#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for cross-runtime exception propagation
# (docs/design/exception_propagation.md), driven against the in-repo stand on an
# Android emulator.
#
#   ./tests/rdma-integration-tests/e2e/exception-propagation.sh
#
# Requirements: a booted emulator (adb devices), the framework published to
# mavenLocal, and the Hermes compiler (third_party/bin/hermesc).

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

cleanup() { rm -rf "$IT_DIR"; }
trap cleanup EXIT

# 0) Static-release the kernel so the app/plugin builds can read the changelog.
step_0_static_release() {
    step "0: static release kernel"
    run_gradle ":rdma-kernel-compiler-plugin:generationRelease" \
        -Pgeneration="g1" -PversionsDir="$VERSIONS_DIR" -Palias="legacy-single-generation" \
        -PrdmaPolicyFile="$ROOT_DIR/rdma.toml"

    run_gradle :tests:stand:kernel:internal:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-internal" "internal"

    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-alice" ""

    run_gradle :tests:stand:kernel:user:itest:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/itest/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-itest" ""
}

# 1) Build and launch the app (baked kernel + exceptions plugin).
step_1_build_and_launch_app() {
    step "1: build and launch app"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :tests:stand:androidApp:assembleDebug
    adb install -r "$ROOT_DIR/tests/stand/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

# 2) Deterministic scenarios run on plugin load (main()): S2 (Native -> JS).
step_2_sync_scenarios() {
    step "2: S2 (native -> JS)"
    wait_logcat "RdmaRuntime" "S2_CAUGHT:"
}

# 3) H2 global handler: exceptions2 registers no per-plugin handler, so its throwing
# lambda reaches the host's global handler.
step_3_h2_handler() {
    step "3: H2 global handler"
    wait_logcat "RDMA_TEST" "H2_UNCAUGHT:itest:exceptions2:"
}

# 4) S1a + double crossing: the exceptions2 plugin content throws once (host -> JS ->
# host). Because a content throw drives a nested Compose composition, it cannot be
# caught without corrupting the Composer (Start/end imbalance); the host therefore
# crashes with the RdmaException, and the crash carries the double-crossed cause chain.
step_4_s1a_double_crossing() {
    step "4: S1a + double crossing (content throw -> host RdmaException crash)"
    wait_logcat "AndroidRuntime" "RdmaException"
    adb logcat -d -s AndroidRuntime 2>/dev/null | grep -q "Caused by:" || fail "missing double-crossed cause chain"
}

# 5) A2 BundleLoadException: drop a corrupt .hbc into filesDir/rdma/, restart, and
# assert nativeIsReady() surfaces BundleLoadException (design §7). The host's re-sync
# thread polls nativeIsReady() and logs the thrown message; the corrupt bundle's
# "Compiling JS failed" message proves the exception crossed back to the host.
step_5_a2_bundle_load() {
    step "5: A2 BundleLoadException (corrupt bundle)"
    local bad="$IT_DIR/bad.hbc"
    head -c 64 /dev/urandom > "$bad"
    adb push "$bad" /data/local/tmp/bad.hbc >/dev/null
    adb shell "run-as $APP_ID mkdir -p files/rdma && run-as $APP_ID cp /data/local/tmp/bad.hbc files/rdma/bad.hbc"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "re-sync unavailable: Compiling JS failed"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch_app
    step_2_sync_scenarios
    step_3_h2_handler
    step_4_s1a_double_crossing
    step_5_a2_bundle_load

    echo
    echo "ALL EXCEPTION-PROPAGATION STEPS PASSED"
}

main "$@"
