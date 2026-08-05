# Contributing

## Prerequisites

- **Android Studio** with SDK 36, NDK 28+
- **Kotlin 2.4.10**, Gradle 9.1+
- **Hermes Android AAR** — published to `mavenLocal()`

## Hermes Setup

The project depends on `com.facebook.hermes:hermes-android:0.76.9` from `mavenLocal()`.  
You need to build Hermes for Android and publish it locally.

The Hermes source is expected at `~/Code/hrms/hermes/`. The JSI headers are copied from there:
```bash
# Already done once — headers are at:
rdma-runtime-android/src/main/cpp/include/jsi/
```

If you need to update JSI headers:
```bash
cp ~/Code/hrms/hermes/API/jsi/jsi/*.h rdma-runtime-android/src/main/cpp/include/jsi/
```

JSI headers are NOT included in the Hermes AAR prefab module — they are bundled with this project.

## Building

### Full Android build
```bash
./gradlew :androidApp:assembleDebug
```

This runs the entire pipeline:
1. **Kernel KSP** — generates C++ glue + `rdma_classes.json`
2. **Plugin KSP** — reads JSON, transforms plugin source
3. **Plugin JS compilation** — Kotlin/JS → `plugin.js`
4. **C++ compilation** — CMake + NDK → `librdma_runtime.so`
5. **APK packaging** — assets + native libs + dex

### Plugin JS only
```bash
./gradlew :plugin:jsBrowserDevelopmentExecutableDistribution
```

### Kernel C++ generation only
```bash
./gradlew :kernel:kspKotlinJvm
# Output: kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/
```

### Force full rebuild (if stale caches)
```bash
rm -rf kernel/build plugin/build rdma-runtime-android/.cxx .gradle/configuration-cache
./gradlew :androidApp:assembleDebug --no-configuration-cache --rerun-tasks
```

## Project Configuration

### Version catalog

All dependency versions in `gradle/libs.versions.toml`:

| Key | Value | Notes |
|-----|-------|-------|
| `kotlin` | `2.4.10` | Kotlin version |
| `ksp` | `2.3.11` | KSP version (KSP 2.3.x ≈ Kotlin 2.4.x) |
| `agp` | `9.0.1` | Android Gradle Plugin |
| `hermes-android` | `0.76.9` | Hermes AAR (from mavenLocal) |
| `composeMultiplatform` | `1.11.1` | Compose Multiplatform |

### Adding a new dependency module

1. Create the module directory with `build.gradle.kts`
2. Add `include(":module-name")` in `settings.gradle.kts`
3. Add plugin/library alias in `gradle/libs.versions.toml` if needed

### Plugin KSP configuration

The plugin KSP processor receives the JSON manifest path via `ksp.arg`:

```kotlin
// plugin/build.gradle.kts
ksp {
    arg("rdmaClassesJson", "${rootProject.projectDir}/kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/rdma_classes.json")
}
```

The KSP output goes to `build/generated/ksp/js/jsMain/kotlin/plugin/`.  
Original `Main.kt` is excluded from JS compilation via `kotlin.exclude("**/Main.kt")`.

### C++ CMake configuration

`rdma-runtime-android/src/main/cpp/CMakeLists.txt`:

```cmake
find_package(hermes-engine REQUIRED CONFIG)
file(GLOB GENERATED_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/generated/*.cpp")
target_link_libraries(rdma_runtime hermes-engine::hermesvm android log)
```

The `file(GLOB)` collects all generated `.cpp` files. When a new @RDMA class is added, the Gradle task `invalidateCmake` deletes `.cxx` to force CMake re-configuration.

### Android packaging

Duplicate native libs (`libhermesvm.so`, `libc++_shared.so`) from prefab are resolved with `pickFirsts` in `androidApp/build.gradle.kts`:

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true
        pickFirsts.add("**/libhermesvm.so")
        pickFirsts.add("**/libc++_shared.so")
    }
}
```

## Testing

### Build verification
```bash
# Compile all Kotlin modules
./gradlew compileKotlinJs compileKotlinJvm compileDebugKotlin

# Verify KSP generates expected output
./gradlew :kernel:kspKotlinJvm
cat kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/rdma_classes.json

