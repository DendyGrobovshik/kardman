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
# Contributing

For developers of the framework itself. Start with [overview.md](overview.md); terms are in
[glossary.md](glossary.md); tests in [testing.md](testing.md).

## Build and publish

The framework is built with a custom Kotlin compiler; run `./scripts/setup.sh` once
([getting_started.md](../guide/getting_started.md)). The compiler plugins, Gradle plugins and
runtimes are consumed from `mavenLocal()` by the stand apps' tooling and by `demo/`; after
changing them, re-publish and stop the daemons:

```bash
./scripts/publish.sh
./gradlew --stop
```

`scripts/publish/` is a separate minimal Gradle build that publishes only the framework
modules (`scripts/publish/hermesc/` publishes the `hermesc` binary as an artifact).

## Versions

All dependency versions are in `gradle/libs.versions.toml`:

| Key | Value | Notes |
|-----|-------|-------|
| `kotlin` | `2.3.21-rdma` | the custom compiler fork |
| `agp` | `9.0.1` | Android Gradle Plugin |
| `hermes-android` | `0.76.9` | from `mavenLocal` |
| `composeMultiplatform` | `1.11.1` | |

A new module: add its directory with `build.gradle.kts`, `include(...)` it in
`settings.gradle.kts`, and add catalog aliases if needed.

## Compiler plugin options

- The kernel Gradle plugin passes the kernel compiler plugin its output directories
  (`cppOutputDir`, `jsonOutputDir`, `kotlinOutputDir`), the backend (`jni` / `capi`), the
  platform for `kernel_manifest_<platform>.json`, and, in the app's iOS compilation, the
  `capi-glue` mode with `glueModules` (`moduleId=package=apiJson;…`).
- The plugin Gradle plugin passes the plugin compiler plugin the kernel API descriptions it
  may see and the output directory (`build/generated/rdma`).

## Native builds

- `rdma-runtime/src/main/cpp/CMakeLists.txt` builds `librdma_runtime.so` (links
  `hermes-engine::hermesvm`).
- The app Gradle plugin generates the app's `CMakeLists.txt` for `librdma_user.so`
  (`file(GLOB)` over the copied bridge sources, links the runtime prefab and Hermes);
  `invalidateCmake` deletes `.cxx` when the file set changes. Duplicate native libraries
  from prefabs are resolved with `pickFirsts` (`libhermesvm.so`, `libc++_shared.so`,
  `librdma_runtime.so`).
- iOS: `:rdma-runtime-ios` compiles the C++ core into `librdma_core.a` with `clang++`;
  `packageNativeSdk` packages it with the headers and `hermes.xcframework`.

## Rules for changes

- **Design first.** Mechanisms and their invariants are in [design/](../design/); a change
  to an invariant (threading, hotfix, exceptions, compatibility) is a design decision, not a
  code change.
- **Both platforms.** A change to a crossing is made for JNI and C ABI together; the shared
  core (`common/`) and the descriptor dispatcher must not get platform-only variants.
- **Performance rules** for per-call paths: [proxies_and_handles.md](proxies_and_handles.md#performance-rules).
- **Compatibility.** The call form only grows; old bundles must keep working on new hosts
  ([plugin_codegen.md](plugin_codegen.md#call-form-v1)).
- **Docs.** User-visible behaviour → `guide/`; a mechanism → its page in `internals/`; a
  decision → `design/`.
