#!/usr/bin/env bash
set -euo pipefail

# One-command initial setup for a fresh machine. Runs the heavy, one-time steps
# in order:
#   1. Kotlin compiler fork  -> scripts/setup/kotlin.sh   (builds + publishes to mavenLocal)
#   2. Hermes (Android)      -> scripts/setup/hermes.sh   (AAR + host hermesc + JSI headers)
#   3. Hermes (iOS)          -> scripts/setup/hermes-ios.sh (xcframework; skipped on non-macOS)
#   4. Framework publish     -> scripts/publish.sh        (publishes framework modules to mavenLocal)
#
# After this, the demo is built with the usual Gradle command:
#   ./gradlew :androidApp:assembleDebug
#
# Each step is idempotent (Gradle/CMake caches are reused on re-runs).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/setup/kotlin.sh"
"$ROOT_DIR/scripts/setup/hermes.sh"
"$ROOT_DIR/scripts/setup/hermes-ios.sh"
"$ROOT_DIR/scripts/publish.sh"

echo "Setup complete. Build the demo with: ./gradlew :androidApp:assembleDebug"
