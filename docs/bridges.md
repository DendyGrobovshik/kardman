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
# Bridges

This document is the single source of truth for how the two runtimes talk to
each other — the **kernel bridge**. Everything else (`@RDMA` classes, widgets,
the Compose protocol) is layered on top of this. It is the one place where the
**Android (JVM)** and **iOS (Kotlin/Native)** hosts genuinely differ; on the
plugin side (Kotlin/JS → Hermes) nothing changes between platforms.

For the runtime data flow that sits on top of the bridge, see
[architecture.md](architecture.md); for how the bridge code is generated, see
[codegen.md](codegen.md); for the original iOS design intent, see
[design/ios_design.md](design/ios_design.md).

## Two backends, one contract

The kernel host runs on the **JVM** (Android) or on **Kotlin/Native** (iOS). The
bridge therefore has two backends that expose the same contract to the shared
C++ core:

| | Android | iOS |
|---|---|---|
| kernel runtime | JVM | Kotlin/Native |
| bridge ↔ kernel | JNI (`FindClass`/`GetMethodID`/`Call*Method`) | function-pointer C ABI (registry) |
| object handle | `jobject` (`NewGlobalRef`) | `void*` (`StableRef`) |
| string | `jstring` | `const char*` |

The shared C++ core (`common/RdmaRuntime.cpp`, `common/RdmaRendezvous.cpp` and
the generated `RdmaComposerProxy.cpp`) is compiled for both platforms; only the
leaf (`RdmaJni.cpp` on Android, `ios/RdmaComposeCAbi.cpp` on iOS) differs.

## The function-pointer registry (iOS)

JNI is dynamic: a `Composer` or any object crosses as a plain `jobject`, and
methods are resolved by name at runtime. A Kotlin/Native **framework** cannot
export C symbols for `@CName` functions — it exports them only as Objective-C
methods (`swift_name`). The iOS bridge therefore cannot link against named C
symbols the way it links against JNI symbols.

The boundary is instead inverted into a **function-pointer registry** (the same
mechanism zipline/redwood use):

- The Kotlin/Native shim registers every C-compatible function into a C++
  registry at startup: `@EagerInitialization` + `staticCFunction(::fn)` →
  `rdma_registerFunction(name, fn)`.
- The C++ side looks functions up by name (`rdma_lookupFunction`) and calls them
  by address. The generated headers declare `inline` wrappers with the *same*
  names as before, so call sites are unchanged.

Direction summary:

| Direction | Mechanism |
|---|---|
| C++ → Kotlin | registry lookup (`rdma_lookupFunction`) via inline wrappers |
| Kotlin → C++ | real `extern "C"` symbols (`rdma_nativeInvoke*`, `rdma_registerFunction`, …) imported via cinterop |

Type mapping:

| JNI | C ABI |
|---|---|
| `jobject` | `void*` (a `StableRef` handle) |
| `NewGlobalRef` | `StableRef.create(...)` |
| `DeleteGlobalRef` | `rdma_disposeStableRef` |
| `jstring` | `const char*` / `CPointer<ByteVar>` (`rdmaToKString`/`rdmaStringToCStr`) |
| `CallObjectMethod` | `rdma_<mod>_<Class>_<member>(handle, …)` |
| `Class.getName` (for `wrapAny`) | integer `rdma_<mod>_typeIdOf(handle)` |

`@CName` on a top-level Kotlin/Native function produces an Objective-C method
(`+fnV:`), not a `_fn` C symbol, when built as a framework; `@SymbolName` is
deprecated/internal, and a `staticLib` variant crashed `CAdapterCodegen`. The
registry sidesteps symbol export entirely: Kotlin never exports C symbols, so
the framework build stays the stock static framework.

## The Composer crossing

The Compose compiler lowers `@Composable fun Text(text)` to
`Text(text, $composer: Composer, $changed: Int)`. `Composer` is not a C type, so
a function taking/returning it cannot be `staticCFunction`-registered. On iOS
the composer **never crosses the boundary**:

- `RdmaComposeHost.Content()` (and `nativeInvokeScopeBlock`) store the ambient
  `currentComposer` in a Kotlin global.
- The shim exposes `rdmaGetCurrentComposer(): Composer?`; every composer proxy
  method (`rdma_composer_*`, `rdma_sideEffect`, `rdma_disposableEffect`) and
  every widget wrapper reads that global instead of taking a `Composer`
  argument. `ComposerProxyHost` on the C++ side is therefore stateless.

