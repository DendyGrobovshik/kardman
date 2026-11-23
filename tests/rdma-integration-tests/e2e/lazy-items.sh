#!/usr/bin/env bash
set -euo pipefail

# End-to-end test of plugin lambdas as lazy list items and of states kept in a plugin
# collection (field report 5): the alice `lazy` component scrolls kernel lazy lists whose
# items are a remembered and an inline plugin `@Composable (Int) -> Unit` — no item may
# compose empty; the `stateMap` component writes states read back from `remember` on a
# recomposition. Driven against the in-repo stand on an Android emulator.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"

APP_ID="org.example"


IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"

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

release() { # release <analysisJson> <polyfillOut> <internalModules>
    "$ROOT_DIR/scripts/release.sh" static-release "$1" "$VERSIONS_DIR" "$2" "$3" ""
}

cleanup() {
    rm -rf "$IT_DIR"
}
trap cleanup EXIT

# 0) Static-release the kernel so the app/plugin builds can read the changelog.
step_0_static_release() {
    step "0: static release kernel"
    run_gradle ":rdma-kernel-compiler-plugin:generationRelease" \
        -Pgeneration="g1" -PversionsDir="$VERSIONS_DIR" -Palias="legacy-single-generation" \
        -PrdmaPolicyFile="$ROOT_DIR/rdma.toml"

    run_gradle :tests:stand:kernel:internal:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-internal" "internal"

    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-alice" ""
}

# 1) Build and launch the app.
step_1_build_app() {
    step "1: build and install app"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :tests:stand:androidApp:assembleDebug
    adb install -r "$ROOT_DIR/tests/stand/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
}

launch() { # launch <component>
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" --es rdmaScreen "$1" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}


log_line() { # log_line <substring>: the last matching line of the app's output
    adb logcat -d 2>/dev/null | grep "$1" | tail -1
}

step_2_lazy_items() {
    step "2: plugin lambdas as lazy list items"
    launch "alice:counter/lazy"
    local tries=0
    while [ "$(adb logcat -d 2>/dev/null | grep -c 'LAZY_PROBE')" -lt 2 ] && [ $tries -lt 240 ]; do
        sleep 0.5; tries=$((tries+1))
    done
    for tag in stable inline; do
        local line; line="$(log_line "LAZY_PROBE $tag:")"
        echo "$line"
        [ -n "$line" ] || fail "no result for the $tag list"
        echo "$line" | grep -q "holes=0 " || fail "the $tag list has items that composed nothing: $line"
    done
}

step_3_state_map() {
    step "3: states kept in a plugin map"
    launch "alice:counter/stateMap"
    wait_logcat "RdmaRuntime" "STATE_MAP:"
    local line; line="$(log_line "STATE_MAP:")"
    echo "$line"
    echo "$line" | grep -q "STATE_MAP: ok same=true" || fail "writing a state read back from remember failed: $line"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_app
    step_2_lazy_items
    step_3_state_map

    echo
    echo "PASS: plugin lambdas compose every lazy item; remembered states keep their type"
}

main "$@"
