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
# Blocks and lambdas

How native code holds and calls plugin JS functions.

## Registration

A plugin function passed to the kernel is registered with `RDMA.registerBlock(fn, pluginId)`
and crosses as a numeric **block id**. The registry (`common/RdmaBlocks.cpp`) maps the id to
the `jsi::Function`, the plugin id (for error attribution) and, for typed callbacks, the
parameter [descriptors](type_descriptors.md). `registerBlock` returns the existing id if
`fn` already has one, so a lambda memoized by Compose keeps its id across recompositions.

## Holders

The kernel keeps a block alive through **holders**; each entry counts them.

| Block | Holder | Released |
|---|---|---|
| widget content lambda, widget callback, list of callbacks | `RdmaBlockRef` remembered in the widget's slot (`rdmaRemember*`) | `onForgotten`/`onAbandoned` (deterministic) |
| `updateScope` block | `ComposerScopeBlock` | finalization of the holder |
| `remember { jsValue }` | `JsValueHolder` | `onForgotten`/`onAbandoned` |
| effect body / dispose | effect observer | deterministic |
| callback passed to an `@RDMA` function or method | `RdmaFunctionN` | finalization of the holder |
| `suspend` continuation | the completion | on completion |

A holder retains its block when created, inside the JS task that registered the block; an
entry with no holder at the end of that task is freed. Widget lambdas are remembered by
block id: a stable plugin lambda yields the same Kotlin lambda (the widget can be
skipped); a replaced one is released at once — so a kernel that keeps a lambda past its
composition must read it through `rememberUpdatedState`. A call to a released block does
nothing (a diagnostic is in the backlog).

## Release

A release never touches the JS runtime: it enqueues the id on the same FIFO low-priority
queue as callback invocations, so a call posted before the holder became unreachable runs
first. Only the Hermes thread removes entries and destroys `jsi::Function`s. Ids are
monotonic and never reused. Finalization: Android uses a `PhantomReference` queue drained
by a daemon thread (`minSdk` 24 has no `Cleaner`); iOS uses `createCleaner`.

## Calling a block

Callbacks are fire-and-forget: `nativeInvokeCallback`/`nativeInvokeLambda` post the call to
the low-priority queue and return; arguments are converted by the block's descriptors on the
Hermes thread; a JS exception goes to the global uncaught handler
([exceptions.md](exceptions.md)). Content and scope blocks are called synchronously through
the rendezvous ([threading.md](threading.md)).
