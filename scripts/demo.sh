#!/usr/bin/env bash
set -euo pipefail

# Builds the standalone demo (`demo/`). The demo consumes the published framework
# from mavenLocal and keeps its own kernel/plugin modules. Because a plugin can
# only be built against a *released* kernel (the plugin build reads the changelog's
# `h`), this script first static-releases the demo kernel into `demo/versions`,
# then assembles the app.
#
# Requirements: `scripts/setup.sh` (Kotlin fork + Hermes) has run once, and the
# Android SDK is available (ANDROID_HOME or `demo/local.properties`).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSIONS_DIR="$ROOT_DIR/demo/versions"
POLYFILL_OUT="$ROOT_DIR/demo/build/polyfill"

# 1) Publish the framework to mavenLocal (idempotent).
"$ROOT_DIR/scripts/publish.sh"

# 2) Compile the demo kernel modules to emit their rdma_analysis.json.
./gradlew -p "$ROOT_DIR/demo" --console=plain \
    :kernel:internal:compileAndroidMain \
    :kernel:user:alice:compileAndroidMain

# 3) Static-release the demo kernel into demo/versions (starting from empty).
"$ROOT_DIR/scripts/release.sh" static-release \
    "$ROOT_DIR/demo/kernel/internal/build/generated/rdma/rdma_analysis.json" \
    "$VERSIONS_DIR" \
    "$POLYFILL_OUT" \
    "internal" \
    ""
"$ROOT_DIR/scripts/release.sh" static-release \
    "$ROOT_DIR/demo/kernel/user/alice/build/generated/rdma/rdma_analysis.json" \
    "$VERSIONS_DIR" \
    "$POLYFILL_OUT" \
    "internal" \
    ""

# 4) Assemble the demo app (bakes the kernel + the `alice:counter` plugin).
./gradlew -p "$ROOT_DIR/demo" --console=plain :androidApp:assembleDebug

echo "Demo APK: $ROOT_DIR/demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
