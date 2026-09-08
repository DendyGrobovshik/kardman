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
# Architecture

## Overview

RDMAHermes enables sharing Kotlin objects between two runtimes — **JVM (Android)** and **Hermes (JavaScript)** — without serialization. Objects live in JVM memory; Hermes gets transparent proxies via JSI.

```
┌─────────────────────────────────────────────────────────────┐
│  Plugin (Kotlin/JS, runs on the "Hermes" thread)            │
│  import com.example.kernel.Person                           │
│  val p = Person("Иван", 30)                                 │
│  println(p.name)                                            │
│         ↓ FIR compiler plugin (resolve + rewrite)           │
│  val p = js("RDMA.createPerson('Иван', 30)")                │
│  println(p.getName())                                       │
└──────────────────────────┬──────────────────────────────────┘
                           ↓ Kotlin/JS → plugin.js
┌──────────────────────────┴──────────────────────────────────┐
│  Hermes Runtime — librdma_runtime.so                        │
│  (Hermes + JNI + base Compose protocol)                     │
│  user glue lives in librdma_user.so, registered             │
│  into RDMA via the installUserBridge hook                   │
│  RDMA.createPerson(...)  →  JSI HostFunction                │
│  p.getName()             →  JSI HostFunction                │
└──────────────────────────┬──────────────────────────────────┘
                           ↓ JNI (cached jmethodID)
┌──────────────────────────┴──────────────────────────────────┐
│  Android / JVM — :kernel classes                            │
│  Person.kt → live JVM object with global ref                │
│  GC managed via NativeState destructor                      │
└─────────────────────────────────────────────────────────────┘
```

This scheme covers the **data path** (constructors, properties, methods). Two
things it elides, detailed later:

- **Two native libraries**: the generic runtime (`librdma_runtime.so` — Hermes +
  JNI + base Compose protocol) is fixed; the app-specific `@RDMA` glue is compiled
  into `librdma_user.so` and wired in through the `installUserBridge` hook.
