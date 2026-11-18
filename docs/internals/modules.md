<!--
Copyright 2026 DendyGrobovshik

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->
# Modules

Each Gradle module of the framework and its role. User-side modules (`:kernel:*`,
`:plugin:*`, the apps) live in this repository under `tests/stand/` (the stand, used by
the tests) and in the standalone `demo/` build.

## Shared model

### `:rdma-annotation`

The `@RDMA` annotation (classes and functions; `RUNTIME` retention so the compiler plugins
see it in compiled dependencies). Targets JVM, JS and iOS.

### `:rdma-types`

The serializable model both compiler plugins share: `RdmaClassInfo`, `RdmaFunctionInfo`,
`RdmaParameterInfo`, `RdmaType`/`RdmaTypeRef`, `StaticInfo`, `RdmaDeclaration` and the
manifest root. The kernel compiler writes it as `rdma_api.json`; the plugin compiler reads
it. Also the release model (changelog entries, plugin contracts).

## Kernel side

### `:rdma-kernel-compiler-plugin`

An IR compiler plugin applied to every kernel module. It extracts the `@RDMA` surface,
validates boundary types, writes the bridge for the module's platform (JNI or C ABI),
`rdma_api.json` and `kernel_manifest_<platform>.json`, and injects the vtable and the hotfix
slot prologues into the IR. In the app's iOS compilation it runs a second time in
`capi-glue` mode for the widget entries. It also contains the release CLI
(`static-release`, `dynamic-release`, `hotfix-release`, `generation-release`) and the
polyfill materializer. → [kernel_codegen.md](kernel_codegen.md),
[versioning.md](versioning.md)

### `:rdma-kernel-gradle-plugin`

Applies the kernel compiler plugin to a kernel module and passes the output directories.
Before compilation it writes the static Kotlin files the IR pass references: the vtable
dispatch declaration and the slot cell (`RdmaSlots.kt`, `RdmaSlotsNative.kt`) in the
module's package, so a kernel module needs no runtime dependency.

## Plugin side

### `:rdma-plugin-compiler-plugin`

Runs on the plugin's JVM compilation (the resolve pass) against the real kernel. It checks
the plugin, generates the kernel stubs from `rdma_api.json`, rewrites the Compose entry
points and `open`-class subclasses, and writes the JS compilation's sources plus
`plugin.json` (the kernel symbols the plugin uses). → [plugin_codegen.md](plugin_codegen.md)

### `:rdma-plugin-gradle-plugin`

Applies the plugin compiler plugin, merges the `rdma_api.json` of the kernel modules the
plugin may see, writes the guest bridge `RdmaRuntimeBridge.kt` (the `external object RDMA`,
state factories, effect helpers, collection classifiers), points the JS compilation at the
generated sources, and reads the changelog's `h` to stamp the plugin with the kernel
version it is built against.

### `:shared-runtime`

A Kotlin/JS module that compiles the bundles every plugin shares — Kotlin stdlib,
coroutines, Compose runtime — with stable export names, once per toolchain generation.

## Runtimes

### `:rdma-runtime` (Android)

An AAR with `librdma_runtime.so` and its Kotlin API.

- **C++** (`src/main/cpp/`): `common/` — the platform-independent core (`RdmaRuntime` —
  Hermes init and the event loop; `RdmaRendezvous` — threads; `RdmaProxyCore` — proxies
  and prototypes; `RdmaBlocks` — the block registry); `RdmaCompose.cpp` — the Compose
  protocol, state, slots and the JNI side of callbacks; `ListHandle.cpp`/`MapHandle.cpp` —
  collection operations over JNI; `RdmaJni.cpp` — JNI entry points; `generated/` — the
  Composer proxy. `ios/` holds the iOS leaves of the same core (built by
  `:rdma-runtime-ios`).
- **Kotlin**: `RdmaBridge` (init, bundle eval, readiness, the global exception handler),
  `RdmaComposeHost` (hosting plugin content), block holders (`RdmaBlockRef`,
  `RdmaFunctionN`), effects, `BundleLoader`.
- Public headers are exported as a prefab so the app's `librdma_user.so` links against it.

### `:rdma-runtime-ios` (iOS)

The iOS counterpart: a Kotlin/Native static framework plus `librdma_core.a`, the same C++
core compiled with `clang++` by a Gradle task (`common/` + `ios/`). Its Kotlin side
(`src/iosShared/kotlin/`, wired into both iOS targets because it uses per-target cinterop)
holds the C ABI shim (`CAbiShim.kt`: composer, state, boxing, `typeIdOf`), the function
registry, `RdmaComposeHost`, block holders and `RdmaSlots`.

`packageNativeSdk` zips the C++ headers, `librdma_core.a` for both architectures and
`hermes.xcframework` into the `rdma-runtime-ios-native` artifact; an app extracts it
(`ditto -x -k`, which keeps the xcframework symlinks) and links its `librdma_user.a`
against it.

### `:rdma-app-gradle-plugin`

Applied to the app module; does all app-side wiring:

- adds the dependencies (kernel modules, runtime, Hermes);
- **Android**: copies the kernels' generated C++ into the app, writes `UserBridge.kt`,
  `UserBridgeJni.cpp`, the aggregate bridge (`RdmaBridgeAggregate.cpp`, which composes the
  per-module bridges and owns `createWithOverrides`/`wrapUserObject`) and a
  `CMakeLists.txt`, and builds `librdma_user.so`; adds the widget entries
  (`widget-kotlin`) to the app's sources; generates the per-module JNI exports for slots
  and the vtable;
- **iOS**: builds `librdma_user.a` from the C ABI bridges, adds each kernel's
  `capi/kotlin` glue to the app's `iosMain` and applies the kernel compiler plugin there in
  `capi-glue` mode (`RdmaCAbiGlueSubplugin`);
- bakes plugins: compiles their JS to `.hbc` with `hermesc` and writes
  `rdma-modules.json` (load order). A plugin with `rdmaBakeInApp=false` is not baked
  unless the build passes `-PrdmaForceBakePlugins`.

Settings (`gradle.properties`): `rdmaKernelRootPackage`, `rdmaKernelProject`,
`rdmaKernelInternals`, `rdmaPluginRootPackage`, `rdmaPluginProject`, `rdmaHermesc`,
`rdmaVersionsDir`.

## Delivery

### `:rdma-store`

The store: a JVM HTTP server with the plugin registry, re-sync reconciliation and the
plugin state machine; it serves plugin and polyfill bundles. → [plugin_loading.md](plugin_loading.md)

## Tests

- `:tests:rdma-tests` — JVM tests of generator output and of the versioning, release and
  polyfill machinery.
- `:tests:rdma-integration-tests` — store tests and the e2e scripts
  (`e2e/*.sh`). → [testing.md](testing.md)
- `:tests:stand:*` — the stand: kernel modules (`internal`, `user:alice`, `user:itest`),
  plugins (`alice:counter`, `bob:services`, `itest:*`) and the two host apps.
