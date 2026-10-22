#!/usr/bin/env bash
set -euo pipefail

# Builds and publishes the custom Kotlin compiler fork to mavenLocal. The fork is
# pinned via the `third_party/kotlin` git submodule, but this script clones it
# directly (see below). The framework is built with this fork (`-Xir-export-all`
# etc.), and it is not on Maven Central, so a fresh machine must build and publish
# it before any Gradle build of kardman works.
#
# The compiler version is the single source of truth in `gradle/libs.versions.toml`
# (key `kotlin`); this script derives `deployVersion`/`versions.kotlin-native`
# from it so a version bump is a one-line change there, not here.
#
# Override any of the following via environment variables:
#   KOTLIN_DIR             Checkout (default: <repo>/third_party/kotlin)
#   KOTLIN_URL             Git URL to clone from (default: the fork on GitHub)
#   KOTLIN_BRANCH          Branch to clone (default: full_stable_js_export_table-2.3.21)
#   KOTLIN_VERSION         deployVersion (default: read from gradle/libs.versions.toml)
#   KOTLIN_NATIVE_VERSION  versions.kotlin-native (default: KOTLIN_VERSION without -rdma)
#   BUILD_NUMBER           build.number (default: 1)
#
# Requirements: git, JDK 17. Heavy the first time (compiles the compiler + stdlib);
# cached afterwards.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

KOTLIN_DIR="${KOTLIN_DIR:-$ROOT_DIR/third_party/kotlin}"
KOTLIN_URL="${KOTLIN_URL:-https://github.com/DendyGrobovshik/kotlin.git}"
KOTLIN_BRANCH="${KOTLIN_BRANCH:-full_stable_js_export_table-2.3.21}"
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

# The fork is tracked as a git submodule, but its own build scripts
# (`libraries/tools/kotlin-prepush-hook/build.gradle.kts`) read `.git` as a real
# directory and fail on a submodule's `gitdir:` pointer. Clone it directly so
# `.git` is a directory; pin the commit recorded in the submodule gitlink.
if [[ ! -d "$KOTLIN_DIR/.git" ]]; then
    rm -rf "$KOTLIN_DIR"
    PINNED_COMMIT="$(git -C "$ROOT_DIR" ls-tree HEAD third_party/kotlin | awk '{print $3}')"
    echo "Cloning Kotlin fork ($KOTLIN_URL, branch $KOTLIN_BRANCH) into $KOTLIN_DIR ..."
    git clone --depth 1 --branch "$KOTLIN_BRANCH" --single-branch "$KOTLIN_URL" "$KOTLIN_DIR"
    if [[ -n "${PINNED_COMMIT:-}" ]]; then
        git -C "$KOTLIN_DIR" fetch --depth 1 origin "$PINNED_COMMIT" 2>/dev/null || true
        git -C "$KOTLIN_DIR" checkout "$PINNED_COMMIT"
    fi
fi

if [[ ! -f "$KOTLIN_DIR/gradlew" ]]; then
    echo "error: Kotlin source not found at $KOTLIN_DIR" >&2
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