- **Two threads**: data calls run directly on the Hermes thread; the **Compose/UI
  path** is reversed — the UI thread drives composition and marshals Compose ops to
  the Hermes thread (see [Threading Model](#threading-model-dedicated-hermes-thread)).

Per-module details: [modules_architecture.md](modules_architecture.md).
Code generation: [codegen.md](codegen.md).

## UI Widget Layer (Redwood-like)

UI is expressed with ordinary `@Composable` widgets. Widgets live in `:kernel`
(host, JVM + material3) and are imported by the plugin like `@RDMA` types. The
plugin compiler plugin rewrites widget calls into bridge calls that go
JSI → JNI → host; the host drives the Compose runtime.

Two kinds of widgets:

- **Primitive** (`Text`, `Column`, `Button`, `TextField`): defined in `:kernel`
  with a material3 implementation. They are the only widgets the kernel knows
  how to render, so they can only live in the kernel.
- **Composite**: ordinary `@Composable` functions built from primitives and
  other composites. They can live anywhere — kernel or plugin. A plugin
  composite's `content` lambda executes in the JS runtime.

### Kernel side (host, JVM)

`kernel/src/commonMain/.../Widgets.kt` declares the primitive widgets as
`@Composable` functions annotated with `@RDMA`, with real material3 bodies:

```kotlin
@Composable
@RDMA
fun Text(text: String) { M3Text(text) }

@Composable
@RDMA
fun Column(content: @Composable () -> Unit) { M3Column { content() } }
```

The kernel compiler plugin extracts every `@RDMA` function that is also
`@Composable` (a widget) into `rdma_manifest.json` (the unified manifest
consumed by the plugin compiler plugin).

The host renderer (`RdmaWidgetEntries.kt`, generated into the app module by the
`rdma-app` plugin) is the single dispatch point. It receives `(name, args)` from JNI
and calls the kernel widgets, bridging JS content/event lambdas back via
`RdmaComposeHost.nativeInvokeScopeBlock` / `nativeInvokeCallback`.

### Plugin side (guest, JS)

The plugin is pure JVM Kotlin (`plugin/src/kotlin`, no `src/jsMain`). It imports
kernel widgets and uses them like any other `@Composable`:

```kotlin
import com.example.kernel.Button
import com.example.kernel.Column
import com.example.kernel.Text
import com.example.kernel.TextField
import com.example.kernel.runRdmaApp

@Composable
fun App() {
    var text by remember { mutableStateOf("") }
    Column {
        TextField(text, onValueChange = { text = it })
        Text("You typed: $text")
        Button("Clear", onClick = { text = "" })
    }
}

fun main() {
    runRdmaApp { App() }
}
```

The plugin compiler plugin (`:rdma-plugin-compiler-plugin`) resolves the plugin
source against `:kernel` (JVM) and rewrites it:

- imports of `@RDMA` widget functions and `runRdmaApp`/`mutableStateOf` are
  removed;
- widget calls are rewritten to generated bridge functions
  (`Text(...)` → `rdmaText(...)`, `mutableStateOf(...)` → `rdmaMutableStateOf(...)`,
  `runRdmaApp { ... }` → `rdmaRunApp { ... }`).

The rewritten `*_rdma.kt` files land in `plugin/build/generated/rdma`, which is
the JS compilation's only source. The `:rdma-plugin-gradle-plugin` additionally
generates `RdmaRuntimeBridge.kt` there — the `external object RDMA` and the
`rdmaRunApp`/`rdmaMutableStateOf` helpers — while the compiler plugin generates
`RdmaWidgetBridge.kt` (`rdmaText`, `rdmaColumn`, ...) that forward to the host over JSI.

### Flow

```
plugin main(): runRdmaApp { App() }
  → rewritten to rdmaRunApp { App() }             (FIR rewrite)
  → RDMA.setComposerEmpty + RDMA.registerContent  (generated bridge, JS)
  → C++ stores g_content + g_empty                (JSI)
  → MainActivity: UserBridge.nativeInstall() + RdmaBridge.nativeInit(assets)
  → setContent { RdmaComposeHost.Content() }
  → nativeInvokeContent → g_content(composerProxy) (JNI → JS)
  → App() runs in JS against the Composer proxy
  → rdmaText("You typed: x") → RDMA.composeText(...)
  → JNI → composeText(...) → kernel Text() → material3
  → tap/typing → nativeInvokeCallback → JS lambda → state → recompose
```

### Companion statics

A public `val` on a companion object of an `@RDMA` class (e.g. `Alignment.Center`,
`ContentScale.Crop`, `Color.Unspecified`) is extracted as a `StaticInfo` and exposed as a
singleton getter `RDMA.<className><Name>()` (e.g. `RDMA.alignmentCenter()`). The plugin
FIR rewrite turns `Alignment.Center` into `rdmaAlignmentCenter()` and the guest bridge
generates the corresponding stub. The host-side C++ reads the companion singleton and
wraps the returned `@RDMA` value as a handle.

## Data Flow

### Constructor call

```
Person("Иван", 30) in plugin Kotlin
  → js("RDMA.createPerson('Иван', 30)")  // FIR transform
  → RDMA.createPerson('Иван', 30)         // JS output
  → createPersonHostFunction(JSI)         // C++ bridge
  → JNI: env->NewObject(personClass, ctor, jName, age)
  → Java: new Person("Иван", 30)          // JVM object with GlobalRef
  → NativeState holds jobject             // GC-managed
```

### Property access

```
p.name in plugin Kotlin
  → p.getName()                    // FIR transform
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

## Threading Model (dedicated Hermes thread)

Hermes runs on a dedicated `"Hermes"` thread (see `RdmaRendezvous.h/cpp`). The UI
thread never touches the `jsi::Runtime` directly — every cross-boundary call is
routed through two channels:

```
UI thread                                Hermes thread
┌────────────────────┐    uiToJs (high/low)   ┌────────────────────┐
│ Compose            │ ─────────────────────▶ │ jsi::Runtime       │
│ nativeInvoke*      │ ◀───────────────────── │ runContent /       │
│ (compose ops)      │    jsToUi (compose ops) │ runScopeBlock /    │
└────────────────────┘                        │ callbacks / eval   │
                                               └────────────────────┘
```

- **Synchronous rendezvous** (`rdmaCallJs`/`rdmaCallUi`) for everything that touches
  the `Composer` and for state reads during composition. Both sides run *reentrant
  service loops*: while waiting for a response, a thread services the opposite queue.
  This is what makes nested `UI → JS → UI → JS → …` composition correct and deadlock-free.
- **Async** (`rdmaPostJs`) for service callbacks (`httpGetAsync`, `fileCacheReadAsync`, …),
  click callbacks and `nativeInvokeLambda`. These run on the Hermes thread without
  stalling the UI. The JS queue is two-priority: compose/init (`high`) over callbacks (`low`).
- **Async init** (`RdmaBridge.nativeInit`/`nativeEvalAsset`): `nativeInit` populates all
  JNI caches on the UI thread (app-class `FindClass` needs the app classloader) and then
  starts the Hermes thread; `nativeEvalAsset` enqueues evals asynchronously.
  `nativeIsReady()` becomes true once the runtime is ready **and** all evals have drained.

Why the rendezvous exists for `Composer`/state, not just perf isolation:

- `SnapshotMutableState` must be **created** and **read** on the UI thread inside the
  active composition snapshot. `RDMA.mutableStateOf` and `StateProxyHost.get_value`
  therefore marshal to the UI thread during composition; `set_value` is direct
  (thread-safe) and triggers recomposition from the Hermes thread.
- `g_currentComposer` / `g_scopeBlocks` / `g_jsValues` / `g_empty` are owned by the
  Hermes thread; `g_currentComposer` is borrowed into the UI-bound compose op.

Thread ids are captured (`g_uiTid`/`g_jsTid` via `gettid()`) and asserted in debug
builds in the `rdmaCallJs`/`rdmaCallUi` executors.

## Key Design Decisions

**JNI caching**: `FindClass` and `GetMethodID` are slow. They run once at `installRdmaBridge()`, and `jmethodID` / `jclass` (as global ref) are stored in a static `RdmaJniCache`.

**NativeState vs HostObject**: `@RDMA` data classes use the `NativeState` pattern — the `jobject` reference (instance data) lives in a `jsi::NativeState` subclass, while property getters/setters and methods are prototype `HostFunction`s that recover the pointer via `getNativeState()` + `static_pointer_cast`. This avoids `jsi::HostObject`'s per-property `get` lookup and per-instance overhead. `jsi::HostObject` is reserved for the Compose protocol proxies (`ComposerProxyHost`, `StateProxyHost`, `ScopeUpdateScopeProxyHost`), which genuinely need dynamic property access.

**Property → getter**: Instead of JS property getters (which require `Object.defineProperty`), properties are exposed as `getName()`/`getAge()` methods. FIR plugin transforms `.name` → `.getName()` in plugin source.

**Cross-module manifest**: The kernel compiler plugin generates `rdma_manifest.json` so the plugin compiler plugin has an explicit list of `@RDMA` types. The FIR plugin reads this JSON and uses full FIR resolution (across module boundaries) to determine which concrete class/method each call site refers to.
