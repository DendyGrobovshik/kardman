#!/usr/bin/env bash
set -euo pipefail

# Release order (§9): build → checks → tests → write changelog → materialize polyfill bundles.
# The changelog writer is CI only, never a developer. This script is the single
# entry point CI uses to release a kernel module.
#
# Usage:
#   scripts/release.sh <command> <analysis.json> <versionsDir> [polyfillOutDir] [internalModules] [telemetryFile]
#
#   command ∈ static-release | dynamic-release | hotfix-release
#
# After the changelog write, any materialized polyfill sources in <polyfillOutDir> are
# compiled to Hermes bytecode (.hbc) bundles (the F/R artifacts served by the store, §8.1),
# written next to the `.kt` sources.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMAND="${1:?usage: release.sh <command> <analysis.json> <versionsDir> [polyfillOutDir] [internalModules] [telemetryFile]}"
ANALYSIS_JSON="${2:?}"
VERSIONS_DIR="${3:?}"
POLYFILL_OUT="${4:-}"
INTERNAL_MODULES="${5:-internal}"
TELEMETRY="${6:-}"
HERMESC="${HERMESC:-$ROOT_DIR/tools/hermesc}"

case "$COMMAND" in
  static-release) TASK=staticRelease ;;
  dynamic-release) TASK=dynamicRelease ;;
  hotfix-release) TASK=hotfixRelease ;;
  *) echo "unknown command: $COMMAND" >&2; exit 2 ;;
esac

# Compiles every materialized polyfill `.kt` in $1 into a `.hbc` bundle, using a scratch
# Kotlin/JS project (the polyfill sources are self-contained: an `external object RDMA` +
# `js("RDMA").<name> = ::<name>` registration).
compile_polyfills() {
    local dir="$1"
    [ -d "$dir" ] || return 0
    local -a sources=()
    local src
    for src in "$dir"/*.kt; do
        [ -e "$src" ] && sources+=("$src")
    done
    [ "${#sources[@]}" -gt 0 ] || return 0

    local scratch
    scratch="$(mktemp -d)"
    trap 'rm -rf "$scratch"' RETURN
    mkdir -p "$scratch/src"
    cp "${sources[@]}" "$scratch/src/"

    cat > "$scratch/settings.gradle.kts" <<EOF
pluginManagement { repositories { mavenLocal(); mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral(); google() } }
rootProject.name = "rdma-polyfill"
EOF
    cat > "$scratch/build.gradle.kts" <<EOF
plugins { id("org.jetbrains.kotlin.multiplatform") version "2.4.10" }
kotlin {
    js(IR) { browser(); binaries.executable() }
    sourceSets { jsMain { kotlin.srcDir("$scratch/src") } }
}
EOF

    echo "== 3/3: compile polyfill bundle(s) =="
    "$ROOT_DIR/gradlew" -p "$scratch" jsProductionExecutableCompileSync --console=plain >/dev/null

    local js
    js="$(find "$scratch/build/compileSync" -name 'rdma-polyfill.js' | head -1)"
    if [ -z "$js" ]; then
        echo "WARNING: no JS produced for polyfill in $dir" >&2
        return 0
    fi
    local out="$dir/polyfill.hbc"
    "$HERMESC" -O -emit-binary -out "$out" "$js"
    echo "wrote $out"
}

echo "== 1/3: hard gate — tests =="
(cd "$ROOT_DIR" && ./gradlew :rdma-tests:test :rdma-kernel-compiler-plugin:test :rdma-store:test)

echo "== 2/3: release ($COMMAND) =="
cd "$ROOT_DIR"
./gradlew ":rdma-kernel-compiler-plugin:$TASK" \
  -PanalysisJson="$ANALYSIS_JSON" \
  -PversionsDir="$VERSIONS_DIR" \
  -PpolyfillOutDir="$POLYFILL_OUT" \
  -PinternalModules="$INTERNAL_MODULES" \
  -PtelemetryFile="$TELEMETRY"

if [ -n "$POLYFILL_OUT" ]; then
    compile_polyfills "$POLYFILL_OUT"
fi
