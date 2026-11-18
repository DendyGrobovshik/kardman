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
# `suspend`

A kernel `@RDMA suspend fun` (top-level or a method) is called from the plugin as an
ordinary `suspend` function. Why it is built this way:
[design/suspend.md](../design/suspend.md).

```
plugin: fetch(url)                       (suspend inline stub)
  → suspendCoroutine { cont -> RDMA._fetchAsync(RDMA.registerContinuation(cont), url) }
  → kernel: _fetchAsync launches fetch(url), then completeResult / completeError
  → nativeInvokeContinuation(id, result) → low queue → cont.resume(result)   (Hermes)
```

## Kernel side

The kernel compiler records the function with its logical result type (the `Continuation`
parameter stripped) and generates a plain wrapper per function or method
(`widget-kotlin/RdmaSuspendBridge.kt`):

```kotlin
fun _fetchAsync(completionId: Long, url: String) {
    RdmaSuspendScope.launch {
        try { completeResult(completionId, fetch(url)) }
        catch (t: Throwable) { completeError(completionId, t) }
    }
}
```

`RdmaSuspendScope` is framework-owned (`SupervisorJob() + Dispatchers.Default`); the body
chooses its real dispatcher with `withContext`. The bridge exposes it as
`RDMA._<name>Async` (methods: `RDMA._<Class>_<m>Async(id, self, …)`).

## Completion

- `RDMA.registerContinuation(cont)` registers the continuation as a block and returns its
  id; `_<name>Async` registers the result [descriptor](type_descriptors.md) for that id.
- `completeResult` / `completeError` call `nativeInvokeContinuation` /
  `nativeInvokeContinuationError`, which post to the **low** queue, so a resume never runs
  in the middle of a composition.
- The result is converted by the registered descriptor (primitives, `@RDMA`, `List`/`Map`,
  nested). After the resume the block and the descriptor are freed.
- An error is serialized like any exception ([exceptions.md](exceptions.md)) and resumes the
  continuation with it.

## Limits

- `suspendCoroutine` is not cancellable: cancelling the plugin coroutine does not cancel the
  kernel call.
- `suspend` methods cannot be `open`; the plugin cannot pass its own `suspend` functions to
  the kernel.
- `suspend` functions are not hotfixable yet (no slot): a native caller would need to
  suspend on a JS result ([hotfix_slots.md](hotfix_slots.md)).
