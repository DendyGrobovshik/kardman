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
# Troubleshooting

Symptoms, causes and fixes for the problems hit most often on a first setup. See
[Setup](setup.md) for the happy path.

## Setup and downloads

### `Read timed out` while downloading dependencies

**Symptom.** `scripts/setup.sh` gets part-way through step 1 and dies with
`Read timed out`, `Recv failure`, or a "resource not found" message for an artifact that
clearly exists.

**Cause.** The Kotlin fork resolves every repository through JetBrains'
cache-redirector (`cache-redirector.jetbrains.com`), which fronts an AWS CloudFront
distribution. On networks where that CDN is throttled, a download starts and then stalls
after the first ~16 KB. The same happens for any other CloudFront host — it is a network
path problem, not a repository problem.

**Fix.** Make CloudFront reachable, and tell Gradle to use your proxy for *every* build
(the Kotlin fork is its own build with its own `gradle.properties`, so a proxy set only
in the root project will not apply to it):

```properties
# ~/.gradle/gradle.properties
systemProp.https.proxyHost=proxy.example.com
systemProp.https.proxyPort=3128
```

Whether the proxy actually tunnels HTTPS can be checked without touching the build:

```bash
curl -x http://proxy.example.com:3128 -I https://www.jetbrains.com/intellij-repository/releases/
# expect: HTTP/1.1 302
```

A `503`/`403` here means the proxy refuses `CONNECT`, which is what Gradle needs.

### `Plugin [id: 'com.google.protobuf', version: '0.9.6'] was not found`

**Cause.** Same redirector stall as above, reported at the first plugin resolution:
Gradle could not download the plugin marker, so it says "not found". The plugin id is
incidental — any other plugin can fail the same way.

**Fix.** As above (working proxy/VPN). Re-running with the network fixed is enough;
nothing in the repository needs changing.

### `Could not resolve com.jetbrains.intellij.platform:util:...`

**Cause.** The Kotlin build needs the IntelliJ SDK artifacts (`com.jetbrains.intellij.platform:*`,
`com.jetbrains.intellij.java:*`, the version pinned by `versions.intellijSdk` in
`third_party/kotlin/gradle/versions.properties`). They are published only on
`https://www.jetbrains.com/intellij-repository`, which redirects to JetBrains' CloudFront
distribution, and they have no public mirror.

**Fix.** There is no repository-level workaround: the machine needs a route to CloudFront
(working proxy or VPN, see above). An alternative is to run `scripts/setup/kotlin.sh` on
a machine that can reach it and copy `~/.m2/repository/org/jetbrains/kotlin/<version>`
plus the Gradle cache for `com.jetbrains.*` to the target machine.

### `error: Android SDK not found`

**Symptom.** A script under `scripts/` stops before doing any work and prints that
message with the `local.properties` recipe.

**Cause.** No SDK is set up in any of the places the scripts look. On a fresh clone there
is usually no `local.properties` (it is git-ignored) and no `ANDROID_HOME` exported.

**Fix.** Every script resolves the SDK through
[`scripts/android-sdk.sh`](../../scripts/android-sdk.sh), in this order:

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `sdk.dir` in `local.properties` (what Android Studio writes)
4. `sdk.dir` in `demo/local.properties`
5. the usual install locations — `~/Library/Android/sdk` (macOS), `~/Android/Sdk`
   (Linux), `%LOCALAPPDATA%\Android\Sdk` (Windows)

So normally there is nothing to do. If your SDK lives elsewhere, either export
`ANDROID_HOME=/path/to/Android/sdk`, or create `local.properties` in the repository root:

```properties
sdk.dir=/path/to/Android/sdk
```

A path is accepted only if it looks like an SDK (contains `platform-tools`, `platforms`,
`ndk`, `cmdline-tools` or `build-tools`); anything else is skipped with a warning.

### `error: cmake and ninja are required to build the host hermesc`

