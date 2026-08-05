# Architecture

## Overview

RDMAHermes enables sharing Kotlin objects between two runtimes — **JVM (Android)** and **Hermes (JavaScript)** — without serialization. Objects live in JVM memory; Hermes gets transparent proxies via JSI.

```
┌─────────────────────────────────────────────────────────────┐
│  Plugin (Kotlin/JS)                                         │
│  import com.example.kernel.Person                           │
│  val p = Person("Иван", 30)                                 │
│  println(p.name)                                            │
│         ↓ KSP transforms to                                 │
│  val p = js("RDMA.createPerson('Иван', 30)")                │
│  println(p.getName())                                       │
└──────────────────────────┬──────────────────────────────────┘
                           ↓ Kotlin/JS → plugin.js
┌──────────────────────────┴──────────────────────────────────┐
│  Hermes Runtime (C++, librdma_runtime.so)                   │
│  RDMA.createPerson(...)  →  JSI HostFunction                │
│  p.getName()             →  JSI HostFunction               │
└──────────────────────────┬──────────────────────────────────┘
                           ↓ JNI (cached jmethodID)
┌──────────────────────────┴──────────────────────────────────┐
│  Android / JVM                                              │
│  Person.java  →  live JVM object with global ref            │
│  GC managed via NativeState destructor                      │
└─────────────────────────────────────────────────────────────┘
```

## Module Details

### `:rdma-annotation`

Minimal KMP module with the `@RDMA` annotation:

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RDMA
```

Targets: JVM, JS, iOS. Retention `RUNTIME` is required so KSP can find the annotation in compiled dependencies.

### `:kernel`

Contains `@RDMA` annotated classes — the shared types that plugin code imports and uses.  
KMP module with JVM + JS + iOS targets.

```kotlin
@RDMA
class Person(val name: String, val age: Int) {
    override fun toString() = "Person(name='$name', age=$age)"
}
```

The **kernel KSP** (`:rdma-kernel-ksp`) runs on this module to:
1. Extract class metadata (name, constructors, methods, properties) via `KSP resolver`
2. Generate C++ JNI/JSI glue code
3. Write `rdma_classes.json` manifest for the plugin KSP

### `:rdma-kernel-ksp`

KSP SymbolProcessor that scans `@RDMA` classes and generates:

| Output | Content |
|--------|---------|
| `RdmaJniCache.h/cpp` | Struct with cached `jclass`, `jmethodID` for each @RDMA class |
| `{Class}HostObject.h/cpp` | JSI `HostFunction` wrappers for properties and methods |
| `{Class}NativeState` | Extends `jsi::NativeState` — holds JVM `jobject` global ref, releases on GC |
| `RdmaBridge.h/cpp` | `installRdmaBridge()` — registers all factories in `global.RDMA` |
| `rdma_classes.json` | JSON manifest of all @RDMA types (for plugin KSP) |

Generated C++ follows the JNI caching pattern:
- `FindClass` + `GetMethodID` called once at init
- Subsequent calls use cached `jmethodID` (O(1) lookup)
- `NewGlobalRef` ensures JVM object survives across native boundaries

### `:rdma-plugin-ksp`

KSP SymbolProcessor that transforms plugin source code. Reads `rdma_classes.json` from kernel build to get @RDMA type information. For each plugin source file:

1. Removes `import com.example.kernel.X` lines
2. Replaces `Person("str", 42)` → `js("RDMA.createPerson('str', 42)")` using regex with type-aware patterns from JSON
3. Replaces `.name` property access → `.getName()` method call

The processor uses `codeGenerator.createNewFile()` to output `*_rdma.kt` files in `build/generated/ksp/`. Original files are excluded from compilation via `kotlin.exclude("**/Main.kt")`.

### `:rdma-runtime-android`

Android Library (AAR) containing:

**Kotlin layer:**
- `RdmaBridge.kt` — `System.loadLibrary("rdma_runtime")` + `external fun nativeInit()` / `nativeEval(String): String`
- `RdmaPluginLoader.kt` — asset loading helpers

**C++ layer** (`src/main/cpp/`):
- `RdmaJni.cpp` — JNI entry points
- `RdmaRuntime.cpp` — Hermes init (`facebook::hermes::makeHermesRuntime()`), `installRdmaBridge()`, `console`/`globalThis` stubs
- `generated/` — auto-generated JNI/JSI glue (from kernel KSP, copied by Gradle task)

**Dependencies:** `hermes-android` (prefab), `fbjni` (prefab).

**Build process:**
1. Kernel KSP runs → generates C++ files in `kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/`
2. `copyGeneratedCpp` task cleans `src/main/cpp/generated/` then copies fresh files
3. `CMakeLists.txt` uses `file(GLOB)` to find all `.cpp` files in `generated/`
4. `invalidateCmake` task deletes `.cxx` cache before CMake runs (ensures new files are picked up)

### `:plugin`

KMP module with JS target. Contains the **original** plugin code:

```kotlin
import com.example.kernel.Person
fun main() {
    val p = Person("Эдвард", 104)
    println(p.name)
}
```

This original code is **not compiled directly**. Instead:
1. `Main.kt` is excluded via `kotlin.exclude("**/Main.kt")`
2. Plugin KSP reads it, generates `Main_rdma.kt` with transformed code
3. Only the transformed file is compiled to JavaScript

Output: `RDMAHermes-plugin.js` (webpack bundle ~980 KB, includes Kotlin stdlib).

### `:shared`

Compose Multiplatform shared UI code. Depends on `:kernel` for @RDMA types.

### `:androidApp`

Android entry point. `MainActivity`:
1. Initializes Hermes runtime via `RdmaBridge.nativeInit()`
2. Loads `kotlin-kotlin-stdlib.js` (required by Kotlin/JS output)
3. Loads `RDMAHermes-plugin.js` (compiled plugin)
4. `main()` in plugin JS calls `RDMA.createX(...)` → JNI → JVM

## Data Flow

### Constructor call

```
Person("Иван", 30) in plugin Kotlin
  → js("RDMA.createPerson('Иван', 30)")  // KSP transform
  → RDMA.createPerson('Иван', 30)         // JS output
  → createPersonHostFunction(JSI)         // C++ bridge
  → JNI: env->NewObject(personClass, ctor, jName, age)
  → Java: new Person("Иван", 30)          // JVM object with GlobalRef
  → NativeState holds jobject             // GC-managed
