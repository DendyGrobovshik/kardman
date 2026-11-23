#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the typed state channels
# (docs/design/type_descriptors.md): every `mutableStateOf`/`mutableStateListOf`
# value is marshalled by the type descriptor baked at its call site, `Long` keeps
# all 64 bits (the plugin's `Long(low, high)` object), and `null` is a value on
# nullable states. Also covers the full boundary type set on state channels
# (`@RDMA` enums and objects, lists/maps), the Long matrix P1-P7, and the loud
# failures: a raw JS number in a Long channel, a foreign type, `null` in a
# non-null channel, and an unknown enum name must all raise `RdmaException`.
# Driven against the in-repo stand on an Android emulator.
#
# The stand renders the last plugin content registered; the state probe lives in
# the alice/counter module (StateProbe.kt). The crash-test plugins
# (itest/exceptions{,2}) are not baked into the stand by default.
#
#   ./tests/rdma-integration-tests/e2e/state-behavior.sh
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
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    wait_logcat "RDMA" "Runtime ready"
}

# 2) Descriptor-typed scalar values round-trip exactly (Double keeps its fraction).
step_2_scalars() {
    step "2: scalar round-trips"
    wait_logcat "RdmaRuntime" "STATE_DOUBLE: 19.9"
    wait_logcat "RdmaRuntime" "SUBPACKAGE: state=sub"
    wait_logcat "RdmaRuntime" "CAPTURE: body=7"
    wait_logcat "RdmaRuntime" "CAPTURE: content=7"
    wait_logcat "RdmaRuntime" "STATE_DOUBLE_SET: 20.5"
    # derivedStateOf: the reader runs only when the result changes (1 + 3, not 31).
    wait_logcat "RdmaRuntime" "DERIVED: runs=4 tens=3 label=tick 0 error=calc boom"
}

# 3) Long keeps all 64 bits: 2^53+1 round-trips exactly, plugin-side Long
#    arithmetic stays exact, and Long.MAX_VALUE survives the crossing.
step_3_long() {
    step "3: Long full fidelity"
    wait_logcat "RdmaRuntime" "STATE_LONG: 9007199254740993"
    wait_logcat "RdmaRuntime" "STATE_LONG_SET: 9007199254740994"
    wait_logcat "RdmaRuntime" "STATE_LONG_MAX: 9223372036854775807"
}

# 4) Nullable states accept `null` and later writes.
step_4_null() {
    step "4: nullable state"
    wait_logcat "RdmaRuntime" "STATE_NULL_AFTER: set"
}

# 5) Snapshot collections: `mutableStateListOf` mutations run through the kernel
#    SnapshotStateList and are visible through the proxy immediately.
step_5_snapshot_list() {
    step "5: snapshot list"
    wait_logcat "RdmaRuntime" "STATE_SLIST: size=3 first=1"
}

# 6) Long matrix P1-P7: literal -> arg -> echo, Long.MAX_VALUE round-trip, kernel
#    return -> `+1L` arithmetic, suspend result, state value, List<Long> element,
#    and a `Long` field of an `@RDMA` class.
step_6_long_matrix() {
    step "6: Long matrix"
    wait_logcat "RdmaRuntime" "STATE_LONG_P1: 2"
    wait_logcat "RdmaRuntime" "STATE_LONG_P2: 9223372036854775807"
    wait_logcat "RdmaRuntime" "STATE_LONG_P3: 9007199254740994"
    wait_logcat "RdmaRuntime" "STATE_LONG_P4: 42"
    wait_logcat "RdmaRuntime" "STATE_LONG_P5: 8"
    wait_logcat "RdmaRuntime" "STATE_LONG_P6: 9007199254740993"
    wait_logcat "RdmaRuntime" "STATE_LONG_P6_STATE: 9007199254740993"
    wait_logcat "RdmaRuntime" "STATE_LONG_P7: 100"
    wait_logcat "RdmaRuntime" "STATE_LONG_P7_SET: 101"
}

# 7) Enum matrix: entries cross as name strings on state, list (snapshot and
#    kernel channels), map keys, map values, nested lists, and nullable enums.
step_7_enum_matrix() {
    step "7: enum matrix"
    wait_logcat "RdmaRuntime" "STATE_ENUM_STATE: GET"
    wait_logcat "RdmaRuntime" "STATE_ENUM_STATE_SET: POST"
    wait_logcat "RdmaRuntime" "STATE_ENUM_SLIST: first=GET last=POST"
    wait_logcat "RdmaRuntime" "STATE_ENUM_LIST: first=GET last=POST"
    wait_logcat "RdmaRuntime" "STATE_ENUM_MAP_KEY: b"
    wait_logcat "RdmaRuntime" "STATE_ENUM_MAP_VALUE: POST"
    wait_logcat "RdmaRuntime" "STATE_ENUM_NESTED: GET"
    wait_logcat "RdmaRuntime" "STATE_ENUM_NULLABLE: null"
    wait_logcat "RdmaRuntime" "STATE_ENUM_NULL_SET: GET"
}

# 8) `@RDMA` object values on a state channel round-trip as owned handles.
step_8_object() {
    step "8: @RDMA object state"
    wait_logcat "RdmaRuntime" "STATE_OBJ: name=ada age=3"
    wait_logcat "RdmaRuntime" "STATE_OBJ_SET: name=bob age=4"
}

# 9) Loud failures: every wrongly-typed write raises `RdmaException` (the
#    `STATE_NEG_<case>_CAUGHT` marker); a silent accept prints `_NO_THROW`, which
#    must NOT appear.
step_9_negatives() {
    step "9: loud-failure negatives"
    wait_logcat "RdmaRuntime" "STATE_NEG_RAW_NUMBER_IN_LONG_FN_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_RAW_NUMBER_IN_LONG_STATE_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_RAW_NUMBER_IN_LONG_LIST_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_RAW_NUMBER_IN_LONG_MAP_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_FOREIGN_TYPE_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_NULL_IN_NONNULL_CAUGHT"
    wait_logcat "RdmaRuntime" "STATE_NEG_ENUM_BAD_NAME_CAUGHT"

    if adb logcat -d -s "RdmaRuntime" 2>/dev/null | grep -qE "STATE_[A-Z0-9_]*_NO_THROW"; then
        adb logcat -d -s "RdmaRuntime" | grep -E "STATE_[A-Z0-9_]*_NO_THROW" >&2
        fail "a wrongly-typed write was silently accepted"
    fi
    if adb logcat -d -s "RdmaRuntime" 2>/dev/null | grep -qE \
        "STATE_(SCALARS|LONG|ENUM|OBJECT|NEGATIVES)_ERROR:|STATE_SLIST_UNSUPPORTED:|STATE_LONG_P6_STATE_UNSUPPORTED:"; then
        adb logcat -d -s "RdmaRuntime" | grep -E "_ERROR:|_UNSUPPORTED:" >&2
        fail "a probe suite failed outright"
    fi
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch_app
    step_2_scalars
    step_3_long
    step_4_null
    step_5_snapshot_list
    step_6_long_matrix
    step_7_enum_matrix
    step_8_object
    step_9_negatives

    echo
    echo "PASS: state channels honor their type descriptors"
}

main "$@"
