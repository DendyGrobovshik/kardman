#!/usr/bin/env bash
set -euo pipefail

# Publishes all framework modules to mavenLocal so that an external project (or
# the in-repo demo) can consume the framework as
# `io.github.dendygrobovshik.kardman:*:1.0`.
#
# Uses a dedicated settings file that includes only the framework modules, so the
# demo app (which consumes the published `rdma-app` Gradle plugin) is not part of
# this build.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

./gradlew -p "$ROOT_DIR/publish" \
    :rdma-annotation:publishToMavenLocal \
    :rdma-types:publishToMavenLocal \
    :rdma-kernel-compiler-plugin:publishToMavenLocal \
    :rdma-kernel-gradle-plugin:publishToMavenLocal \
    :rdma-plugin-compiler-plugin:publishToMavenLocal \
    :rdma-plugin-gradle-plugin:publishToMavenLocal \
    :rdma-runtime-android:publishToMavenLocal \
    :rdma-app-gradle-plugin:publishToMavenLocal
