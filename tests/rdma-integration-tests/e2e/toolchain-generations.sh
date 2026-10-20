#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the toolchain-generation axis
# (docs/design/toolchain_compatibility.md): releasing a plugin under one generation,
# releasing a second generation (which inherits the current version), verifying old and new
# hosts coexist, rolling a hotfix to both generations, blocking a generation that a release
# does not cover (it stays frozen on the last built version), and retiring a generation.
# Driven against the in-repo stand on an Android emulator.
#
# The generation id `G` is simulated: the same real toolchain compiles the same plugin
# source, but each build is tagged with a distinct `builtAgainstToolchain` (passed via
# `-PrdmaBuiltAgainstToolchain`) and recorded in `toolchain.json`. This exercises the
# store's per-generation resolution without an (hours-long) real toolchain rebuild.
#
# The plugin `version` is **global** (one monotonic sequence across generations):
#   v1 -> v2  on g1,  g2 inherits v2,  hotfix v3 (g1+g2),  v4 (g2 only, g1 blocked at v3).
# A `plugin-release` bumps the version once and fans out to every live generation; the
# report marks a generation `blocked` when no bundle was built for it.
#
# Resolution assertions are made against the store's `/resync` endpoint (deterministic);
# the app is used only to prove an actual bundle downloads, loads and renders.
#
#   ./tests/rdma-integration-tests/e2e/toolchain-generations.sh
#
# Requirements: a booted emulator (adb devices), the framework published to mavenLocal,
# and the Hermes compiler (third_party/bin/hermesc).
#
# Adding a brand-new plugin is covered by plugin-lifecycle.sh; this test re-releases one
# store-only plugin across generations, which exercises the same resolution code paths.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"
HERMESC="$ROOT_DIR/third_party/bin/hermesc"

STORE_URL="http://10.0.2.2:8080"   # emulator loopback to host
STORE_PORT=8080
APP_ID="org.example"
RESYNC_ACTION="org.example.rdma.RESYNC"

IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"
BUNDLES_DIR="$VERSIONS_DIR/bundles"
STORE_PID=""

G1="g1"
G2="g2"

PLUG=":tests:stand:plugin:itest:newplug"
PLUG_SRC="$ROOT_DIR/tests/stand/plugin/itest/newplug/src/kotlin/com/example/plugin/itest/NewPlug.kt"
PLUG_JSON="$ROOT_DIR/tests/stand/plugin/itest/newplug/build/generated/rdma/plugin.json"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

adb_dev() { adb devices | grep -q 'emulator-.*\sdevice' || fail "no emulator attached"; }

wait_logcat() { # wait_logcat <tag> <substring>
    local tag="$1" needle="$2" tries=0
    while [ $tries -lt 120 ]; do
        if adb logcat -d -s "$tag" 2>/dev/null | grep -q "$needle"; then return 0; fi
        sleep 0.5; tries=$((tries+1))
    done
    fail "timed out waiting for logcat $tag/$needle"
}

broadcast_resync() { # broadcast_resync <pluginId> <version> <generation>
    local id="$1" version="$2" generation="$3"
    adb shell am broadcast -a "$RESYNC_ACTION" --es pluginId "$id" --ei version "$version" \
        --ei builtAgainst 2 --es hostGeneration "$generation" >/dev/null
}

store_start() {
    mkdir -p "$BUNDLES_DIR"
    (cd "$ROOT_DIR" && VERSIONS_DIR="$VERSIONS_DIR" PORT=$STORE_PORT \
        "$GRADLE" -p "$ROOT_DIR" :rdma-store:run --console=plain >"$IT_DIR/store.log" 2>&1) &
    STORE_PID=$!
    local tries=0
    while [ $tries -lt 120 ]; do
        curl -fsS "http://localhost:$STORE_PORT/health" >/dev/null 2>&1 && return 0
        sleep 0.5; tries=$((tries+1))
    done
    fail "store did not start (see $IT_DIR/store.log)"
}

store_stop() {
    [ -n "$STORE_PID" ] && kill "$STORE_PID" 2>/dev/null || true
    pkill -f "io.github.dendygrobovshik.kardman.store.MainKt" 2>/dev/null || true
}

store_reload() { curl -fsS "http://localhost:$STORE_PORT/reload" >/dev/null; }

seed_bundle() { cp "$2" "$BUNDLES_DIR/$1"; }

