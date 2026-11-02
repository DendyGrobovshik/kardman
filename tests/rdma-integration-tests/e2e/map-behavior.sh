#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the Map bridge: mutation, nested-value
# materialization, and cross-runtime read/write (docs/design/collections.md §Map).
# Driven against the in-repo stand on an Android emulator.
#
#   ./tests/rdma-integration-tests/e2e/map-behavior.sh
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

# 1) Build and launch the app.
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

# 2) Map mutation (set/remove) is visible on the same and a second proxy.
step_2_mutation() {
    step "2: map mutation"
    wait_logcat "RdmaRuntime" "MAP_MUT: size=2 b=2"
}

# 3) Nested map values (map of lists) materialize recursively.
step_3_nesting() {
    step "3: nested map value"
    wait_logcat "RdmaRuntime" "MAP_NEST: size=2 inner0size=2 inner00=1"
}

# 4) Cross-runtime: plugin mutates → kernel reads; kernel mutates → plugin reads.
step_4_cross_runtime() {
    step "4: cross-runtime read/write"
    wait_logcat "RdmaRuntime" "MAP_XRT_READ: v=2"
    wait_logcat "RdmaRuntime" "MAP_XRT_WRITE: size=2 y=20"
    wait_logcat "RdmaRuntime" "MAP_XRT_K2J: size=2 k2=v2"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch_app
    step_2_mutation
    step_3_nesting
    step_4_cross_runtime

    echo
    echo "ALL MAP-BEHAVIOR STEPS PASSED"
}

main "$@"
