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
# How to use

Concrete code snippets live in the [README](../../README.md); this is the workflow in
words. You write ordinary Kotlin and the compiler plugins turn it into proxy calls
at build time:

- **Share a data type** — annotate a class in `:kernel` with `@RDMA`. The kernel
  compiler plugin generates the JNI/JSI glue for its constructors, methods and
  properties automatically.
- **Use it from the plugin** — import the class in `:plugin` and use it like any
  other object. Constructor calls, `.property` reads/writes and method calls are
  rewritten to `RDMA.*` bridge calls by the FIR compiler plugin. No manual
  serialization.
- **Expose UI widgets** — annotate `@Composable` functions in `:kernel` with
  `@RDMA`; they become host-side widgets the plugin can call, driving the Compose
  tree on the kernel side.
- **Inherit & override** — annotate an `open class` with `@RDMA`; the plugin can
  subclass it and override methods. Overrides are dispatched through an injected
  vtable, so they are visible from both runtimes.
- **Offload work** — `@RDMA` classes with callback parameters let the plugin hand
  heavy work (network, IO) to the kernel's threads and receive results via
  callbacks.

For what is and isn't supported yet (types, constructors, properties, methods,
plugin transformation), see [features.md](features.md).

## Cross-module `@RDMA` references

A kernel module may reference an `@RDMA` type declared in another kernel module —
e.g. a widget in `:kernel:user:<user>` taking a `Color`/`Dp` from `:kernel:internal`.
The generated glue marshals such types without knowing their concrete class (see
[codegen.md](../codegen.md) for the type-erased mechanism), so cross-module references
"just work" as long as the referencing module depends on the owning module via
Gradle. The only check the kernel compiler performs is that the referenced FQN is
itself annotated `@RDMA` (resolved through the dependency classpath); a non-`@RDMA`
type in an `@RDMA` signature remains a build error.

## Kernel public-API rule

A kernel module's non-`@RDMA` **concrete classes** must be `internal`/`private` —
a public non-`@RDMA` class could otherwise leak into an `@RDMA` signature. This
rule does **not** apply to `interface`/`object`/top-level `val` declarations
(e.g. a host-side `KernelServiceProvider` interface or a `KernelServices` singleton
registry) or to the framework entry point `runRdmaApp`, which are public by design.

## Use the framework in your own project

The framework modules are published as `io.github.dendygrobovshik.kardman:*:1.0`.
To consume them from a separate project:

1. **Publish the framework and Hermes to `mavenLocal()`** (run from the kardman repo):

   ```bash
   ./scripts/setup.sh
   ```

   This builds and publishes the Kotlin compiler, Hermes (Android + iOS) and the
   framework modules. If you already have the Kotlin compiler, the two relevant
   steps are:

   ```bash
   ./scripts/publish.sh          # rdma-annotation, rdma-types, both compiler plugins + gradle plugins, rdma-runtime, rdma-app-gradle-plugin
   ./scripts/setup/hermes.sh     # com.facebook.hermes:hermes-android
   ```

2. **Add `mavenLocal()`** to `settings.gradle.kts` (`pluginManagement` and
   `dependencyResolutionManagement`).

3. **Create the three user-side modules** (a complete working example lives in the
   companion `wb2` project — copy its structure):

   | Module | Purpose | Plugins |
   |--------|---------|---------|
   | `:kernel` | Your `@RDMA` classes + `@Composable` widgets | `id("io.github.dendygrobovshik.kardman.rdma-kernel-compiler") version "1.0"` |
   | `:plugin` | Your plugin code (Kotlin/JS) | `id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"`, `jsMain` points at `build/generated/rdma` |
   | `:androidApp` | Loads Hermes + the plugin | `androidApplication` + `id("io.github.dendygrobovshik.kardman.rdma-app") version "1.0"` |

   The `rdma-app` plugin does the rest of the wiring for you:
   - adds the dependencies (`:kernel`, `rdma-runtime`, `hermes-android`);
   - generates the user bridge and compiles the generated C++ into `librdma_user.so`;
   - copies the compiled plugin JS into the app's assets (or AOT-compiles it to `.hbc`,
     see below).

   So `:androidApp` needs no hand-written deps, native config or asset-copy tasks —
   just the plugin and the usual `android {}` block.

4. **Set the module paths/packages** in `gradle.properties` (single source of truth):

   ```properties
   rdmaKernelRootPackage=com.example.kernel
   rdmaKernelProject=:kernel
   rdmaKernelInternals=:kernel:internal
   rdmaPluginRootPackage=com.example.plugin
   rdmaPluginProject=:plugin
   # optional: AOT-compile the plugin JS to Hermes bytecode (.hbc)
   rdmaHermesc=third_party/bin/hermesc
   ```

   Kernel and plugin modules are nested subprojects of the container projects above.
   Each kernel module's package is derived from its path (`:kernel:user:alice` →
   `com.example.kernel.user.alice`); each plugin module's package is
   `rdmaPluginRootPackage.<username>` (the first path segment after `:plugin`). A plugin
   sees the framework-owned `rdmaKernelInternals` modules plus its own `:kernel:user:<username>`
   module, and nothing else.

   `rdmaHermesc` points the `rdma-app` plugin at the Hermes compiler. You no longer need
   to vendor the `hermesc` binary: if the path is set but the file is absent, the plugin
   resolves `io.github.dendygrobovshik.kardman:hermesc:1.0:<os-arch>` from `mavenLocal`
   (published by `scripts/setup/hermes.sh`) and extracts it automatically. Setting
   `rdmaHermesc` still opts into AOT; leaving it unset keeps the plain-JS fallback.

   Keep `hermesc` in sync with the runtime: a stale vendored binary produces
   `Wrong bytecode version. Expected N but got M` at runtime and the plugin bundles
   won't load. Prefer leaving `third_party/bin/hermesc` absent and letting the plugin
   resolve the pinned binary from `mavenLocal`.

5. **In `MainActivity`**, register the user bridge, init the runtime (async) and
   load the plugin bundles from the generated manifest:

   ```kotlin
   UserBridge.nativeInstall()          // loads librdma_user.so, registers installUserBridge
   RdmaBridge.nativeInit(assets)       // JNI caches on UI thread, then starts the Hermes thread
   for (module in readModuleManifest()) {
       RdmaBridge.nativeEvalAsset("kotlin/$module.hbc")
   }
   // poll nativeIsReady() AND nativeContentVersion(), then:
   // if (ready) key(contentVersion) { RdmaComposeHost.Content() }
   ```

   `readModuleManifest()` reads the build-generated `kotlin/rdma-modules.json` (module
   base names in topological load order — shared dependencies first, then the plugins).
   **Never hardcode the module list**: after a compiler/Compose update the dependency
   set and its order change, and a hardcoded list silently breaks module resolution,
   leaving the host rendering nothing (blank screen).

   Without `rdmaHermesc`, the plugin copies the JS source (`.js`) and the runtime evals
   it directly. With `rdmaHermesc`, the plugin compiles the JS to `.hbc` bytecode (faster
   startup, smaller size) and emits the `rdma-modules.json` manifest — load those `.hbc`
   assets instead of `.js`. Poll `nativeContentVersion()` and wrap the content in
   `key(contentVersion)` so a dynamically-loaded plugin's `registerContent` triggers a
   host recomposition (see the in-repo `androidApp` for the reference).

See the in-repo `androidApp`, `kernel` and `plugin` modules for a self-contained
reference of this exact wiring.
