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
# Design: `suspend` across the boundary

How it works: [internals/suspend.md](../internals/suspend.md).

## Problem

A kernel `suspend` function must be callable from the plugin as a `suspend` function — the
plugin awaits the result instead of passing a callback, and exceptions resume its coroutine.

## Decisions

- **A kernel-side wrapper, not a raw `Continuation`.** A JVM `suspend fun` lowers to
  `foo(a, Continuation<R>)`; constructing a `Continuation` from C++ is not stable, especially
  on Kotlin/Native. The kernel compiler generates a plain `_<name>Async(completionId, args…)`
  that launches the call in a framework scope and completes the plugin's continuation. CPS is
  left to the Kotlin compiler on both sides; the only callback is in generated code.
- **A dedicated, typed completion channel.** The generic callback path is type-erased and
  could not return `List`/`Map`/`@RDMA` values exactly like a synchronous return; the
  completion carries the result's [descriptor](type_descriptors.md).
- **Completion on the low-priority queue**, so a resume never runs in the middle of a
  composition.
- **The kernel picks the dispatcher.** The framework only owns the launching scope; the body
  moves to IO with `withContext`, as in any Kotlin code.

## Deferred

- Cancellation: `suspendCoroutine` is not cancellable, so cancelling the plugin coroutine
  does not cancel the kernel call.
- Overriding `open suspend` methods; plugin `suspend` functions passed to the kernel.
- Hotfix slots for `suspend` functions (a native caller would have to suspend on a JS
  result).
