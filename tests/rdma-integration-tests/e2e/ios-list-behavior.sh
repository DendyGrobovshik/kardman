#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the List bridge on iOS (mutation, nested-list
# materialization, cross-runtime read/write). Mirrors list-behavior.sh but drives
# the iOS app on a simulator.
#
#   ./tests/rdma-integration-tests/e2e/ios-list-behavior.sh
#
# Requirements: Xcode + an available iPhone simulator, the framework published to
# mavenLocal, and the Hermes compiler (third_party/bin/hermesc).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"

BUNDLE_ID="com.example.rdmahermes.iosApp"
PRODUCT="KardmanDemo"
SIM_NAME="${IOS_SIM_NAME:-iPhone 17}"
DERIVED="$ROOT_DIR/tests/stand/iosApp/build/ios-e2e-derived"

IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

release() { # release <analysisJson> <polyfillOut> <internalModules>
    "$ROOT_DIR/scripts/release.sh" static-release "$1" "$VERSIONS_DIR" "$2" "$3" ""
}

cleanup() { rm -rf "$IT_DIR"; }
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
    local launch_pid=$!
    trap 'kill "$launch_pid" 2>/dev/null || true' EXIT

    wait_marker "$log" "LIST_MUT: size=3 first=10 echoedSize=3"
    wait_marker "$log" "LIST_MUT_CLEAR: size=0"
    wait_marker "$log" "LIST_NEST: outer=2 inner0size=2 inner00=1"
    wait_marker "$log" "LIST_XRT_READ: sum=10"
    wait_marker "$log" "LIST_XRT_WRITE: size=3 last=30"
    wait_marker "$log" "LIST_XRT_K2J: size=2 last=100"

    kill "$launch_pid" 2>/dev/null || true
    trap - EXIT
    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
}

main() {
    local id log
    id="$(resolve_sim)"
    log="$(mktemp -t rdma-ios-list)"

    step_0_static_release
    step_1_build
    step_2_run_and_assert "$id" "$log"

    echo
    echo "ALL IOS LIST-BEHAVIOR STEPS PASSED"
}

main "$@"
