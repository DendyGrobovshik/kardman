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
# Suspend Functions Across the Boundary

**Applies to:** Android (JNI) and iOS (C ABI)

## Purpose

A kernel `@RDMA suspend fun` (top-level or a method of an `@RDMA` class) can be called
from the plugin as an ordinary `suspend` function: the plugin awaits the result instead
of passing a callback.

```kotlin
// kernel
@RDMA
class HttpClient {
    suspend fun fetch(url: String): Response = withContext(Dispatchers.IO) { ... }
}

// plugin
val r = HttpClient().fetch("...")   // suspends, resumes when the kernel completes
```

## Model

The only async primitive today is a callback lambda: a `(R) -> Unit` param is registered
in JS, the kernel holds its id, and calling it posts back to the Hermes thread
([bridges.md](../bridges.md#async-callbacks-and-lambdas)). Suspend reuses that idea but
completes through a dedicated, **type-carrying** channel so that `List`/`Map`/`@RDMA`
results marshal exactly like the synchronous return path (the generic callback path
`wrapAny` is type-erased and cannot).

```
plugin: fetch(url) → rdmaFetch(url)
  → suspendCoroutine { cont -> RDMA._fetchAsync(RDMA.registerContinuation(cont), url) }
  → kernel: _fetchAsync launches the suspend body, then completeResult/completeError
  → nativeInvokeContinuation(id, result) → rdmaPostJs (low-priority) → cont.resume(result)
```

CPS is done by the compiler on both sides; the only callback lives in the generated bridge.

## Why a kernel-side wrapper, not a raw Continuation

A JVM `suspend fun` lowers to `Object foo(a, Continuation<R>)`; constructing a
`Continuation` from C++ is not stable (especially on Kotlin/Native). The kernel plugin
instead injects a plain async wrapper per suspend function/method, named `_<name>Async`:

```kotlin
// generated, invisible to the user
@RDMA
fun _fetchAsync(completionId: Long, url: String) {
    RdmaSuspendScope.launch {
        try { completeResult(completionId, fetch(url)) }
        catch (t: Throwable) { completeError(completionId, t) }
    }
}
```

`completionId` identifies the registered JS continuation. `RdmaSuspendScope` is
framework-owned (`SupervisorJob() + Dispatchers.Default`); the suspend body itself
chooses its real dispatcher via `withContext(...)`.

## Completion

- `RDMA.registerContinuation(cont)` registers the JS continuation and returns its id.
- `completeResult(completionId, value)` / `completeError(completionId, throwable)` call
  `nativeInvokeContinuation` / `nativeInvokeContinuationError`, which post to the
  **low-priority** queue, so a resume runs after composition, never mid-render.
- C++ keeps a `completionId → typeDescriptor` map (registered when `_<name>Async` is
  invoked), so the result is marshalled by type: primitives, `@RDMA` (`wrapAny` /
  `create<X>Wrapper`), `List`/`Map` (`createListHandle` / `createMapHandle`), nested.
- After the resume the id and its registered block are freed (`freeBlock`).

The plugin bridge is a single `suspendCoroutine`:

```kotlin
suspend fun rdmaFetch(url: String): Response =
    suspendCoroutine { cont -> RDMA._fetchAsync(RDMA.registerContinuation(cont), url) }
```

## Errors

Reuses [exception_propagation.md](exception_propagation.md): `completeError` serializes
the `Throwable` to `RdmaExceptionInfo` (`originalExceptionType`/`message`/`stack`/
recursive `cause`); the plugin calls `resumeWithException`, so `try/catch (e: Throwable)`
sees the original type name, message, stack and `cause` chain. The exception is a reborn
description, not a live object.

## Type model

- `RdmaFunctionInfo.suspend: Boolean` and `MethodInfo.suspend: Boolean`.
- `RdmaFunctionExtractor` / `RdmaClassExtractor` detect `IrSimpleFunction.isSuspend`,
  strip the `Continuation` parameter, and record the **logical** return type `R`.
- The validator accepts suspend functions/methods but rejects `suspend` on an `open`
  method (`isSuspend && isOpen`): overriding a suspend method from the plugin is a
  later step.
- Param/return types stay on the allowed-type list (primitives, `@RDMA`, `List`, `Map`,
  lambdas, `Unit`).

## Code generation

| Artifact | Change |
|---|---|
| `rdma-types` | `suspend: Boolean` on `RdmaFunctionInfo` and `MethodInfo` |
| `RdmaFunctionExtractor` / `RdmaClassExtractor` | detect `isSuspend`, strip `Continuation` |
| `CppGenerator` / `CAbiCppGenerator` | inject `_<name>Async` wrapper + `RDMA._<name>Async` host function |
| `RdmaPluginTransformState.buildFunctionBridge` | emit `suspend fun rdma<Name>(...) = suspendCoroutine { ... }` |
| runtime (`rdma-runtime`, `rdma-runtime-ios`) | `registerContinuation`, `freeBlock`, `nativeInvokeContinuation(Error)`, suspend scope |

## Threading

The suspend body runs on the kernel side on its own dispatcher; the framework only owns
the launching scope. Completion marshals to the Hermes thread via the low-priority queue.
The plugin-side coroutine stays single-threaded (Hermes), as today; only direct data-path
calls are legal inside it (see [features.md](../user_guide/features.md#coroutines-in-the-plugin)).

## Cancellation

v1 uses `suspendCoroutine` (non-cancellable): cancelling the plugin scope does not yet
cancel the in-flight kernel call. Propagating cancellation is a follow-up.

## Out of scope

- Overriding an `open` suspend method from the plugin.
- Plugin-defined `suspend` functions passed into the kernel.
