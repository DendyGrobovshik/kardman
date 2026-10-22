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

Two commands on a fresh machine — one-time setup, then the demo:

```bash
./scripts/setup.sh   # Kotlin fork + Hermes (Android, iOS) + publish to mavenLocal
./scripts/demo.sh    # build the standalone demo APK
```

Anything failing? See [Troubleshooting](troubleshooting.md). To wire the framework into
your own project, see [How to use](how_to_use.md).

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
compiler** (`2.3.21-rdma`), so [Initial setup](#initial-setup) must publish it to
`mavenLocal` before anything else builds.

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

```bash
./scripts/demo.sh
```

It publishes the framework, static-releases the demo kernel into `demo/versions/`, and
assembles `demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk`. The demo
exercises the whole pipeline: kernel compiler plugin (C++ glue + `rdma_manifest.json`) →
plugin compiler plugin → Kotlin/JS plugin bundle → CMake/NDK native libs → APK.

## Run the demo in an IDE

### Android Studio

`demo/` is its own Gradle build ([`demo/settings.gradle.kts`](../../demo/settings.gradle.kts)),
so open it as a project, not the repository root:

1. File → Open… → select the `demo/` directory.
2. `demo/` ships no Gradle wrapper (it is git-ignored). Either generate one —
   `./gradlew -p demo wrapper --gradle-version 9.1.0` — or, in Settings → Build,
   Execution, Deployment → Build Tools → Gradle, pick *Use Gradle from: Specified
   location* and point it at Gradle `9.1.0` (the version pinned by the repository-root
   wrapper).
3. Sync, then Run the `androidApp` configuration.

Run in the IDE does not release the kernel: after changing the kernel or a plugin,
re-run `./scripts/demo.sh` first (see
[changes are not picked up](troubleshooting.md#run-in-the-ide-does-not-pick-up-my-changes)).

Without an IDE, install the APK built by `demo.sh` yourself:

```bash
adb install -r demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Logcat filters: `RdmaBridge RdmaRuntime RdmaJni RDMA`.

### Xcode

Preparing for iOS is the same two commands: `setup.sh` produces
`.hermes-ios/hermes.xcframework` and publishes `rdma-runtime-ios`, `demo.sh` releases the
demo kernel that the Xcode build phase reads from `demo/versions/`.

Open `demo/iosApp/iosApp.xcodeproj`, select the `iosApp` scheme, pick a simulator and
press Run — the "Compile Kotlin Framework" build phase invokes Gradle itself. From the
command line:

```bash
xcodebuild -project demo/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
```

## How to use

See [How to use](how_to_use.md) for wiring the framework into your own project.
