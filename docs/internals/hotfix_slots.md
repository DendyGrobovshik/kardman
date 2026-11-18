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
# Hotfixes and slots

A hotfix replaces a kernel symbol's behaviour on installed apps: a dynamic release ships the
changed symbols as **polyfills** — JS implementations in an `F` bundle. Invariant: **a
hotfixed symbol runs its new version everywhere** — for plugins, other polyfills and native
call sites alike ([runtime_rework.md §2.7](../design/runtime_rework.md#27-hotfix-invariant)).
The versioning model: [compatibility.md §6](../design/compatibility.md).

## Polyfills

The release CLI computes the dirty subgraph of a change (the symbol plus its private
closure) and `RdmaPolyfillMaterializer` emits it as Kotlin/JS, compiled into an `F` bundle
keyed by the host version. For every top-level function the bundle registers:

```js
RDMA.<name> = fn;                       // JS callers (old hosts understand only this)
rdmaInstallSlot("<fqn>", fn);           // native callers, if the host has RDMA.installSlot
```

Plugins call kernel functions through `RDMA.<name>`, so registering the function replaces it
for them. Native callers need a slot.

## Slots

Every slotted kernel function gets, from `RdmaSlotTransformer`, a private static slot cell
(created from its FQN and its parameter/result [descriptors](type_descriptors.md)) and a
prologue:

```kotlin
if (cell.blockId != 0L) {
    val r = rdmaSlotInvoke(cell, arrayOf(p0, p1, …))
    if (rdmaSlotHandled(r)) return r as T
}
// original body
```

Not hotfixed, that is a static load, a volatile load and a predictable branch.
`RdmaSlotCell`, `rdmaSlotInvoke` and `rdmaSlotHandled` are generated into each kernel
module's package by the kernel Gradle plugin (`RdmaSlots.kt`; the native actuals in
`RdmaSlotsNative.kt` call the app's generated JNI exports on Android and the runtime's
`RdmaSlots` on iOS), so a kernel module needs no runtime dependency.

- A cell registers itself with the runtime by FQN (C++ registry on Android, `RdmaSlots` on
  iOS); `RDMA.installSlot(fqn, fn)` registers `fn` as a block and writes the cell's
  `blockId`. Either may happen first.
- `rdmaSlotInvoke` runs the JS function through `rdmaCallJsBlocking`: directly on the Hermes
  thread, through the rendezvous on the UI thread, through the low queue with a 5 s timeout
  on any other thread ([threading.md](threading.md#background-threads--js)). Arguments and
  the result are converted by the cell's descriptors; a JS exception is rethrown as
  `RdmaException`.

Slotted today: top-level `@RDMA` functions that are not `@Composable`, not `suspend` and
have no function-typed parameter or result.

## Release gate

So that no hotfix is partial, `RdmaSymbolIndexer` records a `hotfixBlocker` for every
declaration that cannot be slotted yet (`suspend`, `@Composable`, function types, class
members, properties). A dynamic release whose polyfills **modify** such a symbol is rejected
with the symbol and the reason (`RdmaReleaseCli.checkHotfixable`); the change needs a static
release. **Added** symbols are not affected: older hosts have no native callers of them.
The gate relaxes as coverage grows.

## Not covered yet

Slots for `suspend` functions (the reverse async path), for functions with function types
(kernel → JS lambda marshalling), for widgets (a JS composable inside a kernel-driven
composition), and class hotfixes (the whole class moves to JS, with native shells —
[runtime_rework.md §6.5](../design/runtime_rework.md#65-class-hotfix--whole-class-moves-to-js-stage-4b-sketch)).
Each needs its own design; see the backlog
([runtime_rework.md §11](../design/runtime_rework.md#11-backlog)).
