#!/usr/bin/env bash
set -euo pipefail

# A kernel release (compatibility.md §9) in its commit order:
#
#   1. tests        — the hard gate;
#   2. prepare      — every check; polyfill sources and the release plan go to <workDir>,
#                     nothing in <versionsDir> changes;
#   3. build        — each F (one per native state of the module among the supported hosts)
#                     and R polyfill is compiled to Hermes bytecode for every live toolchain
#                     generation;
#   4. contract     — the kernel's tests run on an Android emulator against the native code
#                     and with each F loaded (RDMA_CONTRACT_APP=<app Gradle path>);
#   5. commit       — bundles go into the store (content-addressed, gzip copy, signature when
#                     RDMA_SIGNING_KEY is set), then polyfills.json and the changelog are
#                     written. This is the commit point: a failure before it writes nothing.
#
# The changelog writer is CI only, never a developer.
#
# Usage:
#   scripts/release.sh <command> <analysis.json[,analysis.json]> <versionsDir> [workDir] [internalModules] [telemetryFile]
#
#   command ∈ static-release | dynamic-release | hotfix-release
#   DRY_RUN=1   run the checks, print what would ship, write nothing.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The Gradle invocations below configure the root build (Android modules included),
# so the SDK location must be known; it is detected automatically.
source "$ROOT_DIR/scripts/android-sdk.sh"
ensure_android_sdk "$ROOT_DIR" || exit 1

COMMAND="${1:?usage: release.sh <command> <analysis.json[,analysis.json]> <versionsDir> [workDir] [internalModules] [telemetryFile]}"
ANALYSIS_JSON="${2:?}"
VERSIONS_DIR="${3:?}"
WORK_DIR="${4:-$ROOT_DIR/build/rdma-release}"
INTERNAL_MODULES="${5:-internal}"
TELEMETRY="${6:-}"
DRY_RUN="${DRY_RUN:-0}"
HERMESC="${HERMESC:-$ROOT_DIR/third_party/bin/hermesc}"
# The toolchain this machine builds with; a generation pinned to another one is `blocked`.
KOTLIN_VERSION="${KOTLIN_VERSION:-2.3.21-rdma}"

case "$COMMAND" in
  static-release) TASK=staticRelease ;;
  dynamic-release) TASK=dynamicRelease ;;
  hotfix-release) TASK=hotfixRelease ;;
  *) echo "unknown command: $COMMAND" >&2; exit 2 ;;
esac

# Compiles the polyfill sources in <dir> once to JS, then to one `<G>.hbc` per generation.
compile_polyfill() { # compile_polyfill <dir> <generation>...
    local dir="$1"; shift
    local -a sources=()
    local src
    for src in "$dir"/*.kt; do
        [ -e "$src" ] && sources+=("$src")
    done
    [ "${#sources[@]}" -gt 0 ] || return 0

    local scratch
    scratch="$(mktemp -d)"
    mkdir -p "$scratch/src"
    cp "${sources[@]}" "$scratch/src/"

    cat > "$scratch/settings.gradle.kts" <<EOF
pluginManagement { repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral(); google() } }
rootProject.name = "rdma-polyfill"
EOF
    cat > "$scratch/build.gradle.kts" <<EOF
plugins { id("org.jetbrains.kotlin.multiplatform") version "$KOTLIN_VERSION" }
kotlin {
    js(IR) { browser(); binaries.executable() }
    sourceSets { jsMain { kotlin.srcDir("$scratch/src") } }
}
EOF
    "$ROOT_DIR/gradlew" -p "$scratch" jsProductionExecutableCompileSync --console=plain >/dev/null

    local js
    js="$(find "$scratch/build/compileSync" -name 'rdma-polyfill.js' | head -1)"
    if [ -z "$js" ]; then
        echo "WARNING: no JS produced for polyfill in $dir" >&2
        rm -rf "$scratch"
        return 0
    fi
    local g name
    for g in "$@"; do
        if ! generation_buildable "$g"; then
            echo "  $dir gen ${g:-"-"}: blocked (pinned toolchain not available here)"
            continue
        fi
        name="${g:-_}"
        "$HERMESC" -O -emit-binary -out "$dir/$name.hbc" "$js"
        echo "  built $dir/$name.hbc"
    done
    rm -rf "$scratch"
}

# A generation is buildable here when its pinned Kotlin is this machine's (or not pinned).
generation_buildable() { # generation_buildable <g>
    [ -z "$1" ] && return 0
    python3 - "$VERSIONS_DIR/toolchain.json" "$1" "$KOTLIN_VERSION" <<'PY'
import json, sys, os
path, g, kotlin = sys.argv[1], sys.argv[2], sys.argv[3]
if not os.path.isfile(path): sys.exit(0)
for gen in json.load(open(path)).get("generations", []):
    if gen.get("g") == g:
        pinned = (gen.get("pins") or {}).get("kotlin", "")
        sys.exit(0 if pinned in ("", kotlin) else 1)
sys.exit(0)
PY
}

echo "== 1/5: hard gate — tests =="
(cd "$ROOT_DIR" && ./gradlew :tests:rdma-tests:test :rdma-kernel-compiler-plugin:test :rdma-store:test)

echo "== 2/5: prepare ($COMMAND) =="
cd "$ROOT_DIR"
./gradlew ":rdma-kernel-compiler-plugin:$TASK" \
  -PanalysisJson="$ANALYSIS_JSON" \
  -PversionsDir="$VERSIONS_DIR" \
  -PpolyfillOutDir="$WORK_DIR" \
  -PinternalModules="$INTERNAL_MODULES" \
  -PtelemetryFile="$TELEMETRY" \
  -PdryRun="$([ "$DRY_RUN" = 1 ] && echo true || echo false)"
[ "$DRY_RUN" = 1 ] && exit 0

PLAN="$WORK_DIR/plan.json"
[ -f "$PLAN" ] || { echo "no release plan at $PLAN" >&2; exit 1; }
read -r -a GENERATIONS <<<"$(python3 -c 'import json,sys; print(" ".join(g if g else "\"\"" for g in json.load(open(sys.argv[1]))["generations"]))' "$PLAN")"
# "" stands for "no generations"; turn the quoted placeholder back into an empty string.
for i in "${!GENERATIONS[@]}"; do [ "${GENERATIONS[$i]}" = '""' ] && GENERATIONS[$i]=""; done
DIRS=()
while IFS= read -r line; do [ -n "$line" ] && DIRS+=("$line"); done < <(python3 -c '
import json, sys
p = json.load(open(sys.argv[1]))
for f in p.get("f", []): print(f["dir"])
if p.get("rDir"): print(p["rDir"])' "$PLAN")

echo "== 3/5: build polyfills (generations: ${GENERATIONS[*]:-none}) =="
for dir in ${DIRS[@]+"${DIRS[@]}"}; do
    compile_polyfill "$dir" "${GENERATIONS[@]}"
done

echo "== 4/5: contract tests =="
if [ -n "${RDMA_CONTRACT_APP:-}" ]; then
    "$ROOT_DIR/scripts/contract-test.sh" "$RDMA_CONTRACT_APP" "$PLAN"
else
    echo "skipped (set RDMA_CONTRACT_APP=<app Gradle path> to run the kernel's tests on an emulator)"
fi

echo "== 5/5: commit =="
./gradlew ":rdma-kernel-compiler-plugin:commitRelease" -PworkDir="$WORK_DIR" -PversionsDir="$VERSIONS_DIR"