On Android the composer *does* cross (as a `jobject`); the same shared proxy
reads it via JNI. This is the one structural difference in how the base Compose
protocol is driven, and it is invisible to plugin code.

## Widget wrappers

A widget entry cannot be written as source: source `Widget(...)` is
`@Composable` and cannot be invoked with an explicit composer from a
non-`@Composable` function. On iOS, `CAbiWidgetIrGenerator` (an
`IrGenerationExtension` running after the compose compiler) fills a generated
stub body with a direct IR call to the already-lowered widget, e.g.
`Text(rdmaToKString(text), rdmaGetCurrentComposer() as Composer, 0)`.

## The vtable

`open` methods of an `@RDMA` class can be overridden from the plugin. The
dispatch target differs by platform:

- **Android** — the IR-injected `__vtable: Long` field is set via
  `SetLongField`.
- **iOS** — the IR-injected field cannot be referenced from generated Kotlin
  source (the frontend runs before the IR pass), so the vtable pointer lives in
  a runtime registry instead: the generated `rdma_<mod>_<Class>_setVtable` calls
  `rdmaVtableSet(obj, ptr)` and the IR-injected dispatch reads
  `rdmaVtableGet(this)`.

## State: creation, reads and writes

`SnapshotMutableState` is a Compose-runtime object owned by the kernel; the
plugin only ever touches it through a `StateProxyHost` (`get_value`/`set_value`).
How those two operations run differs, and this is the subtlest part of the
bridge:

- **Creation and reads** must happen on the UI thread inside the active
  composition snapshot. `RDMA.mutableStateOf` and `StateProxyHost.get_value`
  therefore marshal to the UI thread during composition (a blocking
  `rdmaCallUi`). Reading the state there registers the dependency with the
  Compose snapshot observer, so a later write schedules a recomposition.

- **Writes** (`set_value`):

  - **Android** — direct `CallVoidMethod(state, setValue, boxed)` on the Hermes
    thread. The JVM snapshot system is thread-safe and turns this cross-thread
    write into a recomposition on its own.
  - **iOS** — the write **must** be routed back to the main thread via
    `dispatch_async(dispatch_get_main_queue())`. Writing a Kotlin/Native
    `MutableState` directly on the Hermes thread bypasses the Compose snapshot
    observation: the `Recomposer` on the main thread is never notified and no
    recomposition happens. This is the one place where "JNI semantics" and
    "Kotlin/Native semantics" diverge, and it is handled inside
    `StateProxyHostCAbi::set_value` (`ios/RdmaComposeCAbi.cpp`).

The reason writes cannot just reuse the blocking `rdmaCallUi`: an async callback
runs on the Hermes thread while the UI thread is *not* in a rendezvous, so a
blocking JS→UI call there is illegal (and is asserted against). Writes are
therefore posted to the main queue instead.

## Async callbacks and lambdas

A plugin lambda passed into an `@RDMA` function is registered in JS
(`RDMA.registerBlock`) and the kernel receives its block id. Two kernel→JS
invoke entry points exist and are used for different shapes:

- `nativeInvokeCallback` / `nativeInvokeLambda` — post the block invocation back
  to the Hermes thread via the **low-priority** queue (`rdmaPostJs`), so async
  work (service callbacks like `httpGetAsync`/`fileCacheReadAsync`, click
  callbacks) never stalls an in-flight composition. The generated `@RDMA`
  service functions marshal their callback lambdas through `nativeInvokeLambda`.
- `nativeInvokeScopeBlock` / `nativeInvokeContent` — use the **high-priority**
  blocking rendezvous (`rdmaCallJs`) for composition itself.

The two-priority JS queue (`high` for compose/init over `low` for callbacks) is
what keeps composition responsive while async work drains; see
[architecture.md](architecture.md#threading-model-dedicated-hermes-thread).

## Lifecycle

`StableRef` handles (iOS) and `jobject` global refs (Android) are both released
from the proxy destructor: `PersonNativeState::~PersonNativeState` →
`DeleteGlobalRef` on Android, and the `HostObject`/`NativeState` destructor →
`rdma_disposeStableRef` on iOS. A handle therefore keeps the kernel object alive
exactly as long as the Hermes GC keeps the JS proxy alive.
