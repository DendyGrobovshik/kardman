#!/usr/bin/env bash
set -euo pipefail

# Builds Hermes for iOS (iphoneos + iphonesimulator) into a universal
# `hermes.xcframework`, mirroring what scripts/setup-hermes.sh does for Android.
#
# Hermes is cloned from a git remote into a local, gitignored checkout and built
# there. Nothing outside the repository is read or written, so a fresh clone of
# kardman can reproduce the framework.
#
# Override any of the following via environment variables:
#   HERMES_REPO             Git URL to clone from (default: facebook/hermes)
#   HERMES_BRANCH           Branch/tag to check out (default: static_h)
#   HERMES_SRC_DIR          Where to clone into (default: <repo>/.hermes-src,
#                           re-used on re-runs)
#   IOS_DEPLOYMENT_TARGET   Minimum iOS version (default: 12.0, from the podspec)
#   BUILD_TYPE              Debug or Release (default: Release)
#
# Requirements: git, cmake, ninja, xcodebuild (Xcode), python3.
# The build is heavy: it compiles the host `hermesc` + `shermes` and the Hermes
# VM for two iOS architectures.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

HERMES_REPO="${HERMES_REPO:-https://github.com/facebook/hermes.git}"
HERMES_BRANCH="${HERMES_BRANCH:-static_h}"
# Must match scripts/setup-hermes.sh (same runtime/bytecode version).
HERMES_COMMIT="${HERMES_COMMIT:-a1154cb46a9cfe03a96fcdae96ab5e5cf5125c66}"
HERMES_SRC_DIR="${HERMES_SRC_DIR:-$ROOT_DIR/.hermes-src}"
IOS_DEPLOYMENT_TARGET="${IOS_DEPLOYMENT_TARGET:-12.0}"
BUILD_TYPE="${BUILD_TYPE:-Release}"

FRAMEWORK_OUT="$ROOT_DIR/.hermes-ios"

for tool in git cmake ninja xcodebuild; do
    command -v "$tool" > /dev/null || { echo "error: $tool is required" >&2; exit 1; }
done

# 0) Obtain the Hermes source tree at the pinned commit.
if [[ ! -d "$HERMES_SRC_DIR/.git" ]]; then
    echo "Cloning $HERMES_REPO into $HERMES_SRC_DIR ..."
    git clone --filter=blob:none "$HERMES_REPO" "$HERMES_SRC_DIR"
else
    echo "Using existing Hermes checkout at $HERMES_SRC_DIR"
fi
echo "Checking out pinned Hermes commit $HERMES_COMMIT ..."
git -C "$HERMES_SRC_DIR" fetch --depth 1 origin "$HERMES_COMMIT"
git -C "$HERMES_SRC_DIR" checkout "$HERMES_COMMIT"

mkdir -p "$FRAMEWORK_OUT"

# 1) Build the host `hermesc` + `shermes` compilers, required via
#    IMPORT_HOST_COMPILERS for the Apple framework builds. The generated
#    ImportHostCompilers.cmake makes the framework build reuse them instead of
#    compiling another compiler.
if [[ ! -f "$HERMES_SRC_DIR/build_host_hermesc/bin/shermes" ]]; then
    echo "Building host hermesc + shermes ..."
    (cd "$HERMES_SRC_DIR" && cmake -S . -B build_host_hermesc -DCMAKE_BUILD_TYPE="$BUILD_TYPE")
    (cd "$HERMES_SRC_DIR" && cmake --build ./build_host_hermesc --target hermesc shermes)
else
    echo "Using existing host hermesc + shermes."
fi

build_framework() {
    local platform="$1"
    local arch="$2"
    local builddir="build_${platform}"

    if [[ -f "$HERMES_SRC_DIR/$builddir/lib/hermesvm.framework/hermesvm" ]]; then
        echo "Skipping ${platform}: framework already built."
        return
    fi

    echo "Building hermesvm.framework for ${platform} (${arch}) ..."
    cmake -S "$HERMES_SRC_DIR" -B "$HERMES_SRC_DIR/$builddir" -G Ninja \
        -DHERMES_APPLE_TARGET_PLATFORM:STRING="$platform" \
        -DCMAKE_OSX_ARCHITECTURES:STRING="$arch" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET:STRING="$IOS_DEPLOYMENT_TARGET" \
        -DHERMES_ENABLE_DEBUGGER:BOOLEAN=true \
        -DHERMES_ENABLE_INTL:BOOLEAN=true \
        -DHERMES_ENABLE_LIBFUZZER:BOOLEAN=false \
        -DHERMES_ENABLE_FUZZILLI:BOOLEAN=false \
        -DHERMES_ENABLE_TEST_SUITE:BOOLEAN=false \
        -DHERMES_ENABLE_NAPI:BOOLEAN=false \
        -DHERMES_BUILD_APPLE_FRAMEWORK:BOOLEAN=true \
        -DCMAKE_CXX_FLAGS="-gdwarf" \
        -DCMAKE_C_FLAGS="-gdwarf" \
        -DHERMES_ENABLE_TOOLS:BOOLEAN=false \
        -DIMPORT_HOST_COMPILERS:PATH="$HERMES_SRC_DIR/build_host_hermesc/ImportHostCompilers.cmake" \
        -DCMAKE_INSTALL_PREFIX:PATH="$HERMES_SRC_DIR/destroot" \
        -DCMAKE_BUILD_TYPE="$BUILD_TYPE"
    cmake --build "$HERMES_SRC_DIR/$builddir" --target install/strip
}

build_framework "iphoneos" "arm64"
build_framework "iphonesimulator" "arm64"

# 2) Stage the framework slices, bundle the public Hermes + JSI headers into
#    each, and assemble a single self-contained hermes.xcframework.
STAGE="$FRAMEWORK_OUT/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"

stage_framework() {
    local platform="$1"
    local src="$HERMES_SRC_DIR/build_${platform}/lib/hermesvm.framework"
    local dst="$STAGE/${platform}/hermesvm.framework"
    mkdir -p "$(dirname "$dst")"
    cp -R "$src" "$dst"
    mkdir -p "$dst/Headers/hermes/Public" "$dst/Headers/jsi"
    cp "$HERMES_SRC_DIR/API/hermes/"*.h "$dst/Headers/hermes/"
    cp "$HERMES_SRC_DIR/public/hermes/Public/"*.h "$dst/Headers/hermes/Public/"
    cp "$HERMES_SRC_DIR/API/jsi/jsi/"*.h "$dst/Headers/jsi/"
}

stage_framework "iphoneos"
stage_framework "iphonesimulator"

echo "Creating hermes.xcframework ..."
rm -rf "$STAGE/hermes.xcframework"
xcodebuild -create-xcframework \
    -framework "$STAGE/iphoneos/hermesvm.framework" \
    -framework "$STAGE/iphonesimulator/hermesvm.framework" \
    -output "$STAGE/hermes.xcframework"

cp -R "$STAGE/hermes.xcframework" "$FRAMEWORK_OUT/"
rm -rf "$STAGE"

echo "Hermes iOS setup complete."
echo "  framework: $FRAMEWORK_OUT/hermes.xcframework"
