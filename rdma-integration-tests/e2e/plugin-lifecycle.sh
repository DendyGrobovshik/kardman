#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the plugin lifecycle (§7, §8, §9 of
# docs/compatibility.md), driven against the in-repo demo on an Android emulator.
#
# The 9 steps are each a `step_N_*` function; `main` runs them in order and any
# failure stops the whole run (fail-fast via `set -e` + `fail`).
#
#   ./rdma-integration-tests/e2e/plugin-lifecycle.sh
#
# Requirements: a booted emulator (adb devices), the framework published to
# mavenLocal (done in step 1), and the Hermes compiler (tools/hermesc).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"
HERMESC="$ROOT_DIR/tools/hermesc"

STORE_URL="http://10.0.2.2:8080"   # emulator loopback to host
STORE_PORT=8080
APP_ID="org.example"
RESYNC_ACTION="org.example.rdma.RESYNC"

# Scratch state: a copy of versions/ so the repo's committed changelog is never mutated.
IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"
BUNDLES_DIR="$VERSIONS_DIR/bundles"
STORE_PID=""

# The plugin/user-module fixtures are in-repo modules (bake-in-app disabled for the plugin).
NEWPLUG=":plugin:itest:newplug"
NEWPLUG_SRC="$ROOT_DIR/plugin/itest/newplug/src/kotlin/com/example/plugin/itest/NewPlug.kt"
USER_ITEST_SRC="$ROOT_DIR/kernel/user/itest/src/commonMain/kotlin/com/example/kernel/user/itest/Itest.kt"
ALICE_TAGLINE_SRC="$ROOT_DIR/kernel/user/alice/src/commonMain/kotlin/com/example/kernel/user/alice/Tagline.kt"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain "$@"; }

adb_dev() { adb devices | grep -q 'emulator-.*\sdevice' || fail "no emulator attached"; }

wait_logcat() { # wait_logcat <tag> <substring>
    local tag="$1" needle="$2" tries=0
    while [ $tries -lt 120 ]; do
        if adb logcat -d -s "$tag" 2>/dev/null | grep -q "$needle"; then return 0; fi
        sleep 0.5; tries=$((tries+1))
    done
    fail "timed out waiting for logcat $tag/$needle"
}

broadcast_resync() { # broadcast_resync <pluginId> <version> [builtAgainst]
    local id="$1" version="$2" builtAgainst="${3:-2}"
    adb shell am broadcast -a "$RESYNC_ACTION" --es pluginId "$id" --ei version "$version" --ei builtAgainst "$builtAgainst" >/dev/null
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
    # The `:rdma-store:run` wrapper spawns a detached Java process; kill it directly.
    pkill -f "io.github.dendygrobovshik.kardman.store.MainKt" 2>/dev/null || true
}

store_reload() { # re-read changelog + releases + bundles after a release/step
    curl -fsS "http://localhost:$STORE_PORT/reload" >/dev/null
}

seed_bundle() { cp "$2" "$BUNDLES_DIR/$1"; }

# Runs a kernel release via scripts/release.sh (changelog write + polyfill bundle compilation).
release() { # release <command> <analysisJson> <polyfillOutDir>
    "$ROOT_DIR/scripts/release.sh" "$1" "$2" "$VERSIONS_DIR" "$3" "internal" ""
}

plugin_release() { # plugin_release <pluginJson> <bundlePath>  → prints "hash version" on one line
    local pluginJson="$1" bundle="$2"
    local out hash version
    out=$(run_gradle ":rdma-kernel-compiler-plugin:pluginRelease" \
        -PpluginJson="$pluginJson" -PversionsDir="$VERSIONS_DIR" -PbundlePath="$bundle")
    hash=$(echo "$out" | grep -oE "bundle [0-9a-f]{64}" | awk '{print $2}')
    version=$(echo "$out" | grep -oE "v[0-9]+" | head -1 | tr -d 'v')
    echo "$hash $version"
}