# Check transformed plugin code
cat plugin/build/generated/ksp/js/jsMain/kotlin/plugin/Main_rdma.kt | head -20
```

### Runtime testing

Install the APK on an emulator or device and check logcat:

```bash
# Filter relevant tags
adb logcat -s RdmaBridge RdmaRuntime RdmaJni RDMA
```

Expected output:
```
RdmaBridge: Initializing RDMA bridge...
RdmaBridge: RDMA bridge installed successfully
RdmaRuntime: RDMA runtime initialized with bridge
RDMA: Bridge initialized
RDMA: Kotlin stdlib loaded
RdmaRuntime: JS: Person(name='Эдвард', age=104)     ← console.log output
RdmaRuntime: JS: Name: Эдвард, Age: 104               ← plugin println
```

### Adding a test class

1. Create `kernel/src/commonMain/kotlin/com/example/kernel/TestType.kt`:
   ```kotlin
   @RDMA class TestType(val msg: String)
   ```

2. Add to plugin `Main.kt`:
   ```kotlin
   import com.example.kernel.TestType
   val t = TestType("works")
   println(t.msg)
   ```

3. Build and run — no other changes needed.

## Troubleshooting

### `undefined symbol: registerXBridge`

CMake cached the old file list. Run:
```bash
rm -rf rdma-runtime-android/.cxx
```

### `rdma_classes.json not found`

Kernel KSP hasn't run. Run:
```bash
./gradlew :kernel:kspKotlinJvm
```

### `KMP Dependencies Resolution Failure` on iOS

`rdma-annotation` or `kernel` missing iOS targets. Make sure both have:
```kotlin
iosArm64()
iosSimulatorArm64()
```

### `Property 'console' doesn't exist`

Older build without `console` stub in `RdmaRuntime.cpp`. Rebuild.

### `Property 'RDMA' doesn't exist` / `ReferenceError`

Plugin JS loaded before Hermes runtime init. Check `MainActivity` — `nativeInit()` must be called before `nativeEval()`.

### `Error loading module 'RDMAHermes:plugin'`

Missing Kotlin stdlib. Make sure `kotlin-kotlin-stdlib.js` is loaded before the plugin JS. Or the plugin has a dependency on another Kotlin module — remove `implementation(project(":kernel"))` from plugin JS source set.

## Code Generation Pipeline

```
┌─────────────────────────────────┐
│ 1. Kernel @RDMA classes         │
│    kernel/src/commonMain/...    │
└───────────────┬─────────────────┘
                │ :kernel:kspKotlinJvm
                ▼
┌─────────────────────────────────┐
│ 2. Generate C++ + JSON          │
│    kernel/build/generated/...   │
│    ├── PersonHostObject.cpp     │
│    ├── RdmaJniCache.cpp         │
│    ├── RdmaBridge.cpp           │
│    └── rdma_classes.json        │
└───────────────┬─────────────────┘
                │ copyGeneratedCpp
                ▼
┌─────────────────────────────────┐
│ 3. Copy to runtime module       │
│    rdma-runtime-android/        │
│    src/main/cpp/generated/      │
└─────────────────────────────────┘
                │ CMake / NDK
                ▼
┌─────────────────────────────────┐
│ 4. Compile C++ → .so           │
│    librdma_runtime.so           │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│ 5. Plugin original source       │
│    plugin/src/jsMain/kotlin/    │
│    Main.kt                      │
└───────────────┬─────────────────┘
                │ :plugin:kspKotlinJs (reads JSON)
                ▼
┌─────────────────────────────────┐
│ 6. Generate transformed code    │
│    plugin/build/generated/ksp/  │
│    Main_rdma.kt                 │
└───────────────┬─────────────────┘
                │ Kotlin/JS compiler
                ▼
┌─────────────────────────────────┐
│ 7. Compile JS → plugin.js       │
│    (includes kotlin-stdlib)     │
└───────────────┬─────────────────┘
                │ assets
                ▼
┌─────────────────────────────────┐
│ 8. APK                          │
│    ├── librdma_runtime.so       │
│    ├── plugin.js                │
│    ├── kotlin-kotlin-stdlib.js  │
│    └── classes.dex              │
└─────────────────────────────────┘
```
