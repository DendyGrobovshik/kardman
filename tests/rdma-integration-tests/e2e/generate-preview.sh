#!/usr/bin/env bash
set -euo pipefail

# End-to-end test for the demo's "generate → preview" flow. Runs the demo dev
# server, builds + launches the demo app on a booted emulator, taps Generate and
# verifies the store-delivered plugin renders in the preview without an app restart.
#
#   ./tests/rdma-integration-tests/e2e/generate-preview.sh
#
# Requirements: a booted emulator (adb devices), and the framework published to
# mavenLocal (done in step 1 via scripts/demo.sh).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DEMO_DIR="$ROOT_DIR/demo"
VERSIONS_DIR="$DEMO_DIR/versions"
GENERATED_SRC="$DEMO_DIR/plugin/generated/src/kotlin/com/example/plugin/generated/Generated.kt"
STORE_URL="http://localhost:8080"
STORE_PORT=8080
APP_ID="org.example"
SERVER_PID=""

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

adb_dev() { adb devices | grep -q 'emulator-.*\sdevice' || fail "no emulator attached"; }

tap_text() { # tap_text <text>
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    local coords
    coords="$(adb shell cat /sdcard/ui.xml | python3 -c 'import sys,re
xml=sys.stdin.read()
n=sys.argv[1]
for node in re.finditer(r"<node[^>]*>", xml):
    s=node.group(0)
    if ("text=\""+n+"\"") in s:
        b=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", s)
        if b:
            x1,y1,x2,y2=map(int,b.groups()); print((x1+x2)//2,(y1+y2)//2); break
' "$1")"
    [ -n "$coords" ] || fail "text '$1' not found on screen"
    adb shell input tap $coords
}

assert_text() { # assert_text <text>  → polls until the text appears (up to ~30s)
    local tries=0
    while [ $tries -lt 30 ]; do
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
        if adb shell cat /sdcard/ui.xml | python3 -c 'import sys
xml=sys.stdin.read()
n=sys.argv[1]
sys.exit(0 if ("text=\""+n+"\"") in xml else 1)' "$1"; then return 0; fi
        sleep 1; tries=$((tries+1))
    done
    fail "text '$1' not rendered"
}

server_start() {
    (cd "$ROOT_DIR" && source scripts/android-sdk.sh && ensure_android_sdk "$ROOT_DIR" \
        && DEMO_DIR="$DEMO_DIR" VERSIONS_DIR="$VERSIONS_DIR" PORT=$STORE_PORT \
           HERMESC="$ROOT_DIR/third_party/bin/hermesc" \
           "$ROOT_DIR/gradlew" -p "$DEMO_DIR" :server:run --no-daemon --console=plain \
        > "$DEMO_DIR/build/server.log" 2>&1) &
    SERVER_PID=$!
    local tries=0
    while [ $tries -lt 120 ]; do
        curl -fsS "http://localhost:$STORE_PORT/health" >/dev/null 2>&1 && return 0
        sleep 0.5; tries=$((tries+1))
    done
    fail "server did not start (see $DEMO_DIR/build/server.log)"
}

server_stop() {
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
    pkill -f "org.example.server.DevServerKt" 2>/dev/null || true
}

cleanup() {
    server_stop
    # Restore the scratch plugin source the server rewrites.
    git -C "$ROOT_DIR" checkout -- "$GENERATED_SRC" 2>/dev/null || true
    rm -rf "$VERSIONS_DIR"
}
trap cleanup EXIT

main() {
    adb_dev

    # 1) Build the app + static-release the kernel (also republishes the framework).
    step "build demo (publish + static release + assemble)"
    rm -rf "$VERSIONS_DIR" "$DEMO_DIR/build/polyfill"
    "$ROOT_DIR/scripts/demo.sh"

    # 2) Install + launch the app.
    step "install + launch app"
    adb install -r "$DEMO_DIR/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    adb shell am force-stop "$APP_ID"
    adb logcat -c
    adb shell am start -n "$APP_ID/.MainActivity" >/dev/null
    sleep 6

    # 2b) Dismiss the intro overlay (tap anywhere).
    adb shell input tap 540 1000
    sleep 1

    # 3) Start the demo dev server.
    step "start demo dev server"
    server_start

    # 4) Tap Generate (the default editor content is the Counter example). On
    #    success the shell auto-switches to the Preview tab.
    step "generate + verify preview"
    tap_text "Generate"
    assert_text "Count: 0"
    assert_text "increment"

    # 5) The generated plugin is interactive (Compose state round-trips via the
    #    native navigation bar's decrement/increment buttons).
    tap_text "increment"
    assert_text "Count: 1"
    tap_text "decrement"
    assert_text "Count: 0"

    echo
    echo "ALL STEPS PASSED"
}

main "$@"
