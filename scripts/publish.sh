#!/usr/bin/env bash
set -euo pipefail

# Publishes all framework modules to mavenLocal so that an external project (or
# the in-repo demo) can consume the framework as
# `io.github.dendygrobovshik.kardman:*:1.0`.
#
# Uses a dedicated settings file that includes only the framework modules, so the
# demo app (which consumes the published `rdma-app` Gradle plugin) is not part of
# this build.
#
# The iOS runtime (`rdma-runtime-ios` + `rdma-runtime-ios-native`) is published
# from the main build below, because it references the Hermes xcframework produced
# by `scripts/setup-hermes-ios.sh` (`.hermes-ios/hermes.xcframework`). If that
# framework is absent the iOS publish is skipped, so Android-only setups still work.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

./gradlew -p "$ROOT_DIR/publish" \
    :rdma-annotation:publishToMavenLocal \
    :rdma-types:publishToMavenLocal \
    :rdma-kernel-compiler-plugin:publishToMavenLocal \
    :rdma-kernel-gradle-plugin:publishToMavenLocal \
    :rdma-plugin-compiler-plugin:publishToMavenLocal \
    :rdma-plugin-gradle-plugin:publishToMavenLocal \
    :rdma-runtime:publishToMavenLocal \
    :rdma-app-gradle-plugin:publishToMavenLocal

if [[ -d "$ROOT_DIR/.hermes-ios/hermes.xcframework" ]]; then
    echo "Publishing iOS runtime artifacts to mavenLocal ..."
    ./gradlew :rdma-runtime-ios:publishToMavenLocal
else
    echo "Skipping iOS runtime publish (run scripts/setup-hermes-ios.sh first)."
fi
