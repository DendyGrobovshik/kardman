#!/usr/bin/env bash
set -euo pipefail

# Builds the standalone demo (`demo/`). The demo consumes the published framework
# from mavenLocal and keeps its own kernel/plugin modules. Because a plugin can
# only be built against a *released* kernel (the plugin build reads the changelog's
# `h`), this script first static-releases the demo kernel into `demo/versions`,
# then assembles the app.
#
# Requirements: `scripts/setup.sh` (Kotlin fork + Hermes) has run once. The
# Android SDK is detected automatically (see scripts/android-sdk.sh).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The demo app is Android, so the Gradle build needs the SDK location.
source "$ROOT_DIR/scripts/android-sdk.sh"
ensure_android_sdk "$ROOT_DIR" || exit 1

VERSIONS_DIR="$ROOT_DIR/demo/versions"
POLYFILL_OUT="$ROOT_DIR/demo/build/polyfill"

# 1) Publish the framework to mavenLocal (idempotent).
"$ROOT_DIR/scripts/publish.sh"

# 2) Compile the demo kernel module to emit its rdma_analysis.json.
./gradlew -p "$ROOT_DIR/demo" --console=plain \
    :kernel:internal:compileAndroidMain

# 3) Static-release the demo kernel into demo/versions (starting from empty).
"$ROOT_DIR/scripts/release.sh" static-release \
    "$ROOT_DIR/demo/kernel/internal/build/generated/rdma/rdma_analysis.json" \
    "$VERSIONS_DIR" \
    "$POLYFILL_OUT" \
    "internal" \
    ""

# 3b) Record a toolchain generation so `plugin-release` has a live generation to
# fan out to (the store serves dynamic plugins against `g1`).
./gradlew -p "$ROOT_DIR" --console=plain \
    :rdma-kernel-compiler-plugin:generationRelease \
    -Pgeneration=g1 -PversionsDir="$VERSIONS_DIR" -PrdmaPolicyFile="$ROOT_DIR/demo/rdma.toml"

# 4) Assemble the demo app (bakes the kernel; dynamic plugins are store-delivered).
./gradlew -p "$ROOT_DIR/demo" --console=plain :androidApp:assembleDebug

echo "Demo APK: $ROOT_DIR/demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
