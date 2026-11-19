#!/usr/bin/env bash
set -euo pipefail

# End-to-end integration test for the List bridge: mutation, nested-list
# materialization, and cross-runtime read/write (docs/internals/collections.md).
# Driven against the in-repo stand on an Android emulator.
#
#   ./tests/rdma-integration-tests/e2e/list-behavior.sh
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
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-internal" "internal"

    run_gradle :tests:stand:kernel:user:alice:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-alice" ""

    run_gradle :tests:stand:kernel:user:itest:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/user/itest/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/polyfill-itest" ""
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

# 2) List mutation (set/add/removeAt) is visible on the same and a second proxy.
step_2_mutation() {
    step "2: list mutation"
    wait_logcat "RdmaRuntime" "LIST_MUT: size=3 first=10 echoedSize=3"
    wait_logcat "RdmaRuntime" "LIST_MUT_CLEAR: size=0"
}

# 3) Nested list materializes recursively.
step_3_nesting() {
    step "3: nested list"
    wait_logcat "RdmaRuntime" "LIST_NEST: outer=2 inner0size=2 inner00=1"
}

# 4) Cross-runtime: plugin mutates → kernel reads; kernel mutates → plugin reads.
step_4_cross_runtime() {
    step "4: cross-runtime read/write"
    wait_logcat "RdmaRuntime" "LIST_XRT_READ: sum=10"
    wait_logcat "RdmaRuntime" "LIST_XRT_WRITE: size=3 last=30"
    wait_logcat "RdmaRuntime" "LIST_XRT_K2J: size=2 last=100"
}

# 5) A morphed list iterates (map/joinToString) and toString reads native data
#    (not the stale backing array); a kernel-returned list exposes toString.
step_5_iteration() {
    step "5: list iteration and toString"
    wait_logcat "RdmaRuntime" "LIST_ITER: sum=20 joined=1,2,3,4"
    wait_logcat "RdmaRuntime" "LIST_STR: \[1, 2, 3, 4\]"
    wait_logcat "RdmaRuntime" "LIST_KSTR: \[5, 6\]"
}

# 6) Kernel-returned collections are real Kotlin collections: stdlib idioms (for-in,
#    filter, sorted, first, `in`, destructuring forEach, getOrElse, nested values) work on
#    them (docs/design/runtime_rework.md §3).
step_6_collection_idioms() {
    step "6: stdlib idioms on kernel collections"
    wait_logcat "RdmaRuntime" "COLL_LIST: loop=6 filter=\[2\] sorted=\[1, 2, 3\] first=3 contains=true isList=true"
    wait_logcat "RdmaRuntime" "COLL_MAP: keys=a,b entries=a=1,b=2 forEach=2 getOrElse=none"
    wait_logcat "RdmaRuntime" "COLL_NESTED: flat=10 max=2"
    # A kernel subclass where its kernel superclass is expected (supertypes in the stubs).
    wait_logcat "RdmaRuntime" "SHAPE: square:9.0 name=square side=3"
    # Plugin callbacks passed to a kernel constructor and method (lambda member params).
    wait_logcat "RdmaRuntime" "LAMBDA_CTOR: 1,2,3"
    wait_logcat "RdmaRuntime" "LAMBDA_METHOD: a"
    wait_logcat "RdmaRuntime" "LAMBDA_METHOD: b"
    # Lambdas as values: results, kernel lambdas, properties, identity, background calls.
    wait_logcat "RdmaRuntime" "LAMBDA_RESULT: \\[#7\\] twice=18"
    wait_logcat "RdmaRuntime" "KERNEL_LAMBDA: 15 Hi, Ann"
    wait_logcat "RdmaRuntime" "LAMBDA_PROP: \\[x1\\] same=true call=x2"
    wait_logcat "RdmaRuntime" "LAMBDA_EVENT: ping"
    wait_logcat "RdmaRuntime" "LAMBDA_BG: x9"
    # A component with parameters rendered twice by the host, each with its own state.
    wait_logcat "RdmaRuntime" "CARD: a 1 state=a#1"
    wait_logcat "RdmaRuntime" "CARD: b 2 state=b#2"
    # Modifiers (@RDMA class): the chain applies, scope elements only in their container, an
    # equal chain lets the widget skip, a changed one recomposes it, a click reaches the plugin.
    wait_logcat "RdmaRuntime" "MOD_APPLIED: column=\\[padding(4.0) background testTag\\] stable=\\[weight(1.0)@Row clickable testTag\\] noscope=\\[weight(ignored) testTag\\] button=\\[padding(2.0) testTag\\]"
    wait_logcat "RdmaRuntime" "MOD_SKIP: stable=+0 colstable=+0 changing=+3"
    wait_logcat "RdmaRuntime" "MOD_CLICK: clicked=true clicks=1"
    # Object keys: when the parent recomposes, the same plugin object, an equal kernel object
    # and the same content lambda count as unchanged (no rerun, no recompute, no restart).
    wait_logcat "RdmaRuntime" "KEYS: body=+3 itemRow=+0 rememberItem=+0 rememberKernel=+0 effect=+0 row=+0"
}

main() {
    adb_dev
    mkdir -p "$VERSIONS_DIR"

    step_0_static_release
    step_1_build_and_launch_app
    step_2_mutation
    step_3_nesting
    step_4_cross_runtime
    step_5_iteration
    step_6_collection_idioms

    echo
    echo "ALL LIST-BEHAVIOR STEPS PASSED"
}

main "$@"
