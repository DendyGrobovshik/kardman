#!/usr/bin/env bash
set -euo pipefail

# Boots the demo dev server: publishes the framework, static-releases the demo
# kernel, records a toolchain generation, then runs the `/generate` + store server.
# The demo app (`scripts/demo.sh`) talks to this server at http://10.0.2.2:8080.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/android-sdk.sh"
ensure_android_sdk "$ROOT_DIR" || exit 1

DEMO_DIR="$ROOT_DIR/demo"
VERSIONS_DIR="$DEMO_DIR/versions"
POLYFILL_OUT="$DEMO_DIR/build/polyfill"

# 1) Publish the framework to mavenLocal (idempotent).
"$ROOT_DIR/scripts/publish.sh"

# 2) Static-release the demo kernel into demo/versions (starting from empty).
./gradlew -p "$DEMO_DIR" --console=plain :kernel:internal:compileAndroidMain
"$ROOT_DIR/scripts/release.sh" static-release \
    "$DEMO_DIR/kernel/internal/build/generated/rdma/rdma_analysis.json" \
    "$VERSIONS_DIR" \
    "$POLYFILL_OUT" \
    "internal" \
    ""

# 3) Record a toolchain generation so `plugin-release` has a live generation.
./gradlew -p "$ROOT_DIR" --console=plain \
    :rdma-kernel-compiler-plugin:generationRelease \
    -Pgeneration=g1 -PversionsDir="$VERSIONS_DIR" -PrdmaPolicyFile="$DEMO_DIR/rdma.toml"

# 4) Run the server (env is read by DevServerKt; --no-daemon so it picks up fresh env).
DEMO_DIR="$DEMO_DIR" \
VERSIONS_DIR="$VERSIONS_DIR" \
PORT="${PORT:-8080}" \
HERMESC="${HERMESC:-$ROOT_DIR/third_party/bin/hermesc}" \
    "$ROOT_DIR/gradlew" -p "$DEMO_DIR" --console=plain :server:run --no-daemon
