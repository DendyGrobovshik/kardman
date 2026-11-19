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
# Getting started

Two commands on a fresh machine — one-time setup, then the demo:

```bash
./scripts/setup.sh   # Kotlin fork + Hermes (Android, iOS) + publish to mavenLocal
./scripts/demo.sh    # build the standalone demo APK
```

Anything failing? See [troubleshooting.md](troubleshooting.md).

## Prerequisites

- **Android SDK** (compileSdk 36) with **NDK 28+**. Nothing to configure: the scripts
  find the SDK themselves and export `ANDROID_HOME` for Gradle (see
  [the SDK is not found](troubleshooting.md#error-android-sdk-not-found)).
- **JDK 17** — required by the Kotlin fork build and the Hermes Android build.
- **git** and network access — the setup clones Hermes and downloads dependencies.
- **`cmake`** and **`ninja`** — used to build the host `hermesc`; taken from `PATH`, or
  from the copies bundled with the Android SDK.
- **Xcode** with a simulator — only for the iOS part.

Gradle and Kotlin are not manual prerequisites: the wrapper and
`gradle/libs.versions.toml` pin them. The framework is built with a **custom Kotlin
compiler** (`2.3.21-rdma`), so the setup must publish it to `mavenLocal` before anything
else builds.

## Initial setup

```bash
./scripts/setup.sh
```

It runs four steps, each in its own script under [`scripts/setup/`](../../scripts/setup/)
and each idempotent (caches are reused on re-runs):

1. **Kotlin compiler** — [`kotlin.sh`](../../scripts/setup/kotlin.sh). Builds the compiler
   fork (branch `full_stable_js_export_table-2.3.21`, the one adding `-Xir-export-all`)
   and publishes it to `mavenLocal`. It is not on Maven Central, so a fresh machine has
   to build it; heavy the first time, cached afterwards.
2. **Hermes for Android** — [`hermes.sh`](../../scripts/setup/hermes.sh). Clones Hermes at
   a pinned commit into `third_party/hermes/`, publishes `com.facebook.hermes:hermes-android`
   to `mavenLocal`, and installs the matching `hermesc` into `third_party/bin/hermesc`
   (its bytecode version must match the runtime, or plugin bundles will not load).
3. **Hermes for iOS** — [`hermes-ios.sh`](../../scripts/setup/hermes-ios.sh). Builds
   `hermes.xcframework` into `.hermes-ios/`. Skipped on non-macOS hosts.
4. **Framework** — [`publish.sh`](../../scripts/publish.sh). Publishes the framework
   modules to `mavenLocal`, plus `rdma-runtime-ios` when the xcframework from step 3 is
   present.

## Run the demo

The demo is an editor: write plugin code in the app, press **Generate**, and a dev server
compiles it into a plugin that the app loads without a restart. It needs the dev server
running next to the app:

```bash
./scripts/demo-server.sh   # publish, release the demo kernel, serve on :8080
./scripts/demo.sh          # build the APK
adb install -r demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

`demo.sh` publishes the framework, releases the demo kernel into `demo/versions/` and
assembles the APK. Logcat filters: `RdmaBridge RdmaRuntime RdmaJni RDMA`. More in
[demo/README.md](../../demo/README.md).

### Android Studio

`demo/` is its own Gradle build, so open it as a project, not the repository root:

1. File → Open… → select the `demo/` directory.
2. `demo/` ships no Gradle wrapper. Either generate one —
   `./gradlew -p demo wrapper --gradle-version 9.1.0` — or, in Settings → Build,
   Execution, Deployment → Build Tools → Gradle, choose *Use Gradle from: Specified
   location* and point it at Gradle `9.1.0`.
3. Sync, then Run the `androidApp` configuration.

Run in the IDE does not release the kernel: after changing the kernel, re-run
`./scripts/demo.sh` first (see
[changes are not picked up](troubleshooting.md#run-in-the-ide-does-not-pick-up-my-changes)).

### Xcode

`setup.sh` produces `.hermes-ios/hermes.xcframework` and publishes `rdma-runtime-ios`;
`demo.sh` releases the demo kernel that the Xcode build phase reads. Open
`demo/iosApp/iosApp.xcodeproj`, select the `iosApp` scheme, pick a simulator and press
Run — the "Compile Kotlin Framework" phase invokes Gradle itself. From the command line:

```bash
xcodebuild -project demo/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## Use the framework in your own project

The framework modules are published as `io.github.dendygrobovshik.kardman:*:1.0`.

1. **Publish the framework and Hermes to `mavenLocal()`** — `./scripts/setup.sh` from
   this repository. With the Kotlin compiler already published, the relevant steps are
   `./scripts/publish.sh` and `./scripts/setup/hermes.sh`.

2. **Add `mavenLocal()`** to `settings.gradle.kts` (`pluginManagement` and
   `dependencyResolutionManagement`).

3. **Create the user-side modules** (the in-repo `demo/` build is a complete example):

   | Module | Purpose | Plugins |
   |--------|---------|---------|
   | `:kernel:…` | your `@RDMA` classes, functions and widgets | `id("io.github.dendygrobovshik.kardman.rdma-kernel-compiler") version "1.0"` |
   | `:plugin:…` | your plugin code (Kotlin/JS) | `id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"` |
   | `:shared-runtime` | the Kotlin/JS libraries every plugin runs on (stdlib, Compose runtime, coroutines), whole and loaded once | Kotlin Multiplatform, `js` target with `-Xir-export-all` and `-Xir-dce=false` (copy [`shared-runtime/build.gradle.kts`](../../shared-runtime/build.gradle.kts)) |
   | `:androidApp` / `:iosApp` | loads Hermes and the plugins | `id("io.github.dendygrobovshik.kardman.rdma-app") version "1.0"` |

   The `rdma-app` plugin does the app-side wiring: it adds the dependencies, compiles the
   generated bridges into `librdma_user`, adds the widget entries and bakes the plugin
   bundles (`.hbc`) into the app.

   The shared runtime is required, even with a single plugin: every plugin — baked in or
   delivered later by the store — links against the same full libraries, and a plugin's own
   copy of the stdlib is trimmed to what that plugin uses. The app build fails without it
   (another module name can be set with `rdmaSharedRuntimeProject`).

   On iOS the kernel modules are exported to the framework's Objective-C header, so Swift
   code of the host can use kernel types — implement a kernel interface, call a kernel
   service. Set `rdmaExportKernelToObjC=false` to keep them out. A Swift implementation is
   reached through an Objective-C message per call (strings and collections are converted),
   cannot be hotfixed, and must tolerate calls from any thread.

4. **Set the module paths and packages** in `gradle.properties`:

   ```properties
   rdmaKernelRootPackage=com.example.kernel
   rdmaKernelProject=:kernel
   rdmaKernelInternals=:kernel:internal
   rdmaPluginRootPackage=com.example.plugin
   rdmaPluginProject=:plugin
   # optional: AOT-compile plugin JS to Hermes bytecode (.hbc)
   rdmaHermesc=third_party/bin/hermesc
   ```

   Each kernel module's package is derived from its path (`:kernel:user:alice` →
   `com.example.kernel.user.alice`); each plugin module's package is
   `rdmaPluginRootPackage.<username>`. A plugin sees the `rdmaKernelInternals` modules
   plus its own `:kernel:user:<username>` module, nothing else.

   If `rdmaHermesc` is set but the file is absent, the plugin resolves the pinned
   `io.github.dendygrobovshik.kardman:hermesc:1.0:<os-arch>` from `mavenLocal`. Prefer
   that over a vendored binary: a stale `hermesc` produces
   `Wrong bytecode version. Expected N but got M` at runtime.

5. **Start the runtime in the app** (Android `MainActivity`; iOS mirrors it):

   ```kotlin
   UserBridge.nativeInstall()          // loads librdma_user, registers the user bridge
   RdmaBridge.nativeInit(assets)       // JNI caches on the UI thread, starts the Hermes thread
   for (module in readModuleManifest()) {
       RdmaBridge.nativeEvalAsset("kotlin/$module.hbc")
   }
   setContent { RdmaComposeHost.Component("alice:counter/main") }
   ```

   `readModuleManifest()` reads the build-generated `kotlin/rdma-modules.json` (bundles
   in load order: shared dependencies first, then plugins). A plugin registers its
   composables as components; the host renders them by `"<pluginId>/<name>"` anywhere and
   any number of times ([how_to_write_plugin.md](how_to_write_plugin.md#components)).
   **Never hardcode the module list**: it changes with compiler and Compose versions, and a wrong order leaves a
   blank screen. Plugins delivered by the store are loaded the same way after a re-sync —
   see [plugin_loading.md](../internals/plugin_loading.md).

6. **Release your kernel before building plugins.** A plugin is compiled against a
   *released* kernel: the plugin Gradle plugin reads the changelog's `h` from
   `rdmaVersionsDir` (default `versions/`) and fails if the kernel was never released. See
   [hotfix_and_releases.md](hotfix_and_releases.md).

Next: [how_to_write_kernel.md](how_to_write_kernel.md) and
[how_to_write_plugin.md](how_to_write_plugin.md).
