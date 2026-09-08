#!/usr/bin/env bash
set -euo pipefail

# One-command demo build: sets up Hermes (downloads/builds/publishes it and copies
# the JSI headers) and then assembles the demo APK.
#
# Any extra arguments are forwarded to Gradle, e.g.:
#   ./scripts/build.sh --offline
#   ./scripts/build.sh :androidApp:assembleDebug --rerun-tasks

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/setup-hermes.sh"

cd "$ROOT_DIR"
./gradlew :androidApp:assembleDebug "$@"
