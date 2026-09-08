#!/usr/bin/env bash
set -euo pipefail

# Publishes all framework modules to mavenLocal so that an external project can
# consume the framework as `io.github.dendygrobovshik.kardman:*:1.0`.
#
# Needed for the "use the framework in your own project" flow. The in-repo demo
# builds the modules from source, so this is not required to run it.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

./gradlew \
    :rdma-annotation:publishToMavenLocal \
    :rdma-types:publishToMavenLocal \
    :rdma-kernel-compiler-plugin:publishToMavenLocal \
    :rdma-kernel-gradle-plugin:publishToMavenLocal \
    :rdma-plugin-compiler-plugin:publishToMavenLocal \
    :rdma-plugin-gradle-plugin:publishToMavenLocal \
    :rdma-runtime-android:publishToMavenLocal
