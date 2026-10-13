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
# Setup

To get the framework running on a fresh machine, run the one-command initial
setup, then build and run the demo:

```bash
./scripts/setup.sh
```

It builds and publishes the custom Kotlin compiler, builds Hermes for Android and
iOS, and publishes the framework to `mavenLocal`. For wiring the framework into
your own project, see [How to use](how_to_use.md).

## Prerequisites

The only things you need installed on the host:

- **Android SDK** (compileSdk 36) and **NDK** (28+) — the C++ bridge is built with CMake.
- **JDK 17** — required by the Kotlin fork build and the Hermes Android build.
- **git** and network access — the setup script clones Hermes and downloads dependencies.
- **`cmake`** and **`ninja`** — needed to build the host `hermesc`. They do not have to be
  on `PATH`: the scripts fall back to the copies bundled with the Android SDK
  (`<sdk>/cmake/<version>/bin`).
- For iOS: **Xcode** (with an iOS simulator).

The Android SDK does **not** have to be configured by hand. Every script under
`scripts/` resolves it through [`scripts/android-sdk.sh`](../../scripts/android-sdk.sh),
in this order, and exports `ANDROID_HOME`/`ANDROID_SDK_ROOT` for Gradle:

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `sdk.dir` in `local.properties` (what Android Studio writes)
4. `sdk.dir` in `demo/local.properties`
5. the usual install locations — `~/Library/Android/sdk` (macOS), `~/Android/Sdk`
   (Linux), `%LOCALAPPDATA%\Android\Sdk` (Windows)

Set `ANDROID_HOME` or add `local.properties` only when the SDK lives somewhere
non-standard; otherwise the first run just works.

