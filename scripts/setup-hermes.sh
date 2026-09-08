#!/usr/bin/env bash
set -euo pipefail

# Downloads and builds Hermes for Android, publishes the `hermes-android` AAR to
# mavenLocal, and copies the JSI headers into the runtime module.
#
# Override any of the following via environment variables:
#   HERMES_VERSION   AAR version to publish (default: 0.76.9)
#   HERMES_REPO      Git URL to clone from (default: https://github.com/facebook/hermes.git)
#   HERMES_BRANCH    Branch/tag to check out (default: static_h)
#   HERMES_SRC_DIR   Where to clone Hermes into (default: .hermes-src; re-used on re-runs)
#
# Requirements: git, ANDROID_HOME (or ANDROID_SDK_ROOT) with the NDK installed.
# The build is heavy: it compiles libhermes for every Android ABI.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

HERMES_VERSION="${HERMES_VERSION:-0.76.9}"
HERMES_REPO="${HERMES_REPO:-https://github.com/facebook/hermes.git}"
HERMES_BRANCH="${HERMES_BRANCH:-static_h}"
HERMES_SRC_DIR="${HERMES_SRC_DIR:-$ROOT_DIR/.hermes-src}"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
    echo "error: ANDROID_HOME or ANDROID_SDK_ROOT must be set" >&2
    exit 1
fi

# 1) Obtain the Hermes source tree.
if [[ ! -d "$HERMES_SRC_DIR/.git" ]]; then
    echo "Cloning $HERMES_REPO (branch $HERMES_BRANCH) into $HERMES_SRC_DIR ..."
    git clone --depth 1 --branch "$HERMES_BRANCH" "$HERMES_REPO" "$HERMES_SRC_DIR"
else
    echo "Using existing Hermes checkout at $HERMES_SRC_DIR"
fi

# 2) Build + publish the `hermes-android` AAR to mavenLocal.
#    The AAR exposes the `hermes-engine` prefab (`hermesvm` module) that the
#    runtime links against via `find_package(hermes-engine)`.
echo "Building and publishing hermes-android:$HERMES_VERSION to mavenLocal ..."
(
    cd "$HERMES_SRC_DIR/android"
    ./gradlew publishToMavenLocal -PVERSION_NAME="$HERMES_VERSION"
)

# 3) Copy the JSI headers (not shipped in the AAR prefab) into the runtime module.
JSI_SRC="$HERMES_SRC_DIR/API/jsi/jsi"
JSI_DST="$ROOT_DIR/rdma-runtime-android/src/main/cpp/include/jsi"

echo "Copying JSI headers from $JSI_SRC to $JSI_DST ..."
mkdir -p "$JSI_DST"
for header in \
    decorator.h \
    hermes-interfaces.h \
    instrumentation.h \
    jsi-inl.h \
    jsi.h \
    jsilib.h \
    threadsafe.h; do
    cp "$JSI_SRC/$header" "$JSI_DST/$header"
done

echo "Hermes setup complete."
