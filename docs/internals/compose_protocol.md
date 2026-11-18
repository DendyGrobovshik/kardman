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
# Compose protocol

How a plugin composition drives the native Compose runtime.

## Model

There is one source of truth, in native: the slot table, the groups and the UI tree live in
the native Compose runtime (Jetpack Compose on Android, Compose Multiplatform on iOS).
Plugin composables run in JS, but every composer operation they perform
(`startRestartGroup`, `endRestartGroup`, `rememberedValue`, `changed`, …) is forwarded to
the native `Composer`. Kernel widgets compose natively. Values the plugin `remember`s live in
the native slot table as `JsValueHolder`s.

The plugin therefore gets only the **base protocol** — the structural `Composer` methods
needed for `if`/`for`/`when`, `remember`, `changed`/skipping and state — plus kernel widgets
and the bridged effects. Everything else in `androidx.compose.*` is rejected by the plugin
compiler (`ComposeAllowlist`).

## The Composer proxy

The plugin's compose-generated code calls `$composer.<method>_<mangled>(…)` on a JS object.
That object is a proxy over a shared prototype ([proxies_and_handles.md](proxies_and_handles.md));
its host functions call the native `Composer`.

- The method list is `RdmaComposerProtocol.baseProtocol` (kernel compiler plugin). The
  proxy (`RdmaComposerProxy.cpp`) is generated from it and checked against the resolved
  `androidx.compose.runtime.Composer` at build time, so a Compose upgrade that changes the
  interface fails the build.
- The mangled names are learned once per runtime from `RDMA.protocolMethodNames()` in the
  guest bridge, which calls every protocol method on an ES6 `Proxy` cast to `Composer`
  (and `MutableState`, `ScopeUpdateScope`) and records the names the compiler emitted.
- Android passes the composer as a `jobject`; on iOS it never crosses — the shim keeps the
  current composer in a global ([bridges.md](bridges.md#the-composer)).

## Flow

```
plugin main(): runRdmaApp { App() }
  → rdmaRunApp → RDMA.registerContent(fn)          (Hermes)
  → host: setContent { RdmaComposeHost.Content() } (UI)
  → nativeInvokeContent → rdmaCallJs → content(composerProxy)
  → App() runs in JS, composer ops forwarded to the native Composer
  → Text("x") → RDMA.composeText("x") → rdmaCallUi → compose<Text> entry → kernel Text()
  → Column { … } → the content lambda becomes a remembered block;
    the kernel widget calls it → nativeInvokeScopeBlock → JS
  → tap → nativeInvokeCallback (low queue) → JS lambda → state write → recomposition
```

A recompose scope's `updateScope` block is held by a `ComposerScopeBlock`; Compose calls it
to recompose just that part of the plugin tree.

## Widget entries

Each widget gets a generated entry that converts plugin arguments (block ids into
remembered lambdas, raw values into parameters, defaults through a mask) and calls the
widget ([kernel_codegen.md](kernel_codegen.md#widget-entries)). Lambdas are remembered by
block id (`rdmaRememberContent*`, `rdmaRememberCallback*`, `rdmaRememberCallbackList`), so a
stable plugin lambda keeps the widget skippable ([blocks_and_lambdas.md](blocks_and_lambdas.md)).

## State

`RDMA.mutableStateOf(v, desc)` creates a native `SnapshotMutableState` and returns a state
proxy carrying the value's [descriptor](type_descriptors.md).

- **Creation and reads** happen on the UI thread inside the active composition snapshot
  (`rdmaCallUi` during composition), so the read registers the dependency and a later write
  schedules a recomposition.
- **Writes**: Android writes directly from the Hermes thread (the JVM snapshot system is
  thread-safe); iOS posts the write to the main queue, because a Kotlin/Native write from
  another thread bypasses the snapshot observation. A write cannot rendezvous: outside
  composition the UI thread is not waiting at the boundary.
- A value that does not fit the descriptor throws `RdmaException`.
- `mutableStateListOf`/`mutableStateMapOf` create `SnapshotStateList`/`SnapshotStateMap`
  and return a state-backed collection proxy ([collections.md](collections.md)); its
  mutations recompose. A plain collection in `mutableStateOf` mutated in place does not.

## Effects

Effects are kernel-hosted for the same reason widgets are: the Compose runtime must drive
their lifecycle (`onRemembered`/`onForgotten`/`onAbandoned`), and a plugin `remember` value
is opaque to it. The plugin JVM pass rewrites each effect; the body stays in JS.

- `SideEffect { … }` → `rdmaSideEffect` → `RDMA.sideEffect(blockId)`: the kernel composes a
  real `SideEffect` that calls the block after every commit.
- `DisposableEffect(keys) { … }` → `rdmaDisposableEffect`: the guest wraps the body so its
  result is a plain JS object `{ dispose: fn }`; `RDMA.disposableEffect(keys, fn)` registers
  the block, converts the keys and composes `remember(keys) { RdmaDisposableEffectObserver(blockId) }`.
  `onRemembered` runs the body once, `onForgotten` calls `dispose`, `onAbandoned` releases the
  never-run block. Primitive keys compare faithfully; `Unit` is a stable sentinel; object and
  proxy keys always count as changed.
- `LaunchedEffect(keys) { … }` → `rdmaLaunchedEffect`: the same machinery; the body launches
  the `suspend` block on `Dispatchers.Main` and `dispose` cancels the job.
- `rememberCoroutineScope()` → `rdmaRememberCoroutineScope`: a remembered
  `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` cancelled by a
  `rdmaDisposableEffect` on a stable key.

## Exceptions in composition

A plugin `@Composable` that throws leaves its restart group open; Compose then fails with
`Start/end imbalance`, which a `try/catch` around `nativeInvokeContent` cannot repair. The
`RdmaException` reaches the host and the app crashes with the plugin error in its `cause`
chain ([exceptions.md](exceptions.md)).
