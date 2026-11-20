#!/usr/bin/env bash
set -euo pipefail

# Kernel contract tests (compatibility.md §10): the kernel modules' `commonTest`, compiled into
# the app's instrumented tests by the rdma-app plugin, run on a connected emulator first against
# the native code, then once per prepared F polyfill with that polyfill loaded — kernel calls of
# the polyfilled functions then run in JS. Any failure rejects the release.
#
# Usage: scripts/contract-test.sh <app Gradle path> <plan.json>
#   RDMA_CONTRACT_APP_ID       the app's applicationId (default: org.example)
#   RDMA_CONTRACT_GRADLE_ARGS  extra Gradle arguments for building the app (e.g. -PrdmaVersionsDir=…)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${1:?usage: contract-test.sh <app Gradle path> <plan.json>}"
PLAN="${2:?}"
APP_ID="${RDMA_CONTRACT_APP_ID:-org.example}"

adb get-state >/dev/null 2>&1 || { echo "contract tests need a connected Android emulator/device" >&2; exit 1; }

echo "-- installing $APP and its contract tests"
# shellcheck disable=SC2086 # the extra arguments are split on purpose
"$ROOT_DIR/gradlew" -p "$ROOT_DIR" ${RDMA_CONTRACT_GRADLE_ARGS:-} "$APP:installDebug" "$APP:installDebugAndroidTest" --console=plain >/dev/null

RUNNER=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$APP_ID)" | head -1 | sed -E 's/^instrumentation:([^ ]+) .*/\1/')
[ -n "$RUNNER" ] || { echo "no contract test runner installed for $APP_ID" >&2; exit 1; }

run_contract() { # run_contract <label> [devicePolyfillPath]
    local label="$1" polyfill="${2:-}"
    local -a extra=()
    [ -n "$polyfill" ] && extra=(-e rdmaPolyfills "$polyfill")
    local out
    out=$(adb shell am instrument -w ${extra[@]+"${extra[@]}"} "$RUNNER" | tr -d '\r')
    if echo "$out" | grep -q "^OK (" && ! echo "$out" | grep -q "FAILURES!!!"; then
        echo "  $label: $(echo "$out" | grep '^OK (')"
        return 0
    fi
    echo "  $label: FAILED" >&2
    echo "$out" | grep -vE '^INSTRUMENTATION_STATUS(_CODE)?: (class|current|id|numtests|test|stream)=?' | sed 's/^/    /' >&2
    return 1
}

failed=0
echo "-- native"
run_contract "native" || failed=1

while IFS=$'\t' read -r dir hosts; do
    [ -n "$dir" ] || continue
    bundle=$(ls "$dir"/*.hbc 2>/dev/null | head -1 || true)
    if [ -z "$bundle" ]; then
        echo "  F for hosts $hosts: no built bundle, skipped"
        continue
    fi
    name="contract-$(basename "$dir").hbc"
    adb push "$bundle" "/data/local/tmp/$name" >/dev/null
    adb shell "cat /data/local/tmp/$name | run-as $APP_ID sh -c 'mkdir -p files/rdma-contract && cat > files/rdma-contract/$name'"
    adb shell rm -f "/data/local/tmp/$name"
    echo "-- with F for hosts $hosts"
    run_contract "F hosts $hosts" "/data/user/0/$APP_ID/files/rdma-contract/$name" || failed=1
done < <(python3 -c '
import json, sys
for f in json.load(open(sys.argv[1])).get("f", []):
    print(f["dir"] + "\t" + ",".join(str(h) for h in f["hosts"]))' "$PLAN")

if [ "$failed" != 0 ]; then
    echo "Contract tests failed: the release is rejected and nothing was written." >&2
    exit 1
fi
echo "Contract tests passed."
