#!/usr/bin/env bash
set -euo pipefail

# Downloads and builds Hermes for Android, publishes the `hermes-android` AAR to
# mavenLocal, installs the matching host `hermesc` compiler into `tools/`, and
# refreshes the JSI headers used by the runtime.
#
# IMPORTANT: Hermes is pinned to a specific commit. The `static_h` branch is a
# moving target (it was switched to the React Native Hermes build in 2025 and the
# bytecode version / JSI API keep changing), so cloning HEAD is not reproducible.
# The commit below is known-good for this framework: its bytecode version matches
# the `tools/hermesc` that this script installs, and its JSI API matches the
# headers checked into `rdma-runtime/src/main/cpp/include/jsi`.
#
# Override any of the following via environment variables:
#   HERMES_VERSION   AAR version to publish (default: 0.76.9)
#   HERMES_REPO      Git URL to clone from (default: https://github.com/facebook/hermes.git)
#   HERMES_BRANCH    Branch to fetch from (default: static_h)
#   HERMES_COMMIT    Exact commit to check out (default: the known-good pin)
#   HERMES_SRC_DIR   Where to clone Hermes into (default: .hermes-src; re-used on re-runs)
#
# Requirements: git, JDK 17, ANDROID_HOME (or ANDROID_SDK_ROOT) with the NDK,
# cmake + ninja (for the host hermesc). The build is heavy: it compiles libhermes
# for every Android ABI.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

HERMES_VERSION="${HERMES_VERSION:-0.76.9}"
HERMES_REPO="${HERMES_REPO:-https://github.com/facebook/hermes.git}"
HERMES_BRANCH="${HERMES_BRANCH:-static_h}"
# Known-good pin: bytecode v99, JSI `IRuntime` API (matches the committed headers).
HERMES_COMMIT="${HERMES_COMMIT:-a1154cb46a9cfe03a96fcdae96ab5e5cf5125c66}"
HERMES_SRC_DIR="${HERMES_SRC_DIR:-$ROOT_DIR/.hermes-src}"

if [[ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
    echo "error: ANDROID_HOME or ANDROID_SDK_ROOT must be set" >&2
    exit 1
fi

# The Hermes Android build's Gradle wrapper does not run on JDK 24/25; force JDK 17.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [[ -z "$JAVA_HOME" || ! -d "$JAVA_HOME" ]]; then
    echo "error: JDK 17 is required (set JAVA_HOME)" >&2
    exit 1
fi
# Required by the Hermes Android build (build-logic `hermesUtils` plugin).
export HERMES_WS_DIR="$HERMES_SRC_DIR"

# 1) Obtain the Hermes source tree at the pinned commit.
if [[ ! -d "$HERMES_SRC_DIR/.git" ]]; then
    echo "Cloning $HERMES_REPO into $HERMES_SRC_DIR ..."
    git clone --filter=blob:none "$HERMES_REPO" "$HERMES_SRC_DIR"
else
    echo "Using existing Hermes checkout at $HERMES_SRC_DIR"
fi
echo "Checking out pinned Hermes commit $HERMES_COMMIT ..."
git -C "$HERMES_SRC_DIR" fetch --depth 1 origin "$HERMES_COMMIT"
git -C "$HERMES_SRC_DIR" checkout "$HERMES_COMMIT"

# 2) Patch the Hermes Android build to link JSI statically into libhermesvm.so.
#    The upstream build compiles JSI as a separate libjsi.so and then EXCLUDES it
#    from the AAR, which leaves `hermes-engine::hermesvm` without the JSI symbols
#    our runtime uses. Static linking restores them.
JSI_FLAG='-DHERMES_BUILD_SHARED_JSI='
if grep -q "${JSI_FLAG}True" "$HERMES_SRC_DIR/android/build.gradle.kts"; then
    echo "Patching HERMES_BUILD_SHARED_JSI=False ..."
    sed -i '' "s/${JSI_FLAG}True/${JSI_FLAG}False/" "$HERMES_SRC_DIR/android/build.gradle.kts"
fi

# 3) Build + publish the `hermes-android` AAR to mavenLocal.
#    The AAR exposes the `hermes-engine` prefab (`hermesvm` module) that the
#    runtime links against via `find_package(hermes-engine)`.
#    The `:ios-artifacts` subproject publishes iOS framework artifacts that are
#    only produced by setup-hermes-ios.sh, so it is excluded here.
echo "Building and publishing hermes-android:$HERMES_VERSION to mavenLocal ..."
(
    cd "$HERMES_SRC_DIR/android"
    ./gradlew publishToMavenLocal -PVERSION_NAME="$HERMES_VERSION" \
        -x :ios-artifacts:publishToMavenLocal
)

# 4) Build + install the host `hermesc` compiler into tools/. Its bytecode version
#    must match the runtime built above, otherwise the plugin `.hbc` bundles will
#    not load. (Never rely on a stale committed binary.)
echo "Building host hermesc ..."
if [[ ! -f "$HERMES_SRC_DIR/build_host_hermesc/bin/hermesc" ]]; then
    (cd "$HERMES_SRC_DIR" && cmake -S . -B build_host_hermesc -DCMAKE_BUILD_TYPE=Release)
    (cd "$HERMES_SRC_DIR" && cmake --build ./build_host_hermesc --target hermesc)
fi
mkdir -p "$ROOT_DIR/tools"
cp "$HERMES_SRC_DIR/build_host_hermesc/bin/hermesc" "$ROOT_DIR/tools/hermesc"
echo "Installed tools/hermesc: $("$ROOT_DIR/tools/hermesc" --version 2>&1 | grep -i 'bytecode version' || true)"

# 5) Sanity-check: the JSI headers checked into the runtime must match the pinned
#    commit (the prefab does not ship them, and the C++ runtime includes them
#    directly from `rdma-runtime/src/main/cpp/include/jsi`). Bail out early with a
#    clear message if the pin has drifted from those headers.
JSI_SRC="$HERMES_SRC_DIR/API/jsi/jsi"
JSI_DST="$ROOT_DIR/rdma-runtime/src/main/cpp/include/jsi"
if ! diff -q "$JSI_SRC/jsi.h" <(tail -n +16 "$JSI_DST/jsi.h") >/dev/null 2>&1; then
    echo "error: committed JSI headers ($JSI_DST) do not match Hermes commit $HERMES_COMMIT." >&2
    echo "       Update HERMES_COMMIT or refresh the headers manually." >&2
    exit 1
fi

echo "Hermes setup complete."