build_plugin_bundle() { # build_plugin_bundle <outHbc>  → compiles NEWPLUG js to hbc
    local out="$1"
    run_gradle "$NEWPLUG:jsProductionExecutableCompileSync"
    local js
    js=$(find "$ROOT_DIR/plugin/itest/newplug/build/compileSync" -name 'RDMAHermes-plugin-itest-newplug.js' | head -1)
    [ -n "$js" ] || fail "no plugin JS produced"
    "$HERMESC" -O -emit-binary -out "$out" "$js"
}

# ---------------------------------------------------------------------------
# steps
# ---------------------------------------------------------------------------

# 1) Compile + launch the app (with the built-in kernel + plugins baked in).
step_1_build_and_launch_app() {
    step "1: build and launch app"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :androidApp:assembleDebug
    adb install -r "$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
    register_baked_plugin "alice:counter" "$ROOT_DIR/androidApp/src/main/assets/kotlin/RDMAHermes-plugin-alice-counter.hbc"
    register_baked_plugin "bob:services" "$ROOT_DIR/androidApp/src/main/assets/kotlin/RDMAHermes-plugin-bob-services.hbc"
}

register_baked_plugin() { # register_baked_plugin <pluginId> <hbc>
    local id="$1" hbc="$2"
    local json
    case "$id" in
        "alice:counter") json="$ROOT_DIR/plugin/alice/counter/build/generated/rdma/plugin.json" ;;
        "bob:services")  json="$ROOT_DIR/plugin/bob/services/build/generated/rdma/plugin.json" ;;
        *) fail "unknown baked plugin $id" ;;
    esac
    local hash version
    read -r hash version <<<"$(plugin_release "$json" "$hbc")"
    seed_bundle "$hash" "$hbc"
}

# 2) Add a new plugin (store-only; not baked into the app).
step_2_add_plugin() {
    step "2: add a store-delivered plugin"
    cat > "$NEWPLUG_SRC" <<'EOF'
package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun NewPlugContent() {
    Column {
        Text("NEWPLUG v1")
    }
}

fun main() {
    runRdmaApp { NewPlugContent() }
}
EOF
    local hbc="$IT_DIR/newplug.hbc"
    build_plugin_bundle "$hbc"
    local hash version
    read -r hash version <<<"$(plugin_release "$ROOT_DIR/plugin/itest/newplug/build/generated/rdma/plugin.json" "$hbc")"
    seed_bundle "$hash" "$hbc"
    echo "released $NEWPLUG v$version ($hash)"
}

# 3) Load the new plugin into the already-running app via re-sync.
step_3_load_plugin_in_running_app() {
    step "3: re-sync loads the new plugin in the running app"
    store_reload
    broadcast_resync "itest:newplug" 0
    wait_logcat "RDMA" "updated itest:newplug -> v1"
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -q "NEWPLUG v1" || fail "NEWPLUG v1 not rendered"
}

# 4) Add a user kernel module + update the plugin to use it (dynamic release → F polyfill).
step_4_add_user_module_and_update_plugin() {
    step "4: add user kernel module + update plugin"
    cat > "$USER_ITEST_SRC" <<'EOF'
package com.example.kernel.user.itest

import io.github.dendygrobovshik.kardman.RDMA

@RDMA
fun itestPlaceholder(): String = "placeholder"

@RDMA
fun itestGreeting(): String = "hello-from-itest-kernel"
EOF
    run_gradle :kernel:user:itest:compileAndroidMain
    local analysis="$ROOT_DIR/kernel/user/itest/build/generated/rdma/rdma_analysis.json"
    local polyfillOut="$IT_DIR/polyfill-f"
    release "dynamic-release" "$analysis" "$polyfillOut"
    local h
    h=$(python3 -c "import json;print(json.load(open('$VERSIONS_DIR/changelog.json'))['h'])")
    if [ -f "$polyfillOut/polyfill.hbc" ]; then
        seed_bundle "F:user_itest:$h" "$polyfillOut/polyfill.hbc"
    fi
    cat > "$NEWPLUG_SRC" <<'EOF'
package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import com.example.kernel.user.itest.itestGreeting

@Composable
fun NewPlugContent() {
    Column {
        Text("NEWPLUG v2: ${itestGreeting()}")
    }
}

fun main() {
    runRdmaApp { NewPlugContent() }
}
EOF
    local hbc="$IT_DIR/newplug-v2.hbc"
    build_plugin_bundle "$hbc"
    local hash version
    read -r hash version <<<"$(plugin_release "$ROOT_DIR/plugin/itest/newplug/build/generated/rdma/plugin.json" "$hbc")"
    seed_bundle "$hash" "$hbc"
    echo "released $NEWPLUG v$version"
}

