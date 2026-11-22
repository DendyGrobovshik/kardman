#!/usr/bin/env bash
set -euo pipefail

# End-to-end test of the recomposition tracer (docs/guide/threads_and_performance.md
# #recomposition-tracer): the stand starts with tracing on, the alice `TraceProbe` fixture
# ticks three times, and the report must count each composable's runs and name why each
# run happened (own state, changed argument, skipped). Driven against the in-repo stand on
# an Android emulator.

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
step_1_build_and_launch_app() {
    step "1: build and launch app"
    "$ROOT_DIR/scripts/publish.sh"
    run_gradle :tests:stand:androidApp:assembleDebug
    adb install -r "$ROOT_DIR/tests/stand/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb logcat -c
    adb shell am force-stop "$APP_ID"
    adb shell am start -n "$APP_ID/.MainActivity" --ez rdmaTrace true >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

# 2) Descriptor-typed scalar values round-trip exactly (Double keeps its fraction).
report_has() { # report_has <ERE>
    adb logcat -d -s "RdmaTrace" 2>/dev/null | grep -qE "$1" && return 0
    echo "---- report ----" >&2
    adb logcat -d -s "RdmaTrace" >&2 || true
    fail "report lacks: $1"
}

step_2_report() {
    step "2: trace report"
    wait_logcat "RdmaRuntime" "TRACE: ticks=3"
    adb shell am broadcast -a org.example.rdma.TRACE -p "$APP_ID" >/dev/null
    wait_logcat "RdmaTrace" "Plugin composable runs"
    local p='com\.example\.plugin\.alice'
    report_has "$p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=3 byParent=0"
    report_has "restarted by: state@[0-9a-f]+<Integer>, read in $p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\) x3"
    report_has "$p\.TraceChild \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=0 byParent=3"
    report_has "called again by: $p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\) x3"
    report_has "changed params: #0 x3"
    report_has "$p\.TraceSkipped \(TraceProbe_rdma\.kt:[0-9]+\): runs=1 first=1 restarts=0 byParent=0"
    report_has "$p\.TraceSelf \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=3 byParent=0"
    report_has "restarted by: state@[0-9a-f]+<Integer>, read in $p\.TraceSelf \(TraceProbe_rdma\.kt:[0-9]+\) x3"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch_app
    step_2_report

    echo
    echo "PASS: the recomposition tracer counts runs and names their causes"
}

main "$@"