**Cause.** The host `hermesc` compiler is built with CMake, and neither `PATH` nor the
Android SDK provided it.

**Fix.** Either install them (`brew install cmake ninja`), or make sure the Android SDK
contains `<sdk>/cmake/<version>/bin` — the scripts add the newest one to `PATH`
themselves.

### Disk is full (ENOSPC)

**Symptom.** The Hermes build dies while linking a static library
(`libhermesVMRuntime.a` and friends) with `write64() failed, returned -1 ... errno=28` and
`ranlib: error writing file`.

**Cause.** `errno=28` is `ENOSPC` — the disk is full. Hermes static libraries are large
(hundreds of MB per archive), and the iOS build keeps two build trees plus the host
compiler around.

**Fix.** Free space; **20–30 GB** is a comfortable amount for the Android + iOS Hermes
builds and the later Gradle/CMake steps. Generally safe to remove:

- Xcode simulator runtimes you are not using (`xcrun simctl runtime list`,
  `xcrun simctl runtime delete <id>`), simulator devices (`xcrun simctl delete unavailable`)
  and `~/Library/Developer/CoreSimulator/Caches`
- Android emulator AVDs and SDK `system-images` you do not use
- unused NDK versions under `<sdk>/ndk` (Hermes pins one: `ndkVersion` in
  `third_party/hermes/android/gradle/libs.versions.toml`)
- `~/Library/Caches/*`, `brew cleanup -s --prune=all`,
  `~/.gradle/caches/build-cache-1` (keep `~/.gradle/caches/modules-2` — re-downloading
  through a proxy is painful)

Keep the in-progress Hermes trees — `third_party/hermes/build_iphoneos`,
`build_iphonesimulator`, `build_host_hermesc`. Re-running the same script resumes from
them; deleting them restarts hours of compilation. `third_party/hermes/android/.cxx` and
`third_party/hermes/build` are Android build outputs and can go (the AAR is already in
`mavenLocal`), at the price of rebuilding that part.

After freeing space, re-run the step that failed (`./scripts/setup/hermes-ios.sh`,
`./scripts/setup/hermes.sh`); it is idempotent.

## Android demo

### `SDK location not found` in Android Studio

**Cause.** Android Studio opened `demo/` and has no `demo/local.properties` yet, or the
`ANDROID_HOME` of the shell that launched it is not visible to the IDE.

