#!/usr/bin/env bash
set -euo pipefail

# Builds and publishes the custom Kotlin compiler fork to mavenLocal, from the
# `third_party/kotlin` git submodule. The framework is built with this fork
# (`-Xir-export-all` etc.), and it is not on Maven Central, so a fresh machine
# must build and publish it before any Gradle build of kardman works.
#
# The compiler version is the single source of truth in `gradle/libs.versions.toml`
# (key `kotlin`); this script derives `deployVersion`/`versions.kotlin-native`
# from it so a version bump is a one-line change there, not here.
#
# Override any of the following via environment variables:
#   KOTLIN_DIR             Submodule checkout (default: <repo>/third_party/kotlin)
#   KOTLIN_VERSION         deployVersion (default: read from gradle/libs.versions.toml)
#   KOTLIN_NATIVE_VERSION  versions.kotlin-native (default: KOTLIN_VERSION without -rdma)
#   BUILD_NUMBER           build.number (default: 1)
#
# Requirements: git, JDK 17. Heavy the first time (compiles the compiler + stdlib);
# cached afterwards.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

KOTLIN_DIR="${KOTLIN_DIR:-$ROOT_DIR/third_party/kotlin}"
KOTLIN_VERSION="${KOTLIN_VERSION:-$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "$ROOT_DIR/gradle/libs.versions.toml" | head -1)}"
KOTLIN_NATIVE_VERSION="${KOTLIN_NATIVE_VERSION:-${KOTLIN_VERSION%-rdma}}"
BUILD_NUMBER="${BUILD_NUMBER:-1}"

if [[ -z "$KOTLIN_VERSION" ]]; then
    echo "error: could not read the Kotlin version from gradle/libs.versions.toml" >&2
    exit 1
fi

# The Kotlin build's Gradle wrapper does not run on recent JDKs; force JDK 17.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
if [[ -z "$JAVA_HOME" || ! -d "$JAVA_HOME" ]]; then
    echo "error: JDK 17 is required (set JAVA_HOME)" >&2
    exit 1
fi

# Fresh clones leave the submodule directory empty; initialize it.
git -C "$ROOT_DIR" submodule update --init --recursive third_party/kotlin

if [[ ! -f "$KOTLIN_DIR/gradlew" ]]; then
    echo "error: Kotlin submodule not found at $KOTLIN_DIR" >&2
    echo "       Run: git submodule update --init --recursive" >&2
    exit 1
fi

echo "Building and publishing the Kotlin compiler ($KOTLIN_VERSION) to mavenLocal ..."
(
    cd "$KOTLIN_DIR"
    ./gradlew install \
        -PdeployVersion="$KOTLIN_VERSION" \
        -Pbuild.number="$BUILD_NUMBER" \
        -Pversions.kotlin-native="$KOTLIN_NATIVE_VERSION"
)

# Sanity check: the published Gradle plugin artifact for the exact version must exist.
ARTIFACT="$HOME/.m2/repository/org/jetbrains/kotlin/kotlin-gradle-plugin/$KOTLIN_VERSION/kotlin-gradle-plugin-$KOTLIN_VERSION.pom"
if [[ ! -f "$ARTIFACT" ]]; then
    echo "error: expected $ARTIFACT but it was not published" >&2
    exit 1
fi

echo "Kotlin compiler $KOTLIN_VERSION published to mavenLocal."
