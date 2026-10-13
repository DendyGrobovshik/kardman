#!/usr/bin/env bash
set -euo pipefail

# Runs the fast (JVM) unit tests for the framework. No emulator is required.
#
# The full end-to-end plugin lifecycle test additionally needs a booted Android
# emulator + adb; run it directly when available:
#   tests/rdma-integration-tests/e2e/plugin-lifecycle.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

./gradlew -p "$ROOT_DIR" \
    :tests:rdma-tests:test \
    :rdma-store:test \
    :rdma-kernel-compiler-plugin:test \
    :rdma-plugin-compiler-plugin:test

echo "Unit tests passed."
