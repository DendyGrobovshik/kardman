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
# User Guide

Kardman is a framework that lets you dynamically load code and UI logic into a
mobile application. It connects two runtimes — **Kotlin (kernel)** and
**JavaScript (plugin)** — without serialization: `@RDMA` objects always live in
kernel memory, and the plugin uses them as ordinary objects that are really
proxies over JSI + JNI. Compose UI logic is proxied the same way, so dynamically
loaded UI mutates the compose tree in the kernel.

## Prerequisites

The only things you need installed on the host:

- **Android SDK** (compileSdk 36) and **NDK** (28+) — the C++ bridge is built with CMake.
- **`ANDROID_HOME`** (or `ANDROID_SDK_ROOT`) pointing at the SDK.
- **JDK 17+** — required by the Android Gradle Plugin.
- **git** and network access — the demo script clones Hermes and downloads dependencies.

Gradle and Kotlin are **not** manual prerequisites: the Gradle wrapper
(`./gradlew`) and the version catalog (`gradle/libs.versions.toml`) pin them for
you (Gradle 9.1+, Kotlin 2.4.10).

## Run the demo

One command builds everything:

```bash
./scripts/build.sh
```

This script does two things:

1. **Sets up Hermes** (see [`scripts/setup-hermes.sh`](../scripts/setup-hermes.sh)):
   - clones Hermes (default `static_h` branch of `facebook/hermes`) into `.hermes-src/`
   - builds the Android AAR and publishes `com.facebook.hermes:hermes-android` to `mavenLocal`
   - copies the JSI headers into `rdma-runtime-android/src/main/cpp/include/jsi/`
2. **Assembles the demo APK**: `./gradlew :androidApp:assembleDebug`, which runs the
   entire pipeline:
   1. **Kernel compiler plugin** — generates C++ glue + `rdma_manifest.json` + injects the vtable
   2. **Plugin compiler plugin** — reads the manifest and rewrites the plugin source
   3. **Plugin JS compilation** — Kotlin/JS → `RDMAHermes-plugin.js`
   4. **C++ compilation** — CMake + NDK → `librdma_runtime.so` + `librdma_user.so`
   5. **APK packaging** — assets + native libs + dex

The Hermes build is heavy the first time (it compiles libhermes for every ABI);
it is cached afterwards. Override any setting via env vars — see the header of
[`setup-hermes.sh`](../scripts/setup-hermes.sh).

Install the result on a device/emulator:

```bash
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Logcat filters for the demo: `RdmaBridge RdmaRuntime RdmaJni RDMA`.

### Useful build targets

| Command | What it does |
|---------|--------------|
| `./gradlew :androidApp:assembleDebug` | Full demo APK (the whole pipeline above) |
| `./gradlew :plugin:jsBrowserDevelopmentExecutableDistribution` | Compile only the plugin to JS (no native/APK) |
| `./gradlew :kernel:compileKotlinJvm` | Run only the kernel compiler plugin → `kernel/build/generated/rdma/cpp/` |
| `./gradlew :rdma-kernel-compiler-plugin:test :rdma-plugin-compiler-plugin:test :rdma-tests:test` | Run the compiler-plugin unit/integration tests |

Force a full rebuild if caches go stale:

```bash
rm -rf kernel/build plugin/build rdma-runtime-android/.cxx .gradle/configuration-cache
./gradlew :androidApp:assembleDebug --no-configuration-cache --rerun-tasks
```

## How to use

Concrete code snippets live in the [README](../README.md); this is the workflow in
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

## Use the framework in your own project

The framework modules are published as `io.github.dendygrobovshik.kardman:*:1.0`.
To consume them from a separate project:

1. **Publish the framework and Hermes to `mavenLocal()`:**

   ```bash
   ./scripts/publish.sh          # rdma-annotation, rdma-types, both compiler plugins + gradle plugins, rdma-runtime-android
   ./scripts/setup-hermes.sh     # com.facebook.hermes:hermes-android
   ```

2. **Add `mavenLocal()`** to `settings.gradle.kts` (`pluginManagement` and
   `dependencyResolutionManagement`).

3. **Create the four user-side modules** (a complete working example lives in the
   companion `wb2` project — copy its structure):

   | Module | Purpose | Plugins / deps |
   |--------|---------|----------------|
   | `:kernel` | Your `@RDMA` classes + `@Composable` widgets | `id("io.github.dendygrobovshik.kardman.rdma-kernel-compiler") version "1.0"`, dep `rdma-annotation:1.0` + Compose |
   | `:kernel-bridge` | Compiles the generated C++ into `librdma_user.so` | `androidLibrary` + CMake (globs the generated `*.cpp`), deps `:kernel`, `rdma-runtime-android:1.0` |
   | `:plugin` | Your plugin code (Kotlin/JS) | `id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"`, `jsMain` points at `build/generated/rdma` |
   | `:androidApp` | Loads Hermes + the plugin | `androidApplication`, deps `:kernel`, `:kernel-bridge`, `rdma-runtime-android:1.0` |

4. **Wire the plugin's two-pass compilation**: `jvmMain` compiles the original
   `src/kotlin` against `:kernel` (the FIR resolve pass emits `*_rdma.kt`); `jsMain`
   points only at `build/generated/rdma/`. The `:kernel` module adds
   `kotlin.srcDir("build/generated/rdma/kotlin")` for the injected vtable declaration.

5. **In `MainActivity`**, register the user bridge and init the runtime (async):

   ```kotlin
   UserBridge.nativeInstall()          // loads librdma_user.so, registers installUserBridge
   RdmaBridge.nativeInit(assets)       // JNI caches on UI thread, then starts the Hermes thread
   RdmaBridge.nativeEvalAsset("kotlin/RDMAHermes-plugin.js")
   // poll RdmaBridge.nativeIsReady(), then setContent { RdmaComposeHost.Content() }
   ```

See the in-repo `androidApp`, `kernel`, `kernel-bridge` and `plugin` modules for a
self-contained reference of this exact wiring.