**Fix.** Let Studio write it: File → Sync Project with Gradle Files, it creates
`demo/local.properties` with `sdk.dir` automatically. Otherwise copy the recipe from
[the SDK is not found](#error-android-sdk-not-found).

### Which Gradle and JDK does Android Studio need for `demo/`?

`demo/` has no Gradle wrapper in git, so either generate one:

```bash
./gradlew -p demo wrapper --gradle-version 9.1.0
```

or point Studio at Gradle **9.1.0** — Settings → Build, Execution, Deployment → Build
Tools → Gradle → *Use Gradle from: Specified location*. If you already ran any
repository script, the distribution is unpacked at
`~/.gradle/wrapper/dists/gradle-9.1.0-bin/<hash>/gradle-9.1.0`.

The JDK needs no configuration: `demo/gradle/gradle-daemon-jvm.properties` pins the
daemon toolchain (JDK 21), and the foojay resolver in `demo/settings.gradle.kts` fetches
it when it is missing. Leaving Studio's own Gradle JDK on the bundled JBR is fine.

### Run in the IDE does not pick up my changes

**Cause.** A plugin can only be built against a *released* kernel, so the demo kernel is
not built by the IDE: `scripts/demo.sh` static-releases it into `demo/versions/` as a
separate step. Pressing Run only assembles the app against whatever is already released
there.

**Fix.** After changing the kernel or a plugin, run:

```bash
./scripts/demo.sh
```

and then Run from the IDE again. The same applies after changing framework code, which
has to be re-published: `./scripts/publish.sh`.

### How do I run the APK without an IDE?

```bash
adb install -r demo/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb shell am start -n org.example/.MainActivity
```

### Where are the demo logs?

`adb logcat` with the demo filters: `RdmaBridge RdmaRuntime RdmaJni RDMA`. For the iOS
app, use the Xcode console (or `xcrun simctl spawn booted log stream`).

### Plugin bundles do not load / bytecode version mismatch

**Cause.** `third_party/bin/hermesc` (which compiles plugin bundles to `.hbc`) and the
Hermes runtime in the app must agree on the bytecode version. A stale `hermesc` from an
older run will produce bundles the runtime rejects.

**Fix.** Re-run `./scripts/setup/hermes.sh` — it rebuilds `hermesc` from the pinned
commit and reinstalls it into `third_party/bin/`.

## iOS demo

### The Xcode build phase fails

**Cause.** Xcode runs Gradle through the "Compile Kotlin Framework" build phase:

```
./gradlew -p demo :iosApp:embedAndSignAppleFrameworkForXcode :iosApp:aotCompileJsCAbi
```

Xcode's log hides most of Gradle's output, so the real error is hard to read there.

**Fix.** Run the same two tasks by hand from the repository root — you get the full
Gradle output:

```bash
./gradlew -p demo :iosApp:embedAndSignAppleFrameworkForXcode :iosApp:aotCompileJsCAbi
```

The prerequisites for this phase are `./scripts/setup.sh` (which produces
`.hermes-ios/hermes.xcframework` and publishes `rdma-runtime-ios`) and `./scripts/demo.sh`
(which releases the demo kernel into `demo/versions/`). The phase itself exports
`JAVA_HOME` from `/usr/libexec/java_home -v 17`, so JDK 17 has to be installed.

### The app builds but crashes on launch

**Cause.** Usually a missing piece in the bundle: the second run-script phase
("Embed Hermes + .hbc") copies `hermesvm.framework` from
`demo/iosApp/build/rdma-native-sdk/hermes.xcframework/<slice>/` and the compiled `.hbc`
bundles from `demo/iosApp/build/generated/rdma/hbc/` into the app. If those inputs do not
exist yet, the phase silently copies nothing (`if [ -d ... ]`) and the app fails at
startup.

**Fix.** Check the built app:

```bash
ls "$(ls -d ~/Library/Developer/Xcode/DerivedData/iosApp-*/Build/Products/Debug-iphonesimulator/KardmanDemo.app)" \
  Frameworks/*.hbc
```

You should see `Frameworks/hermesvm.framework` and the `.hbc` files. If they are missing,
run `./scripts/demo.sh` (it regenerates `rdma-native-sdk` and the `.hbc` bundles) and
build again in Xcode.

### `Run script build phase ... will be run during every build` warning

Harmless. Both custom phases (the Gradle invocation and the Hermes/`.hbc` copy) declare
no outputs, so Xcode re-runs them on every build and warns about it.

## Caches and rebuilds

### How do I force a full rebuild of the demo?

```bash
rm -rf demo/kernel/internal/build demo/kernel/user/alice/build \
       demo/plugin/alice/counter/build demo/.gradle/configuration-cache
./gradlew -p demo :androidApp:assembleDebug --no-configuration-cache --rerun-tasks
```

### Can I delete the heavy build directories?

Everything under `third_party/` is generated and regenerable, but the price differs:
`third_party/hermes/build_iphoneos`, `build_iphonesimulator` and `build_host_hermesc`
cost hours to rebuild (see [Disk is full](#disk-is-full-enospc)),
`third_party/hermes/android/.cxx` costs tens of minutes, and `third_party/kotlin` is a
checkout that `scripts/setup/kotlin.sh` reclones. In `~/.m2/repository` keep the
published `org.jetbrains.kotlin:<version>` artifacts — that is the compiler build you
would otherwise repeat.
