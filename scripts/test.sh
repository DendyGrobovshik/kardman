#!/usr/bin/env bash
set -euo pipefail

# Runs the fast (JVM) unit tests for the framework. No emulator is required.
#
# The full end-to-end plugin lifecycle test additionally needs a booted Android
# emulator + adb; run it directly when available:
#   tests/rdma-integration-tests/e2e/plugin-lifecycle.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The root build includes the Android modules, so Gradle needs the SDK location.
source "$ROOT_DIR/scripts/android-sdk.sh"
ensure_android_sdk "$ROOT_DIR" || exit 1

./gradlew -p "$ROOT_DIR" \
    :tests:rdma-tests:test \
    :rdma-store:test \
    :rdma-kernel-compiler-plugin:test \
    :rdma-plugin-compiler-plugin:test

echo "Unit tests passed."
