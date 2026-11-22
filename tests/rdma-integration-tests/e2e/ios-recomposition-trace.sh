#!/usr/bin/env bash
set -euo pipefail

# iOS counterpart of recomposition-trace.sh: the stand starts with the recomposition tracer
# on (SIMCTL_CHILD_RDMA_TRACE=1) and prints its report every second; the alice `TraceProbe`
# fixture ticks three times and the report must count each composable's runs and name why
# each run happened. Driven against the in-repo stand on an iOS simulator.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"

BUNDLE_ID="com.example.rdmahermes.iosApp"
PRODUCT="KardmanDemo"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
DERIVED="$ROOT_DIR/tests/stand/iosApp/build/ios-e2e-derived"


IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"

fail() {
    echo "FAIL: $*" >&2
    if [ -n "${LAUNCH_LOG:-}" ] && [ -f "$LAUNCH_LOG" ]; then
        echo "==== diagnostics from launch log ($LAUNCH_LOG) ====" >&2
        grep -E 'RdmaTrace|TRACE:' "$LAUNCH_LOG" | tail -40 >&2
    fi
    exit 1
}
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

release() { # release <analysisJson> <polyfillOut> <internalModules>
    "$ROOT_DIR/scripts/release.sh" static-release "$1" "$VERSIONS_DIR" "$2" "$3" ""
}

LAUNCH_PID=""
cleanup() {
    [ -n "$LAUNCH_PID" ] && kill "$LAUNCH_PID" 2>/dev/null || true
    rm -rf "$IT_DIR"
}
trap cleanup EXIT

# Resolve a simulator device id, booting one if needed.
resolve_sim() {
    local id
    id="$(xcrun simctl list devices available | grep -E "$SIM_NAME \(" | grep -oE '[0-9A-F-]{36}' | head -1)"
    [ -n "$id" ] || fail "no available simulator matching '$SIM_NAME'"
    if ! xcrun simctl list devices | grep -E "$id" | grep -q "Booted"; then
        xcrun simctl boot "$id" 2>/dev/null || true
        xcrun simctl bootstatus "$id" -b >/dev/null
    fi
    echo "$id"
}

wait_marker() { # wait_marker <file> <needle>
    local file="$1" needle="$2" tries=0
    while [ $tries -lt 240 ]; do
        if grep -q "$needle" "$file" 2>/dev/null; then return 0; fi
        sleep 0.5; tries=$((tries+1))
    done
    fail "timed out waiting for '$needle'"
}

# 0) Static-release the kernel (same changelog the app/plugin builds read).
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

# 1) Build the iOS app (exception plugins dropped from the bake first). The Xcode
#    build phase invokes Gradle; the scratch versions dir is passed via the
#    ORG_GRADLE_PROJECT_* env convention.
step_1_build() {
    step "1: build iOS app"
    "$ROOT_DIR/scripts/publish.sh"
    ORG_GRADLE_PROJECT_rdmaVersionsDir="$VERSIONS_DIR" \
        xcodebuild \
        -project "$ROOT_DIR/tests/stand/iosApp/iosApp.xcodeproj" \
        -scheme iosApp \
        -sdk iphonesimulator \
        -destination "platform=iOS Simulator,name=$SIM_NAME" \
        -derivedDataPath "$DERIVED" \
        build >/dev/null
}

report_has() { # report_has <file> <ERE>: the cumulative report reaches it within 10 s
    local file="$1" re="$2" tries=0
    while [ $tries -lt 20 ]; do
        if grep -E "RdmaTrace: " "$file" | grep -qE "$re"; then return 0; fi
        sleep 0.5; tries=$((tries+1))
    done
    fail "report lacks: $re"
}

# 2) Install, launch with tracing on, assert the report.
step_2_run_and_assert() {
    step "2: install, launch traced, assert the report"
    local id="$1" log="$2"
    local app="$DERIVED/Build/Products/Debug-iphonesimulator/$PRODUCT.app"
    [ -d "$app" ] || fail "app not built at $app"

    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
    xcrun simctl install "$id" "$app"

    SIMCTL_CHILD_RDMA_TRACE=1 xcrun simctl launch --console-pty "$id" "$BUNDLE_ID" > "$log" 2>&1 &
    LAUNCH_PID=$!

    wait_marker "$log" "TRACE: ticks=3"
    wait_marker "$log" "RdmaTrace: Plugin composable runs"
    local p='com\.example\.plugin\.alice'
    report_has "$log" "$p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=3 byParent=0"
    report_has "$log" "restarted by: state@[0-9a-f]+<Int>, read in $p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\) x3"
    report_has "$log" "$p\.TraceChild \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=0 byParent=3"
    report_has "$log" "called again by: $p\.TraceProbe \(TraceProbe_rdma\.kt:[0-9]+\) x3"
    report_has "$log" "changed params: #0 x3"
    report_has "$log" "$p\.TraceSkipped \(TraceProbe_rdma\.kt:[0-9]+\): runs=1 first=1 restarts=0 byParent=0"
    report_has "$log" "$p\.TraceSelf \(TraceProbe_rdma\.kt:[0-9]+\): runs=4 first=1 restarts=3 byParent=0"
    report_has "$log" "restarted by: state@[0-9a-f]+<Int>, read in $p\.TraceSelf \(TraceProbe_rdma\.kt:[0-9]+\) x3"

    kill "$LAUNCH_PID" 2>/dev/null || true
    LAUNCH_PID=""
    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
}

main() {
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build

    local id log
    id="$(resolve_sim)"
    log="$IT_DIR/ios-trace.log"
    LAUNCH_LOG="$log"
    step_2_run_and_assert "$id" "$log"

    echo
    echo "PASS: the recomposition tracer counts runs and names their causes on iOS"
}

main "$@"
