# RDMAHermes

Cross-runtime object sharing between JVM (Android) and Hermes (JavaScript engine).  
Annotate a Kotlin class with `@RDMA` — it becomes available on both runtimes.  
Plugin code looks like plain Kotlin, but executes in Hermes via JSI/JNI bridge.

**Pipeline:** `Kotlin plugin code` → FIR compiler plugin transformation → `Kotlin/JS` → `Hermes` → JNI → `JVM objects`

## Quick Start

```bash
# Build Android APK (all code generation runs automatically)
./gradlew :androidApp:assembleDebug

# Build plugin JS only
./gradlew :plugin:jsBrowserDevelopmentExecutableDistribution
```

## Adding a new @RDMA type

1. Create a class in `kernel/src/commonMain/kotlin/com/example/kernel/`:
   ```kotlin
   @RDMA
   class MyType(val name: String, val value: Int)
   ```

2. Use it in `plugin/src/kotlin/com/example/plugin/Main.kt`:
   ```kotlin
   import com.example.kernel.MyType
   val x = MyType("hello", 42)
   println(x.name)
   ```

3. Build. Kernel KSP generates C++ bridge; the FIR compiler plugin rewrites plugin Kotlin into JS proxy calls automatically.

## Project modules

| Module | Role |
|--------|------|
| `:rdma-annotation` | `@RDMA` annotation (KMP) |
| `:kernel` | @RDMA annotated classes (KMP: JVM + JS) |
| `:rdma-kernel-ksp` | KSP processor → generates C++ JNI/JSI glue + `rdma_classes.json` |
| `:rdma-compiler-plugin` | FIR compiler plugin → resolves @RDMA usages and rewrites plugin source to JS proxy calls |
| `:rdma-gradle-plugin` | Gradle wrapper that wires `:rdma-compiler-plugin` into the plugin module's JVM resolve compilation |
| `:rdma-runtime-android` | Android AAR: Hermes runtime + JNI bridge + C++ glue |
| `:plugin` | Demo plugin (Kotlin/JS), compiles to JS executed in Hermes |
| `:shared` | Shared KMP code (Compose UI) |
| `:androidApp` | Android app — initializes Hermes, loads plugin JS |

See [docs/architecture.md](docs/architecture.md) for details.

## Requirements

- Android SDK + NDK (28+)
- Kotlin 2.4.10
- Gradle 9.1+
- Hermes Android AAR published to `mavenLocal()` (see [contribution guide](docs/contribution.md))

## Docs

- [Architecture](docs/architecture.md)
- [Contributing](docs/contribution.md)
- [Design concept](DESIGN.md)