Gradle and Kotlin are **not** manual prerequisites: the Gradle wrapper
(`./gradlew`) and the version catalog (`gradle/libs.versions.toml`) pin them for
you. Note that this project uses a **custom Kotlin compiler** (see
[Initial setup](#initial-setup) below); it must be published to `mavenLocal`
before any build works.

## Initial setup

Run the one-command setup (idempotent — caches are reused on re-runs):

```bash
./scripts/setup.sh
```

It runs four steps, each in its own script under [`scripts/setup/`](../../scripts/setup/):

1. **Build the Kotlin compiler** ([`scripts/setup/kotlin.sh`](../../scripts/setup/kotlin.sh)).
   The framework is built with a fork of the Kotlin compiler (checked out as the
   `third_party/kotlin` submodule, branch `full_stable_js_export_table-2.4.10`) that
   adds the `-Xir-export-all` flag used to export plugin declarations to JS. It is
   **not** on Maven Central, so a fresh machine must build and publish it first. The
   script reads the compiler version from `gradle/libs.versions.toml`, runs
   `./gradlew install`, and verifies the published artifact. Heavy the first time
   (it compiles the compiler and stdlib); cached afterwards.

2. **Build Hermes for Android** ([`scripts/setup/hermes.sh`](../../scripts/setup/hermes.sh)).
   Clones Hermes at a pinned commit into `third_party/hermes/`, builds and publishes
   `com.facebook.hermes:hermes-android` to `mavenLocal`, and builds + installs the
   matching `hermesc` compiler into `third_party/bin/hermesc` (its bytecode version
   must match the runtime, or the plugin bundles will not load).

3. **Build Hermes for iOS** ([`scripts/setup/hermes-ios.sh`](../../scripts/setup/hermes-ios.sh)).
   Builds a universal `hermes.xcframework` into `.hermes-ios/`. Skipped automatically
   on non-macOS hosts; fails loudly on macOS if Xcode/`cmake`/`ninja` are missing.

4. **Publish the framework** ([`scripts/publish.sh`](../../scripts/publish.sh)).
   Publishes `rdma-annotation`, `rdma-types`, both compiler plugins and their Gradle
   plugins, `rdma-runtime` and `rdma-app-gradle-plugin` to `mavenLocal` (plus
   `rdma-runtime-ios` when the iOS xcframework from step 3 is present). The demo app
   consumes the `rdma-app` plugin by id from `mavenLocal`.

## Run the demo

After setup, build the standalone demo with:

```bash
./scripts/demo.sh
```

This publishes the framework, static-releases the demo kernel into `demo/versions/`,
and assembles the demo APK. Alternatively, run the equivalent Gradle steps by hand:

```bash
./scripts/publish.sh
./gradlew -p demo :kernel:internal:compileAndroidMain :kernel:user:alice:compileAndroidMain
# (release the demo kernel into demo/versions/, then:)
./gradlew -p demo :androidApp:assembleDebug
```

The pipeline:

1. **Kernel compiler plugin** — generates C++ glue + `rdma_manifest.json` + injects the vtable
2. **Plugin compiler plugin** — reads the manifest and rewrites the plugin source
3. **Plugin JS compilation** — Kotlin/JS → `RDMAHermes-plugin.js`
4. **C++ compilation** — CMake + NDK → `librdma_runtime.so` + `librdma_user.so`
5. **APK packaging** — assets + native libs + dex

Install the result on a device/emulator:

```bash
adb install -r demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Logcat filters for the demo: `RdmaBridge RdmaRuntime RdmaJni RDMA`.

### iOS demo

The iOS demo needs the framework published and the demo kernel released first, because
the Xcode build phase invokes Gradle tasks that read `demo/versions/`:

```bash
./scripts/setup.sh   # once
./scripts/demo.sh    # once: publishes the framework + static-releases the demo kernel
```

Then build the iOS app from the command line:

```bash
xcodebuild -project demo/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Or open `demo/iosApp/iosApp.xcodeproj` in Xcode, select the `iosApp` scheme, pick a
simulator, and press Run. The "Compile Kotlin Framework" build phase runs
`./gradlew -p demo :iosApp:embedAndSignAppleFrameworkForXcode :iosApp:aotCompileJsCAbi`
automatically (via the repository-root wrapper).

### Run the demo in an IDE

**Android Studio** — `demo/` is its own Gradle build (`demo/settings.gradle.kts`), so
open it as a project rather than the repository root:

1. Run `./scripts/setup.sh` once, then `./scripts/demo.sh` once.
2. File → Open… → select the `demo/` directory.
3. `demo/` has no Gradle wrapper; in Settings → Build Tools → Gradle set the
   distribution to version `9.1.0` (pinned by the repository-root
   `gradle/wrapper/gradle-wrapper.properties`).
4. Sync, then run the `androidApp` configuration on an emulator or device.

**Xcode** — open `demo/iosApp/iosApp.xcodeproj` and run the `iosApp` scheme (see
[iOS demo](#ios-demo) above).

### Useful build targets

| Command | What it does |
|---------|--------------|
| `./scripts/demo.sh` | Build the standalone demo (publish + release kernel + assemble APK) |
| `./gradlew -p demo :androidApp:assembleDebug` | Full demo APK (the whole pipeline above) |
| `./gradlew -p demo :iosApp:linkDebugFrameworkIosSimulatorArm64` | Build the iOS Kotlin/Native framework (no Xcode) |
| `./gradlew -p demo :plugin:alice:counter:jsBrowserDevelopmentExecutableDistribution` | Compile only the `alice:counter` plugin to JS (no native/APK) |
| `./gradlew -p demo :kernel:internal:compileAndroidMain` | Run only the kernel compiler plugin → `demo/kernel/internal/build/generated/rdma/` |
| `./scripts/test.sh` | Run the fast JVM tests (`:tests:rdma-tests`, `:rdma-store`, compiler plugins) |

Force a full rebuild if caches go stale:

```bash
rm -rf demo/kernel/internal/build demo/kernel/user/alice/build demo/plugin/alice/counter/build demo/.gradle/configuration-cache
./gradlew -p demo :androidApp:assembleDebug --no-configuration-cache --rerun-tasks
```

## How to use

See [How to use](how_to_use.md) for wiring the framework into your own project.
