#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the typed state channels on iOS
# (docs/design/type_descriptors.md): every `mutableStateOf` value is marshalled by
# the type descriptor baked at its call site, a `Long` keeps all 64 bits (the
# plugin's `Long(low, high)` object), `null` is a value on nullable states, and the
# full boundary type set on state channels works (`@RDMA` enums and objects,
# lists/maps, snapshot collections). The loud failures hold too: a raw JS number in
# a Long channel, a foreign type, `null` in a non-null channel, and an unknown enum
# name must all raise `RdmaException`. Mirrors state-behavior.sh but drives the
# iOS app on a simulator.
#
#   ./tests/rdma-integration-tests/e2e/ios-state-behavior.sh
#
# Requirements: Xcode + an available iPhone simulator, the framework published to
# mavenLocal, and the Hermes compiler (third_party/bin/hermesc).
#
# The stand renders the last plugin content registered; the state probe lives in
# the alice/counter module (StateTypesProbe). The crash-test plugins
# (itest/exceptions{,2}) are not baked into the stand by default.

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
        grep -E 'STATE_|boxStateValueCAbi|set_value' "$LAUNCH_LOG" | tail -60 >&2
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

# 2) Install, launch (console output captured), and assert the state markers.
step_2_run_and_assert() {
    step "2: install, launch, assert markers"
    local id="$1" log="$2"
    local app="$DERIVED/Build/Products/Debug-iphonesimulator/$PRODUCT.app"
    [ -d "$app" ] || fail "app not built at $app"

    xcrun simctl terminate "$id" "$BUNDLE_ID" 2>/dev/null || true
    xcrun simctl install "$id" "$app"

    xcrun simctl launch --console-pty "$id" "$BUNDLE_ID" > "$log" 2>&1 &
    LAUNCH_PID=$!

    # Scalar round-trips: Double keeps its fraction.
    wait_marker "$log" "STATE_DOUBLE: 19.9"
    wait_marker "$log" "SUBPACKAGE: state=sub"
    wait_marker "$log" "CAPTURE: body=7"
    wait_marker "$log" "CAPTURE: content=7"
    wait_marker "$log" "STATE_DOUBLE_SET: 20.5"
    # Long keeps all 64 bits, including beyond the exact JS-integer range.
    wait_marker "$log" "STATE_LONG: 9007199254740993"
    wait_marker "$log" "STATE_LONG_SET: 9007199254740994"
    wait_marker "$log" "STATE_LONG_MAX: 9223372036854775807"
    # Nullable states accept `null` and later writes.
    wait_marker "$log" "STATE_NULL_AFTER: set"
    # Snapshot collections: mutations run through the kernel SnapshotStateList.
    wait_marker "$log" "STATE_SLIST: size=3 first=1"
    # Long matrix P1-P7.
    wait_marker "$log" "STATE_LONG_P1: 2"
    wait_marker "$log" "STATE_LONG_P2: 9223372036854775807"
    wait_marker "$log" "STATE_LONG_P3: 9007199254740994"
    wait_marker "$log" "STATE_LONG_P4: 42"
    wait_marker "$log" "STATE_LONG_P5: 8"
    wait_marker "$log" "STATE_LONG_P6: 9007199254740993"
    wait_marker "$log" "STATE_LONG_P6_STATE: 9007199254740993"
    wait_marker "$log" "STATE_LONG_P7: 100"
    wait_marker "$log" "STATE_LONG_P7_SET: 101"
    # Enum matrix: name-string crossings on state, lists, map keys/values,
    # nested lists and nullable enums.
    wait_marker "$log" "STATE_ENUM_STATE: GET"
    wait_marker "$log" "STATE_ENUM_STATE_SET: POST"
    wait_marker "$log" "STATE_ENUM_SLIST: first=GET last=POST"
    wait_marker "$log" "STATE_ENUM_LIST: first=GET last=POST"
    wait_marker "$log" "STATE_ENUM_MAP_KEY: b"
    wait_marker "$log" "STATE_ENUM_MAP_VALUE: POST"
    wait_marker "$log" "STATE_ENUM_NESTED: GET"
    wait_marker "$log" "STATE_ENUM_NULLABLE: null"
    wait_marker "$log" "STATE_ENUM_NULL_SET: GET"
    # `@RDMA` object values on a state channel.
    wait_marker "$log" "STATE_OBJ: name=ada age=3"
    wait_marker "$log" "STATE_OBJ_SET: name=bob age=4"
    # Loud-failure negatives raise `RdmaException` at every write point.
    wait_marker "$log" "STATE_NEG_RAW_NUMBER_IN_LONG_FN_CAUGHT"
    wait_marker "$log" "STATE_NEG_RAW_NUMBER_IN_LONG_STATE_CAUGHT"
    wait_marker "$log" "STATE_NEG_RAW_NUMBER_IN_LONG_LIST_CAUGHT"
    wait_marker "$log" "STATE_NEG_RAW_NUMBER_IN_LONG_MAP_CAUGHT"
    wait_marker "$log" "STATE_NEG_FOREIGN_TYPE_CAUGHT"
    wait_marker "$log" "STATE_NEG_NULL_IN_NONNULL_CAUGHT"
    wait_marker "$log" "STATE_NEG_ENUM_BAD_NAME_CAUGHT"

    if grep -qE "STATE_[A-Z0-9_]*_NO_THROW" "$log"; then
        grep -E "STATE_[A-Z0-9_]*_NO_THROW" "$log" >&2
        fail "a wrongly-typed write was silently accepted"
    fi
    if grep -qE "STATE_(SCALARS|LONG|ENUM|OBJECT|NEGATIVES)_ERROR:|STATE_SLIST_UNSUPPORTED:|STATE_LONG_P6_STATE_UNSUPPORTED:" "$log"; then
        grep -E "_ERROR:|_UNSUPPORTED:" "$log" >&2
        fail "a probe suite failed outright"
    fi

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
    log="$IT_DIR/ios-state.log"
    LAUNCH_LOG="$log"
    step_2_run_and_assert "$id" "$log"

    echo
    echo "PASS: state channels honor their type descriptors on iOS"
}

main "$@"
