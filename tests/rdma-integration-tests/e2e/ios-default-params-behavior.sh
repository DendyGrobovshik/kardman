#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for trailing default parameters on iOS (C-ABI):
# a cross-parameter default (`b = a * 2`), an all-defaulted call, a nullable
# return, a defaulted constructor, a method default referencing `this`, a
# defaulted suspend function, and composable value + empty-lambda defaults.
# Mirrors default-params-behavior.sh but drives the iOS app on a simulator.
#
#   ./tests/rdma-integration-tests/e2e/ios-default-params-behavior.sh
#
# Requirements: Xcode + an available iPhone simulator, the framework published to
# mavenLocal, and the Hermes compiler (third_party/bin/hermesc).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"

BUNDLE_ID="com.example.rdmahermes.iosApp"
PRODUCT="KardmanDemo"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
DERIVED="$ROOT_DIR/tests/stand/iosApp/build/ios-e2e-derived"

EXCEPTIONS_1="$ROOT_DIR/tests/stand/plugin/itest/exceptions"
EXCEPTIONS_2="$ROOT_DIR/tests/stand/plugin/itest/exceptions2"

IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

release() { # release <analysisJson> <polyfillOut> <internalModules>
    "$ROOT_DIR/scripts/release.sh" static-release "$1" "$VERSIONS_DIR" "$2" "$3" ""
}

# The exception crash-test plugins register their own content AFTER the alice Counter
# and throw through it, so they are opted out of the bake (rdmaBakeInApp=false) and
# restored afterwards. Mirrors ios-suspend-behavior.sh; the widget-default markers
# require the alice Counter to be the rendered content.
drop_exception_plugins() {
    echo "rdmaBakeInApp=false" > "$EXCEPTIONS_1/gradle.properties"
    echo "rdmaBakeInApp=false" > "$EXCEPTIONS_2/gradle.properties"
}

restore_exception_plugins() {
    rm -f "$EXCEPTIONS_1/gradle.properties" "$EXCEPTIONS_2/gradle.properties"
}

LAUNCH_PID=""
cleanup() {
    [ -n "$LAUNCH_PID" ] && kill "$LAUNCH_PID" 2>/dev/null || true
    restore_exception_plugins
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
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-internal" "internal"

    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-alice" ""

    run_gradle :tests:stand:kernel:user:itest:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/itest/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-itest" ""
}

# 1) Build the iOS app. The Xcode build phase invokes Gradle; we pass the scratch
# versions dir via the ORG_GRADLE_PROJECT_* env convention so it reaches the build.
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

# 2) Install, launch (console output captured), and assert the markers.
step_2_run_and_assert() {
    step "2: install, launch, assert markers"
    local id="$1" log="$2"
    local app="$DERIVED/Build/Products/Debug-iphonesimulator/$PRODUCT.app"
    [ -d "$app" ] || fail "app not built at $app"

    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
    xcrun simctl install "$id" "$app"

    xcrun simctl launch --console-pty "$id" "$BUNDLE_ID" > "$log" 2>&1 &
    LAUNCH_PID=$!

    wait_marker "$log" "DEFAULT_FN: add=15"
    wait_marker "$log" "DEFAULT_FN2: greet=hello world!"
    wait_marker "$log" "DEFAULT_NULL: flag:default-label"
    wait_marker "$log" "DEFAULT_CTOR: tag=n:1"
    wait_marker "$log" "DEFAULT_METHOD: bump=6"
    wait_marker "$log" "SUSPEND_DEFAULT: sum=15"

    # Composable defaults: the alice Counter widget tree calls TextWithDefault() and
    # ColumnWithDefault() with no arguments, so the value defaults ("default"/"title")
    # and the empty-lambda default must be resolved on the native side.
    wait_marker "$log" "WIDGET_TEXT_DEFAULT: title: default"
    wait_marker "$log" "WIDGET_COLUMN_DEFAULT"

    kill "$LAUNCH_PID" 2>/dev/null || true
    LAUNCH_PID=""
    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
}

main() {
    local id log
    id="$(resolve_sim)"
    log="$(mktemp -t rdma-ios-default-params)"

    drop_exception_plugins
    step_0_static_release
    step_1_build
    step_2_run_and_assert "$id" "$log"

    echo
    echo "ALL IOS DEFAULT-PARAMS STEPS PASSED"
}

main "$@"
