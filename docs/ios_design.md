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
# iOS Support

This document describes how the framework adds a second native host — **iOS
(Kotlin/Native)** — alongside the existing **Android (JVM)** host. The
user-facing model is unchanged: plugin code still looks like plain Kotlin and
never sees the implementation details of either platform.

## Overview

RDMAHermes connects two runtimes:

- **kernel** — `@RDMA` classes and widgets, shipped in the app;
- **plugin** — Kotlin/JS code compiled to Hermes bytecode and loaded at runtime.

On Android the kernel runs on the JVM and the C++ bridge talks to it through
JNI. On iOS the kernel runs on Kotlin/Native and the same bridge talks to it
through a generated C ABI. Everything on the plugin side — the JS compilation,
`rdma_manifest.json`, the FIR rewrite, `.hbc` bytecode, and versioning — is
platform-independent and reused as-is.

## What changes

Only the kernel host and the leaf of the bridge change:

| | Android | iOS |
|---|---|---|
| kernel runtime | JVM | Kotlin/Native |
| bridge ↔ kernel | JNI | function-pointer C ABI (see below) |

## Bridge interop: function-pointer C ABI instead of JNI

JNI is dynamic (reflection: `FindClass`/`GetMethodID`/`Call*Method`), so a
`Composer` (or any object) crosses as a plain `jobject`. A Kotlin/Native
**framework** cannot export C symbols for `@CName` functions (it exports them
only as Objective-C methods, `swift_name`), so the iOS bridge cannot link
against named C symbols the way it links against JNI symbols.

Instead the boundary is inverted to a **function-pointer registry**, the same
mechanism zipline/redwood use:

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
| `DeleteGlobalRef` | `rdma_disposeStableRef` (in the `NativeState` destructor) |
| `jstring` | `const char*` / `CPointer<ByteVar>` (`rdmaToKString`/`rdmaStringToCStr`) |
| `CallObjectMethod` | `rdma_<mod>_person_getName(handle, …)` |

### Why the registry (not `@CName` symbols)

`@CName` on a top-level Kotlin/Native function produces an Objective-C method
(`+fnV:`), not a `_fn` C symbol, when built as a **framework** (verified with
`nm`). `@SymbolName` is deprecated/internal, and a `staticLib` variant crashed
`CAdapterCodegen`. The registry sidesteps symbol export entirely: Kotlin never
exports C symbols, so the framework build stays the stock static framework.

## The Composer crossing

The Compose compiler lowers `@Composable fun Text(text)` to
`Text(text, $composer: Composer, $changed: Int)`. `Composer` is not a C type, so
a function taking/returning it cannot be `staticCFunction`-registered. Instead
the composer **never crosses the boundary**:

- `RdmaComposeHost.Content()` (and `nativeInvokeScopeBlock`) store the ambient
  `currentComposer` in a Kotlin global.
- The shim exposes `rdmaGetCurrentComposer(): Composer?`; every composer proxy
  method (`rdma_composer_*`, `rdma_sideEffect`, `rdma_disposableEffect`) and
  every widget wrapper reads that global instead of taking a `Composer`
  argument. `ComposerProxyHost` on the C++ side is therefore stateless.

The kernel plugin runs **after** the compose compiler (verified: the lowered
`$composer`/`$changed` parameters are already present in the IR), so the widget
wrapper is emitted as IR that calls the *already-lowered* widget directly with
the global composer and `changed = 0`.

## Widget wrappers (IR generation)

A widget entry cannot be written as source: source `Widget(...)` is `@Composable`
and cannot be invoked with an explicit composer from a non-`@Composable`
function. `CAbiWidgetGenerator` therefore emits, per widget:

- a C-compatible **stub** (`fun rdma_<mod>_composeText(text: CPointer<ByteVar>?) {}`)
  plus the content/callback **lambda helpers** and the `staticCFunction`
  registration;
- `CAbiWidgetIrGenerator` (an `IrGenerationExtension`, running after the compose
  compiler) then **replaces the stub body** with a direct IR call to the lowered
  widget, e.g. `Text(rdmaToKString(text), rdmaGetCurrentComposer() as Composer, 0)`.

Because the generated source is compiled in the following incremental pass, the
build is two-pass: pass N generates the stubs/helpers/data, pass N+1 compiles
them and the IR pass injects the widget bodies. A fail-fast guard in
`RdmaFunctionExtractor` aborts the build if a `@Composable @RDMA` function lacks
the `$composer` parameter (i.e. the compose compiler did not run first).