# 5) Load the updated bundles (plugin v2 + F polyfill) into the running app.
step_5_load_updated_bundles() {
    step "5: re-sync downloads updated plugin + F polyfill"
    store_reload
    broadcast_resync "itest:newplug" 1
    wait_logcat "RDMA" "updated itest:newplug -> v2"
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -q "hello-from-itest-kernel" || fail "new kernel symbol not rendered"
}

# 6) Static release: bake the accumulated kernel into native, rebuild + relaunch.
step_6_static_release_new_app() {
    step "6: static release (bump H, bake native)"
    local analysis="$ROOT_DIR/kernel/user/itest/build/generated/rdma/rdma_analysis.json"
    release "static-release" "$analysis" "$IT_DIR/polyfill-s"
    run_gradle :androidApp:assembleDebug
    adb install -r "$ROOT_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

# 7) Old plugin still works; the new kernel is now native (no F needed).
step_7_old_bundle_works_and_native_baked() {
    step "7: old bundle works, kernel baked in native"
    store_reload
    broadcast_resync "alice:counter" 1
    wait_logcat "RDMA" "current alice:counter v1"
    broadcast_resync "itest:newplug" 0
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb shell cat /sdcard/ui.xml | grep -q "hello-from-itest-kernel" || fail "native-baked symbol not rendered"
}

# 8) Remove a function present since step 1 (aliceTagline) and replace with a manual polyfill.
step_8_remove_native_function_add_manual_polyfill() {
    step "8: remove aliceTagline + manual polyfill (R)"
    cat > "$ALICE_TAGLINE_SRC" <<'EOF'
package com.example.kernel.user.alice

import io.github.dendygrobovshik.kardman.Polyfill

@Polyfill(for = "com.example.kernel.user.alice.aliceTagline")
internal fun aliceTagline_polyfill(): String = "alice-polyfill"
EOF
    run_gradle :kernel:user:alice:compileAndroidMain
    local analysis="$ROOT_DIR/kernel/user/alice/build/generated/rdma/rdma_analysis.json"
    local polyfillOut="$IT_DIR/polyfill-r"
    release "dynamic-release" "$analysis" "$polyfillOut"
    if [ -f "$polyfillOut/polyfill.hbc" ]; then
        seed_bundle "R:user_alice" "$polyfillOut/polyfill.hbc"
    else
        fail "expected an R polyfill bundle to be materialized"
    fi
}

# 9) The manual polyfill reaches the app and is evaluated (dispatch re-wiring).
step_9_manual_polyfill_delivered() {
    step "9: manual polyfill delivered and dispatched"
    store_reload
    broadcast_resync "alice:counter" 1
    # The R bundle (manual polyfill) is downloaded + evaluated by the client; the
    # registration `js("RDMA").aliceTagline = ::aliceTagline_polyfill` re-wires the call.
    wait_logcat "RDMA" "polyfill R:user_alice evaluated"
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
    cp "$ROOT_DIR/versions/changelog.json" "$VERSIONS_DIR/" 2>/dev/null || true
    cp -r "$ROOT_DIR/versions/internal" "$VERSIONS_DIR/" 2>/dev/null || true
    cp -r "$ROOT_DIR/versions/user_alice" "$VERSIONS_DIR/" 2>/dev/null || true

    store_start

    step_1_build_and_launch_app
    step_2_add_plugin
    step_3_load_plugin_in_running_app
    step_4_add_user_module_and_update_plugin
    step_5_load_updated_bundles
    step_6_static_release_new_app
    step_7_old_bundle_works_and_native_baked
    step_8_remove_native_function_add_manual_polyfill
    step_9_manual_polyfill_delivered

    echo
    echo "ALL STEPS PASSED"
}

main "$@"
