#!/usr/bin/env bash
#
# End-to-end test of the release contract gate (compatibility.md §10): the kernel's commonTest
# runs on the emulator against the native code and against each prepared F polyfill.
#   1. a hotfix that keeps the tests green (function and test changed together) is released;
#   2. a hotfix whose JS polyfill behaves differently from the native code (a whole Double
#      prints as "2" in Kotlin/JS, "2.0" on the JVM) is rejected, and nothing is written.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"
IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"
SRC="$ROOT_DIR/tests/stand/kernel/internal/src/commonMain/kotlin/com/example/kernel/internal/services/ContractDemo.kt"
TEST="$ROOT_DIR/tests/stand/kernel/internal/src/commonTest/kotlin/com/example/kernel/internal/services/ContractDemoTest.kt"
INTERNAL_MANIFEST="$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/kernel_manifest_android.json"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }
run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

cp "$SRC" "$IT_DIR/ContractDemo.kt.orig"
cp "$TEST" "$IT_DIR/ContractDemoTest.kt.orig"
cleanup() {
    cp "$IT_DIR/ContractDemo.kt.orig" "$SRC"
    cp "$IT_DIR/ContractDemoTest.kt.orig" "$TEST"
}
trap cleanup EXIT

adb get-state >/dev/null 2>&1 || fail "no emulator attached"
mkdir -p "$VERSIONS_DIR"

# Bundles are signed with the e2e test key; the stand app trusts its public key (rdma.toml).
RDMA_SIGNING_KEY="$(grep -v '^#' "$ROOT_DIR/tests/rdma-integration-tests/signing/test-signing-key.txt")"
export RDMA_SIGNING_KEY
export RDMA_CONTRACT_GRADLE_ARGS="-PrdmaVersionsDir=$VERSIONS_DIR"

release() { # release <command> <analysisJson> <workDir>
    "$ROOT_DIR/scripts/release.sh" "$1" "$2" "$VERSIONS_DIR" "$3" "internal" ""
}
version() { python3 -c "import json;print(max(e['version'] for e in json.load(open('$VERSIONS_DIR/changelog.json'))['entries']))"; }
set_label() { # set_label <function body expression> <test assertion>
    python3 - "$SRC" "$TEST" "$1" "$2" <<'PY'
import re, sys
src, test, body, assertion = sys.argv[1:5]
s = open(src).read()
s = re.sub(r'fun contractLabel\(x: Double\): String = .*', 'fun contractLabel(x: Double): String = ' + body, s)
open(src, "w").write(s)
t = open(test).read()
t = re.sub(r'assertEquals\(.*\)', assertion, t)
open(test, "w").write(t)
PY
}

step "0: static release (the app's host)"
unset RDMA_CONTRACT_APP
run_gradle ":rdma-kernel-compiler-plugin:generationRelease" -Pgeneration=g1 -PversionsDir="$VERSIONS_DIR" -Palias=contract
run_gradle :tests:stand:kernel:internal:compileAndroidMain >/dev/null
release "static-release" "$INTERNAL_MANIFEST" "$IT_DIR/work-0"
run_gradle :tests:stand:kernel:user:alice:compileAndroidMain >/dev/null
release "static-release" "$ROOT_DIR/tests/stand/kernel/user/alice/build/generated/rdma/kernel_manifest_android.json" "$IT_DIR/work-0a"

# From here on every release runs the contract tests on the emulator.
export RDMA_CONTRACT_APP=":tests:stand:androidApp"

step "1: a hotfix that keeps the contract is released"
set_label '"v2:$x"' 'assertEquals("v2:1.5", contractLabel(1.5))'
run_gradle :tests:stand:kernel:internal:compileAndroidMain >/dev/null
before=$(version)
release "dynamic-release" "$INTERNAL_MANIFEST" "$IT_DIR/work-1" | tee "$IT_DIR/release-1.log"
grep -q "Contract tests passed." "$IT_DIR/release-1.log" || fail "the contract tests did not run or did not pass"
grep -q "  F hosts .*: OK (" "$IT_DIR/release-1.log" || fail "the polyfill run is missing"
[ "$(version)" -gt "$before" ] || fail "the release was not committed"
python3 -c "import json,sys; assert json.load(open('$VERSIONS_DIR/polyfills.json'))['f']['internal']" || fail "no F published for internal"

step "2: a hotfix whose polyfill breaks the contract is rejected"
set_label '"v3:$x"' 'assertEquals("v3:2.0", contractLabel(2.0))'
run_gradle :tests:stand:kernel:internal:compileAndroidMain >/dev/null
before=$(version)
if release "dynamic-release" "$INTERNAL_MANIFEST" "$IT_DIR/work-2" >"$IT_DIR/release-2.log" 2>&1; then
    tail -30 "$IT_DIR/release-2.log" >&2
    fail "a release whose polyfill fails the contract was accepted"
fi
grep -q "native: OK (" "$IT_DIR/release-2.log" || { tail -30 "$IT_DIR/release-2.log" >&2; fail "the native run should pass"; }
grep -q "Contract tests failed" "$IT_DIR/release-2.log" || { tail -30 "$IT_DIR/release-2.log" >&2; fail "the contract failure was not reported"; }
[ "$(version)" = "$before" ] || fail "a rejected release wrote the changelog"

echo
echo "ALL CONTRACT-TESTS STEPS PASSED"