## The vtable

On Android the vtable is an IR-injected `__vtable: Long` field set via
`SetLongField`. The IR-injected field cannot be referenced from generated Kotlin
source (the frontend runs before the IR pass), so on iOS the vtable pointer
lives in a runtime registry instead: the generated `rdma_<mod>_<Class>_setVtable`
calls `rdmaVtableSet(obj, ptr)` and the IR-injected dispatch reads
`rdmaVtableGet(this)`.

## Memory and threading

Kotlin/Native (2.4) uses the new memory manager: a shared heap where mutable
objects are reachable from any thread (JVM-like semantics). No freezing or
thread confinement. The data path therefore stays direct on the Hermes thread,
exactly as with JNI.

The two-thread rendezvous (main ↔ Hermes) is unchanged and still exists only
because the Compose runtime is single-threaded on the main thread. On iOS the
only difference is `gettid()` → `pthread_self`.

## Services implemented in Swift

On Android the host app implements services by registering a
`KernelServiceProvider`. On iOS that provider may be written in Swift.
Kotlin/Native exports the `KernelServiceProvider` interface as a generated
Objective-C protocol; the Swift app conforms to it and registers via
`KernelServices.register(...)`. Parameter conversion (`String`/`Boolean`/`Int`/
`List`/lambdas ↔ Objective-C types and blocks) is automatic.

A callback round-trips as: plugin block id → `RdmaFunction1` (Kotlin) →
Objective-C block → Swift, and back through `nativeInvokeCallback`. Direct
Swift interop (Swift Export) is still Beta and is a future drop-in for the same
Kotlin interface.

## Code generation changes

- The kernel compiler plugin gains a `capi` backend (`CAbi*` generators) in
  addition to the `jni` backend (`CppGenerator`/`RdmaWidgetGenerator`).
- `wrapAny` uses an integer `typeId` registry (`rdma_<mod>_typeIdOf`) instead of
  `Class.getName`.
- The Composer proxy is generated with no composer argument (`CAbiComposerProxyGenerator`).
- Widget wrappers are IR-generated (`CAbiWidgetIrGenerator`), see above.

## Packaging

Mirrors the Android split into a reusable runtime and a per-app bridge:

- `rdma-runtime-ios` — reusable static framework (Hermes + JSI + rendezvous +
  base Compose protocol + the function-pointer registry). The C++ core
  (`common/RdmaRuntime.cpp`, `ios/RdmaComposeCAbi.cpp`,
  `ios/generated/RdmaComposerProxy.cpp`) is compiled with `clang++` by a Gradle
  `Exec` task into `librdma_core.a` and linked via `linkerOpts`; the shim imports
  the C++-provided entry points through a cinterop `rdma.def`.
- `hermes.framework` — prebuilt XCFramework (0.76.x), built reproducibly by
  `scripts/setup-hermes-ios.sh`.
- a per-app generated bridge (the `RdmaCAbi_<mod>`, `RdmaWidgetBridge_<mod>`,
  per-class proxies) compiled by the `rdma-app` plugin's `buildRdmaUserCAbi`
  task into `librdma_user.a` (the analog of `librdma_user.so`).

## Host app integration

This is the entry surface the app developer touches. It mirrors the Android
`MainActivity`: the app registers its services, boots the runtime, loads the
plugin bytecode, then renders the UI.

1. `KernelServices.register(provider)`
2. `RdmaBridge.nativeInit(...)` — on iOS takes bundle/bytes instead of
   `AssetManager`
3. load the plugin `.hbc`
4. wait for `nativeIsReady()`
5. `setContent { RdmaComposeHost.Content() }`

On iOS the entry point is SwiftUI hosting
`ComposeUIViewController { RdmaComposeHost.Content() }`.

## Module changes

- `:kernel:*` — become KMP: `jvm` (resolve pass + manifest) plus
  `iosArm64`/`iosSimulatorArm64` (runtime).
- a new `rdma-runtime-ios` next to `rdma-runtime`.
- `rdma-app-gradle-plugin` — gains an iOS path mirroring the Android wiring.
- `rdma-types`, `:plugin:*`, `rdma-annotation` — unchanged.

See also: [architecture.md](architecture.md), [codegen.md](codegen.md).