release() { # release <command> <analysisJson> <polyfillOutDir>
    "$ROOT_DIR/scripts/release.sh" "$1" "$2" "$VERSIONS_DIR" "$3" "internal" ""
}

generation_release() { # generation_release <g> [alias]
    run_gradle ":rdma-kernel-compiler-plugin:generationRelease" \
        -Pgeneration="$1" -PversionsDir="$VERSIONS_DIR" -Palias="${2:-}" \
        -PrdmaPolicyFile="$IT_DIR/rdma.toml"
}

retire_generation() { # retire_generation <g>
    run_gradle ":rdma-kernel-compiler-plugin:retireGeneration" \
        -Pgeneration="$1" -PversionsDir="$VERSIONS_DIR" \
        -PrdmaPolicyFile="$IT_DIR/rdma.toml"
}

# Writes the plugin source rendering [label] and compiles it against [generation], emitting
# the Hermes bundle to [out]. Does not release.
build_plugin_bundle() { # build_plugin_bundle <label> <generation> <outHbc>
    local label="$1" generation="$2" out="$3"
    cat > "$PLUG_SRC" <<EOF
package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun NewPlugContent() {
    Column {
        Text("$label")
    }
}

fun main() {
    runRdmaApp { NewPlugContent() }
}
EOF
    run_gradle -PrdmaBuiltAgainstToolchain="$generation" "$PLUG:jsProductionExecutableCompileSync"
    # The plugin's own module is the only JS not matching a shared dependency prefix
    # (kotlin-, androidx-, compose-, kotlinx-, kotlin_).
    local js
    js=$(find "$ROOT_DIR/tests/stand/plugin/itest/newplug/build/compileSync" -name '*.js' \
        | grep -Ev '/(kotlin-|androidx-|compose-|kotlinx-|kotlin_)' | head -1)
    [ -n "$js" ] || fail "no plugin JS produced"
    "$HERMESC" -O -emit-binary -out "$out" "$js"
}

# Releases the plugin in one action: bumps the version once and releases to every live
# generation. `--no-bump` records the current version instead (bootstrap a new generation).
# Each <generation>=<bundle> pair is compiled already; bundles are seeded by content hash.
release_plugin() { # release_plugin [--no-bump] <generation>=<bundle> [<generation>=<bundle> ...]
    local no_bump="" first=true pairs=""
    if [ "$1" = "--no-bump" ]; then no_bump=true; shift; fi
    local pair gen bundle
    for pair in "$@"; do
        gen="${pair%%=*}"; bundle="${pair#*=}"
        if [ "$first" = true ]; then first=false; else pairs="${pairs},"; fi
        pairs="${pairs}${gen}=${bundle}"
    done
    local -a gargs=( -PpluginJson="$PLUG_JSON" -PversionsDir="$VERSIONS_DIR" -PgenerationBundles="$pairs" -PrdmaPolicyFile="$IT_DIR/rdma.toml" )
    [ -n "$no_bump" ] && gargs+=( -PnoBump=true )
    run_gradle ":rdma-kernel-compiler-plugin:pluginRelease" "${gargs[@]}"
    for pair in "$@"; do
        bundle="${pair#*=}"
        local hash
        hash=$(shasum -a 256 "$bundle" | awk '{print $1}')
        seed_bundle "$hash" "$bundle"
    done
    store_reload
}

# Queries the store's /resync and prints the resolved targetVersion.
resync_target() { # resync_target <pluginId> <version> <hostGeneration> → targetVersion
    local id="$1" version="$2" generation="$3"
    local body resp
    body="{\"pluginId\":\"$id\",\"version\":$version,\"builtAgainstKernel\":2,\"hostVersion\":2,\"hostGeneration\":\"$generation\"}"
    resp=$(curl -fsS -X POST "http://localhost:$STORE_PORT/resync" -H 'Content-Type: application/json' -d "$body")
    python3 -c 'import json,sys; print(json.load(sys.stdin)["targetVersion"])' <<< "$resp"
}

# Asserts the store resolves [plugin] to [expected] for a host on [generation].
assert_target() { # assert_target <pluginId> <version> <generation> <expected>
    local got
    got=$(resync_target "$1" "$2" "$3")
    [ "$got" = "$4" ] || fail "generation $3 should resolve $1 v$2 -> v$4, got v$got"
    echo "  ok: $3 host on $1 v$2 -> v$got"
}

# ---------------------------------------------------------------------------
# steps
# ---------------------------------------------------------------------------

