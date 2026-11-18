#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for suspend support (docs/design/suspend.md):
# top-level suspend functions (Int/List/Map/Unit/nullable returns, @RDMA argument),
# a suspend method on an @RDMA class, exception propagation, and concurrent
# in-flight calls. Driven against the in-repo stand on an Android emulator.
#
#   ./tests/rdma-integration-tests/e2e/suspend-behavior.sh

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

step_0_static_release() {
    step "0: static release kernel"
    run_gradle ":rdma-kernel-compiler-plugin:generationRelease" \
        -Pgeneration="g1" -PversionsDir="$VERSIONS_DIR" -Palias="legacy-single-generation" \
        -PrdmaPolicyFile="$ROOT_DIR/rdma.toml"

    run_gradle :tests:stand:kernel:internal:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-internal" "internal"

    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-alice" ""

    run_gradle :tests:stand:kernel:user:itest:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/itest/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-itest" ""
}

step_1_build_and_launch() {
    step "1: build and launch app"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :tests:stand:androidApp:assembleDebug
    adb install -r "$ROOT_DIR/tests/stand/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

step_2_suspend_function() {
    step "2: top-level suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_FN: sum=5"
}

step_3_suspend_list() {
    step "3: List-returning suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_LIST: size=3 first=1"
}

step_4_suspend_method() {
    step "4: suspend method on an @RDMA class"
    wait_logcat "RdmaRuntime" "SUSPEND_METHOD: v=value-7"
}

step_5_suspend_error() {
    step "5: suspend exception propagation"
    wait_logcat "RdmaRuntime" "SUSPEND_ERR: caught=suspend boom"
}

step_6_suspend_map() {
    step "6: Map-returning suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_MAP: size=2 b=2"
}

step_7_suspend_unit() {
    step "7: Unit-returning suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_UNIT: ok"
}

step_8_suspend_null() {
    step "8: nullable-returning suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_NULL: null=null v=value-true"
}

step_9_suspend_arg() {
    step "9: @RDMA argument to a suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_ARG: os="
}

step_10_suspend_cause() {
    step "10: nested-cause exception (cause chain preserved)"
    wait_logcat "RdmaRuntime" "SUSPEND_CAUSE: msg=outer boom cause=outer boom inner=inner boom"
}

step_11_suspend_concurrent() {
    step "11: concurrent in-flight suspend calls"
    wait_logcat "RdmaRuntime" "SUSPEND_CONCURRENT_1: 2"
    wait_logcat "RdmaRuntime" "SUSPEND_CONCURRENT_2: 4"
    wait_logcat "RdmaRuntime" "SUSPEND_CONCURRENT_3: 6"
}

step_12_suspend_rdma_return() {
    step "12: @RDMA-object return from a suspend function"
    wait_logcat "RdmaRuntime" "SUSPEND_RDMA: os=suspend-os model=suspend-model version=suspend-version"
}

step_13_timer_delay() {
    step "13: delay(1000) must not complete within 100ms (timer waits)"
    wait_logcat "RdmaRuntime" "SUSPEND_TIMEOUT_OK: result=null"
}

step_14_timer_timeout() {
    step "14: delay(100) must complete within 500ms (timer fires)"
    wait_logcat "RdmaRuntime" "SUSPEND_TIMEOUT_DONE: result=done"
}

step_15_timer_cancel() {
    step "15: cancelling a pending delay"
    wait_logcat "RdmaRuntime" "SUSPEND_CANCEL_OK"
}

step_16_enum_boundary() {
    step "16: @RDMA enums cross as name strings (when/name/ordinal/valueOf)"
    wait_logcat "RdmaRuntime" "ENUM_WHEN: get"
    wait_logcat "RdmaRuntime" "ENUM_SUSPEND_WHEN: post"
    wait_logcat "RdmaRuntime" "ENUM_CONFIG: label=method=POST isGet=false"
    wait_logcat "RdmaRuntime" "ENUM_MEMBERS: name=GET ordinal=0 valueOf=true default=true"
    # Enums in generic positions: list elements, callback parameter, `when` expression (runtime_rework §5.0).
    wait_logcat "RdmaRuntime" "ENUM_GENERIC: codes=g,p names=GET,POST hasPost=true"
    wait_logcat "RdmaRuntime" "ENUM_CALLBACK: POST isPost=true ordinal=1"
    # Plugin overrides called by native code (vtable, runtime_rework §6.4, §2.8): a typed
    # non-String result and a throwing override, on the Hermes and on the UI thread.
    wait_logcat "RdmaRuntime" "VT_HERMES power=9000 greet=I am a cyborg! risky=caught:"
    wait_logcat "System.out" "VT_UI power=9000 greet=I am a cyborg! risky=caught:"
}

# 17) Block lifetime (docs/design/runtime_rework.md §4): 200 lambdas handed to the kernel
#    and dropped there are released once their holders are collected.
step_17_block_lifetime() {
    step "17: dropped plugin lambdas are released"
    wait_logcat "RdmaRuntime" "BLOCKS_DEMO: done"
    adb shell am broadcast -a org.example.rdma.BLOCKS >/dev/null
    wait_logcat "RDMA_TEST" "BLOCKS="
    local n
    n=$(adb logcat -d -s RDMA_TEST 2>/dev/null | grep -o "BLOCKS=[0-9]*" | tail -1 | cut -d= -f2)
    echo "live blocks after GC: $n"
    [ "$n" -lt 100 ] || fail "dropped lambdas were not released ($n live blocks)"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch
    step_2_suspend_function
    step_3_suspend_list
    step_4_suspend_method
    step_5_suspend_error
    step_6_suspend_map
    step_7_suspend_unit
    step_8_suspend_null
    step_9_suspend_arg
    step_10_suspend_cause
    step_11_suspend_concurrent
    step_12_suspend_rdma_return
    step_13_timer_delay
    step_14_timer_timeout
    step_15_timer_cancel
    step_16_enum_boundary
    step_17_block_lifetime

    echo
    echo "ALL SUSPEND-BEHAVIOR STEPS PASSED"
}

main "$@"
