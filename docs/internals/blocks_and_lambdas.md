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

`RdmaFunctionN` wrappers of one block are equal (`equals`/`hashCode` by block id). A plugin
lambda passed to a method creates a new wrapper on every call; with a memoized lambda the
wrappers still compare equal, so a value holding it (an element of a kernel `Modifier`)
is equal across recompositions and Compose can skip.

## Release

A release never touches the JS runtime: it enqueues the id on the same FIFO low-priority
queue as callback invocations, so a call posted before the holder became unreachable runs
first. Only the Hermes thread removes entries and destroys `jsi::Function`s. Ids are
monotonic and never reused. Finalization: Android uses a `PhantomReference` queue drained
by a daemon thread (`minSdk` 24 has no `Cleaner`); iOS uses `createCleaner`.

## Calling a block

A block registers its parameter descriptors and, for a lambda with a result, its result
descriptor. The kernel holds it as an `RdmaFunctionN` (Android: created by the bridge; iOS:
generated glue), whose `invoke` depends on the result:

- **no result (`Unit`)** — fire-and-forget: the call is posted to the low-priority queue and
  returns; arguments are converted on the Hermes thread; a JS exception goes to the global
  uncaught handler ([exceptions.md](exceptions.md));
- **a result** — the caller waits: `rdmaCallJsBlocking`, routed like a hotfix slot (inline on
  the Hermes thread, rendezvous from UI, low queue with a 5 s timeout from other threads); the
  result is converted by the result descriptor and a JS exception is rethrown as
  `RdmaException`.

## Kernel lambdas in JS

A lambda-typed property getter, method result or function result crosses through
`rdmaWrapNativeFunction` (Android) / `rdmaWrapNativeFunctionCAbi` (iOS):

- an `RdmaFunctionN` (a plugin lambda that went through the kernel) returns the plugin's own
  function from the block registry, so identity holds;
- any other lambda becomes a JS host function owning a global ref / `StableRef` to it,
  released when Hermes collects the function. A call converts the arguments by the parameter
  descriptors, invokes the lambda on the Hermes thread (Android: `FunctionN.invoke` through
  JNI; iOS: `rdma_invokeFunction`), converts the result by the result descriptor and turns a
  kernel exception into a JS error. Content and scope blocks are called synchronously through
the rendezvous ([threading.md](threading.md)).
