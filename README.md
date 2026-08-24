# Kardman

Cross-runtime object sharing between kernel JVM (Android) and plugin Hermes (JavaScript engine).  
Annotate a Kotlin class with `@RDMA` — it becomes available on both runtimes.  
Plugin code looks like plain Kotlin, but executes in Hermes via JSI/JNI bridge.

## How to use

1. Create a class in `kernel` (compiled to JVM):
   ```kotlin
   @RDMA
   class MyType(val name: String, val value: Int)
   ```

2. Use it in `plugin` (Compiled to js):
   ```kotlin
   import com.example.kernel.MyType

   val x = MyType("hello", 42)
   println(x.name)
   ```

Regular kotlin code, no need for manual serialization/deserialization. It creates `MyType` object in JVM memory and call its methods via proxies by JSI and JNI.

3. Build. kernel compiler plugin generates C++ bridge; the FIR compiler plugin rewrites plugin Kotlin into JS proxy calls automatically.

## Project modules

| Module | Role |
|--------|------|
| `:rdma-annotation` | `@RDMA` annotation (KMP) |
| `:kernel` | @RDMA annotated classes + @Composable UI widgets (JVM + material3) |
| `:rdma-kernel-compiler-plugin` | IR compiler plugin → generates C++ JNI/JSI glue + `rdma_classes.json` + `rdma_widgets.json` + vtable injection |
| `:rdma-kernel-gradle-plugin` | Gradle wrapper that wires `:rdma-kernel-compiler-plugin` into the kernel module |
| `:rdma-plugin-compiler-plugin` | FIR compiler plugin → resolves @RDMA/@RDMAWidget usages and rewrites plugin source to JS proxy calls |
| `:rdma-plugin-gradle-plugin` | Gradle wrapper that wires `:rdma-plugin-compiler-plugin` into the plugin module's JVM resolve compilation and generates the guest-side widget bridge |
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