step_0_static_release() {
    step "0: static release the kernel"
    run_gradle :tests:stand:kernel:internal:compileAndroidMain
    release "static-release" "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-0"
    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "static-release" "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-0"
}

step_1_generation_g1_and_launch() {
    step "1: generation-release $G1 + build and launch app"
    generation_release "$G1" "simulated-gen-1"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :tests:stand:androidApp:assembleDebug
    adb install -r "$ROOT_DIR/tests/stand/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

step_2_plugin_v1_g1() {
    step "2: release plugin v1 on $G1"
    build_plugin_bundle "GENPLUG v1" "$G1" "$IT_DIR/genplug-v1.hbc"
    release_plugin "$G1=$IT_DIR/genplug-v1.hbc"
}

step_3_load_v1_in_app() {
    step "3: a $G1 host loads v1 and renders it"
    broadcast_resync "itest:newplug" 0 "$G1"
    wait_logcat "RDMA" "updated itest:newplug -> v1"
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -q "GENPLUG v1" || fail "GENPLUG v1 not rendered"
}

step_4_plugin_v2_g1() {
    step "4: release plugin v2 on $G1"
    build_plugin_bundle "GENPLUG v2" "$G1" "$IT_DIR/genplug-v2.hbc"
    release_plugin "$G1=$IT_DIR/genplug-v2.hbc"
}

step_5_generation_g2() {
    step "5: generation-release $G2 + $G2 inherits the current version (v2)"
    generation_release "$G2" "simulated-gen-2"
    build_plugin_bundle "GENPLUG v2" "$G2" "$IT_DIR/genplug-g2-v2.hbc"
    release_plugin --no-bump "$G2=$IT_DIR/genplug-g2-v2.hbc"
}

step_6_coexistence() {
    step "6: old and new generations coexist"
    assert_target "itest:newplug" 2 "$G1" 2
    assert_target "itest:newplug" 0 "$G2" 2
}

step_7_hotfix_both() {
    step "7: hotfix v3 to both $G1 and $G2"
    build_plugin_bundle "GENPLUG v3" "$G1" "$IT_DIR/genplug-g1-v3.hbc"
    build_plugin_bundle "GENPLUG v3" "$G2" "$IT_DIR/genplug-g2-v3.hbc"
    release_plugin "$G1=$IT_DIR/genplug-g1-v3.hbc" "$G2=$IT_DIR/genplug-g2-v3.hbc"
    assert_target "itest:newplug" 2 "$G1" 3
    assert_target "itest:newplug" 2 "$G2" 3
}

step_8_blocked_g1() {
    step "8: release v4 on all generations, but $G1 has no build → $G1 frozen at v3"
    build_plugin_bundle "GENPLUG v4" "$G2" "$IT_DIR/genplug-g2-v4.hbc"
    release_plugin "$G2=$IT_DIR/genplug-g2-v4.hbc"
    assert_target "itest:newplug" 3 "$G1" 3
    assert_target "itest:newplug" 3 "$G2" 4
}

step_9_retire_g1() {
    step "9: retire generation $G1 (FIFO floor advances to $G2)"
    retire_generation "$G1"
    python3 - "$VERSIONS_DIR/toolchain.json" "$IT_DIR/rdma.toml" "$G1" "$G2" <<'PY'
import json, sys
toolchain, toml, g1, g2 = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(toolchain) as f:
    gen = json.load(f)["generations"]
g1rec = [g for g in gen if g["g"] == g1]
assert g1rec and g1rec[0]["status"] == "retired", f"{g1} not retired: {gen}"
with open(toml) as f:
    floor = None
    for line in f:
        if line.startswith("oldestActiveGeneration"):
            floor = line.split("=", 1)[1].strip().strip('"')
assert floor == g2, f"expected oldestActiveGeneration={g2}, got {floor!r}"
print(f"generation {g1} retired; oldestActiveGeneration advanced to {g2}")
PY
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

cleanup() {
    store_stop
    rm -rf "$IT_DIR"
}
trap cleanup EXIT

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    store_start

    step_0_static_release
    step_1_generation_g1_and_launch
    step_2_plugin_v1_g1
    step_3_load_v1_in_app
    step_4_plugin_v2_g1
    step_5_generation_g2
    step_6_coexistence
    step_7_hotfix_both
    step_8_blocked_g1
    step_9_retire_g1

    echo
    echo "ALL STEPS PASSED"
}

main "$@"
