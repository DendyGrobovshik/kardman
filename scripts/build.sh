#!/usr/bin/env bash
set -euo pipefail

# One-command demo build:
#   1. sets up Hermes (downloads/builds/publishes it + copies the JSI headers)
#   2. publishes the framework Gradle plugins to mavenLocal (the demo app consumes
#      the `rdma-app` plugin by id from mavenLocal)
#   3. assembles the demo APK
#
# Any extra arguments are forwarded to the final Gradle invocation, e.g.:
#   ./scripts/build.sh --offline
#   ./scripts/build.sh :androidApp:assembleDebug --rerun-tasks

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/setup-hermes.sh"
"$ROOT_DIR/scripts/publish.sh"

cd "$ROOT_DIR"
./gradlew :androidApp:assembleDebug "$@"
