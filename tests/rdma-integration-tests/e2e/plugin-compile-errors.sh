#!/usr/bin/env bash
set -euo pipefail

# Compile-time rejections of the plugin's JVM resolve pass (docs/design/runtime_rework.md
# §5.5): constructs that cannot hold across the boundary must fail the plugin build
# with a clear message instead of misbehaving at run time. No device needed.
#
#   ./tests/rdma-integration-tests/e2e/plugin-compile-errors.sh
#
# Each case drops one probe file into the bob:services plugin, compiles it and expects
# the error; the probe is always removed afterwards.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
GRADLE="$ROOT_DIR/gradlew"

IT_DIR="$(mktemp -d)"
VERSIONS_DIR="$IT_DIR/versions"
PROBE="$ROOT_DIR/tests/stand/plugin/bob/services/src/kotlin/com/example/plugin/bob/CompileErrorProbe.kt"
PLUGIN=":tests:stand:plugin:bob:services"

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo; echo "==== $* ===="; }

run_gradle() { "$GRADLE" -p "$ROOT_DIR" --console=plain -PrdmaVersionsDir="$VERSIONS_DIR" "$@"; }

release() { # release <analysisJson> <polyfillOut> <internalModules>
    "$ROOT_DIR/scripts/release.sh" static-release "$1" "$VERSIONS_DIR" "$2" "$3" ""
}

cleanup() {
    rm -f "$PROBE"
    rm -rf "$IT_DIR"
}
trap cleanup EXIT

# 0) Static-release the kernel so the plugin build can read the changelog.
step_0_static_release() {
    step "0: static release kernel"
    run_gradle ":rdma-kernel-compiler-plugin:generationRelease" \
        -Pgeneration="g1" -PversionsDir="$VERSIONS_DIR" -Palias="legacy-single-generation" \
        -PrdmaPolicyFile="$ROOT_DIR/rdma.toml"
    run_gradle :tests:stand:kernel:internal:compileAndroidMain
    release "$ROOT_DIR/tests/stand/kernel/internal/build/generated/rdma/rdma_analysis.json" "$IT_DIR/polyfill-internal" "internal"
}

# expect_compile_error <name> <expected message fragment> <probe body>
expect_compile_error() {
    local name="$1" needle="$2" body="$3"
    step "$name"
    cat > "$PROBE" <<EOF
package com.example.plugin.bob

import com.example.kernel.internal.services.HttpMethod
import com.example.kernel.internal.services.PlatformInfo
import com.example.kernel.internal.services.platformInfo

$body
EOF
    local out="$IT_DIR/$name.log"
    if run_gradle "$PLUGIN:compileAndroidMain" >"$out" 2>&1; then
        fail "$name: the plugin compiled, expected an error"
    fi
    grep -q "CompileErrorProbe.kt.*$needle" "$out" || {
        grep -E "^e: " "$out" | head -5 >&2
        fail "$name: missing error '$needle'"
    }
    rm -f "$PROBE"
}

IS_AS="is/as against a kernel type is not supported"

main() {
    step_0_static_release

    expect_compile_error "is against a kernel class" "$IS_AS" \
        'fun probe(x: Any): Boolean = x is PlatformInfo'
    expect_compile_error "as against a kernel class" "$IS_AS" \
        'fun probe(x: Any): PlatformInfo = x as PlatformInfo'
    expect_compile_error "as? against a kernel enum" "$IS_AS" \
        'fun probe(x: Any): HttpMethod? = x as? HttpMethod'
    expect_compile_error "is in when against a kernel class" "$IS_AS" \
        'fun probe(x: Any): Int = when (x) { is PlatformInfo -> 1; else -> 0 }'
    expect_compile_error "reified filterIsInstance against a kernel class" "$IS_AS" \
        'fun probe(): Int = listOf<Any>(platformInfo()).filterIsInstance<PlatformInfo>().size'
    expect_compile_error "Enum.entries" "Enum.entries is not supported" \
        'fun probe(): Int = HttpMethod.entries.size'
    expect_compile_error "Enum.values()" "Enum.values() is not supported" \
        'fun probe(): Int = HttpMethod.values().size'

    expect_compile_error "@RDMA in a plugin" "@RDMA is not allowed in a plugin" \
        '@io.github.dendygrobovshik.kardman.RDMA
class PluginOwn(val n: Int)'

    expect_compile_error "derivedStateOf with a policy" "derivedStateOf without a mutation policy only" \
        'fun probe() = androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.referentialEqualityPolicy()) { 1 }'
    expect_compile_error "derivedStateOf of a plugin class" "derivedStateOf requires a concrete allowed result type" \
        'class Own(val n: Int)
fun probe() = androidx.compose.runtime.derivedStateOf { Own(1) }'

    step "control: the plugin compiles without a probe"
    run_gradle "$PLUGIN:compileAndroidMain" >"$IT_DIR/control.log" 2>&1 || {
        grep -E "^e: " "$IT_DIR/control.log" | head -5 >&2
        fail "the unmodified plugin does not compile"
    }

    echo
    echo "ALL PLUGIN-COMPILE-ERROR STEPS PASSED"
}

main "$@"