```

### Property access

```
p.name in plugin Kotlin
  → p.getName()                    // KSP transform
  → p.getName() in JS             // JS output
  → getNameHostFunction(JSI)      // C++ bridge
  → JNI: CallObjectMethod(getter_name) via cached jmethodID
  → Java: Person.getName()
```

### Method call

```
p.toString() in plugin Kotlin
  → p.toString() in JS (dynamic, no transform needed)
  → toStringHostFunction(JSI)
  → JNI: CallObjectMethod(method_toString) via cached jmethodID
  → Java: Person.toString()
```

### GC / Lifetime

When Hermes GC collects the JS proxy object:
1. `PersonNativeState` destructor called
2. `env->DeleteGlobalRef(globalRef_)` — releases JVM reference
3. If no other JVM references → Person is eligible for JVM GC

## Key Design Decisions

**JNI caching**: `FindClass` and `GetMethodID` are slow. They run once at `installRdmaBridge()`, and `jmethodID` / `jclass` (as global ref) are stored in a static `RdmaJniCache`.

**NativeState vs HostObject**: `NativeState` holds the `jobject` reference (instance data). `HostFunction` closures wrap method calls (prototype methods). This separates data from behavior, avoiding per-instance HostObject overhead.

**Property → getter**: Instead of JS property getters (which require `Object.defineProperty`), properties are exposed as `getName()`/`getAge()` methods. KSP transforms `.name` → `.getName()` in plugin source.

**KSP cross-module**: Kernel KSP generates `rdma_classes.json` because KSP cannot reliably resolve annotations across module boundaries in KMP JS targets. Plugin KSP reads this JSON instead of using `resolver.getSymbolsWithAnnotation()`.
