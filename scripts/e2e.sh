#!/usr/bin/env bash
set -euo pipefail

# Runs the full local test suite: fast JVM unit tests, then the Android and iOS
# end-to-end List-behavior tests. Requires a booted Android emulator and an
# available iOS simulator.
#
#   ./scripts/e2e.sh

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/test.sh"

echo
"$ROOT_DIR/tests/rdma-integration-tests/e2e/list-behavior.sh"

echo
"$ROOT_DIR/tests/rdma-integration-tests/e2e/ios-list-behavior.sh"

echo
echo "All tests passed."
