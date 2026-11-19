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

The bridge is how the C++ runtime talks to kernel code. It is the one place where Android
and iOS genuinely differ; on the plugin side (Kotlin/JS in Hermes) nothing changes between
platforms.

## Two backends, one contract

| | Android | iOS |
|---|---|---|
| kernel runtime | JVM / ART | Kotlin/Native |
| C++ → kernel | JNI (`Call*Method` through cached `jmethodID`s) | function-pointer C ABI (registry) |
| kernel → C++ | JNI exports (`Java_…`) | real `extern "C"` symbols, imported through cinterop |
| object handle | `jobject` (`NewGlobalRef`) | `void*` (`StableRef`) |
| string | `jstring` | `const char*` |
| runtime type of an object | `typeIdOf(obj)` generated per module | the same |

The shared C++ core (`common/RdmaRuntime.cpp`, `RdmaRendezvous.cpp`, `RdmaProxyCore.cpp`,
`RdmaBlocks.cpp`) is compiled for both platforms; only the leaves differ: on Android
`RdmaCompose.cpp`, `ListHandle.cpp`, `MapHandle.cpp`, `RdmaJni.cpp`; on iOS
`ios/RdmaComposeCAbi.cpp`, `ios/RdmaCollectionsCAbi.cpp`.

Every host function follows the same shape: unpack `this` and the arguments (a tag check
and a `static_pointer_cast` on the `NativeState`, values by type or
[descriptor](type_descriptors.md)), call the kernel, convert the result, and turn a kernel
exception into a JS error ([exceptions.md](exceptions.md)).

## The function registry (iOS)

JNI is dynamic: methods are resolved by name at runtime. A Kotlin/Native **framework**
cannot export C symbols for `@CName` functions — it exports them only as Objective-C
methods — so the iOS bridge cannot link against named Kotlin symbols. The boundary is
inverted into a function-pointer registry (the mechanism zipline and redwood use):

- Kotlin registers every C-compatible function at startup: `@EagerInitialization` +
  `staticCFunction(::fn)` → `rdma_registerFunction(name, fn)`.
- C++ looks functions up by name once (`rdma_lookupFunction`) and calls them by address.
  The generated headers declare `inline` wrappers with the function names, so call sites
  read like direct calls.

| JNI | C ABI |
|---|---|
| `jobject` | `void*` (a `StableRef`) |
| `NewGlobalRef` / `DeleteGlobalRef` | `StableRef.create` / `rdma_disposeStableRef` |
| `jstring` | `const char*` (`rdmaToKString` / `rdmaStringToCStr`) |
| `CallObjectMethod` | `rdma_<mod>_<Class>_<member>(handle, …)` |

`@SymbolName` is internal and a `staticLib` variant crashed `CAdapterCodegen`; the registry
sidesteps symbol export entirely and keeps the stock static framework.

An exception cannot cross a C frame. Kotlin shim functions catch their own exceptions and
record them (thread-local); C++ reads them after the call and throws a JS error.

## The Composer

The Compose compiler lowers `@Composable fun Text(text)` to
`Text(text, $composer, $changed)`.

- **Android**: the composer crosses as a `jobject`; the Composer proxy calls it through
  JNI.
- **iOS**: `Composer` is not a C type, so it never crosses. `RdmaComposeHost` keeps the
  current composer in a Kotlin global and every composer operation and widget entry reads
  it (`rdmaGetCurrentComposer()`); the C++ proxy is stateless.

This is invisible to plugin code. See [compose_protocol.md](compose_protocol.md).

## Calls into the plugin

Native code calls plugin JS through a few entry points, routed to the Hermes thread
([threading.md](threading.md)):

| Entry point | Used for | Routing |
|---|---|---|
| `nativeInvokeComponent`, `nativeInvokeScopeBlock` | composing plugin content | rendezvous (synchronous) |
| `nativeInvokeCallback` / `nativeInvokeLambda` | callbacks, service lambdas | posted to the low-priority queue |
| `nativeInvokeContinuation(Error)` | `suspend` completion | posted to the low-priority queue |
| slot / vtable invoke | hotfixed functions, overrides | synchronous from any thread ([hotfix_slots.md](hotfix_slots.md)) |

## State writes

`MutableState` belongs to the native Compose runtime; the plugin reaches it through a state
proxy. Creation and reads during composition run on the UI thread through the rendezvous.
Writes differ:

- **Android**: a direct, thread-safe JVM write from the Hermes thread; the snapshot system
  turns it into a recomposition.
- **iOS**: the write is posted to the main queue. Writing a Kotlin/Native `MutableState`
  directly on the Hermes thread bypasses the snapshot observation, and no recomposition
  happens.

A write cannot use the blocking rendezvous, because outside composition the UI thread is
not waiting at the boundary. Details: [compose_protocol.md](compose_protocol.md#state).

## iOS: the memory model

Kotlin/Native's memory manager shares the heap across threads (JVM-like), with no
freezing, so the data path stays direct on the Hermes thread as with JNI.

## Services in Swift

On iOS a host service provider may be written in Swift: Kotlin/Native exports the
`KernelServiceProvider` interface as an Objective-C protocol; the app conforms to it and
registers with `KernelServices.register(...)`. Parameter conversion (strings, numbers,
lists, lambdas ↔ blocks) is automatic.
