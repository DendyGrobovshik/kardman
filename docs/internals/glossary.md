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
# Glossary

The words this documentation uses with a fixed meaning. Versioning terms (`H`, `G`,
polyfill kinds, plugin states) are defined in
[compatibility.md §1](../design/compatibility.md#1-terminology) and
[toolchain_compatibility.md](../design/toolchain_compatibility.md); they are linked
here, not redefined.

## Code and where it runs

- **Kernel** — how code is *organized*: the app's Kotlin modules that declare the
  `@RDMA` API (our `internal` module plus user modules `:kernel:user:<name>`).
- **Plugin** — Kotlin/JS code compiled to Hermes bytecode and loaded at runtime. It
  reaches the kernel only through `@RDMA` symbols.
- **Native** — where code *physically runs*: JVM/ART on Android, Kotlin/Native on iOS,
  plus our C++ runtime. The opposite is **JS** (the Hermes VM). Kernel code runs
  natively; plugin code runs in JS.
- **Host (app)** — the app binary: runtime, kernel and baked plugins.
- **Baked plugin** — a plugin bundle included in the app at build time instead of
  being downloaded from the store.
- **Bundle** — a unit of loadable JS code (`.hbc`): a plugin, a polyfill, or a shared
  dependency such as the Kotlin stdlib.

## The boundary

- **`@RDMA` symbol** — a kernel declaration exposed to plugins: a class, an enum, a
  companion `val`, a top-level function, a widget.
- **Crossing** — a call or a value passing between JS and native.
- **Bridge** — the generated code that implements the crossings of a kernel module:
  JSI + JNI on Android, C ABI + the function registry on iOS. See
  [bridges.md](bridges.md).
- **RDMA object** — the JS global `RDMA` installed by the runtime. It is the only door
  from plugin JS into native: one function per `@RDMA` symbol plus services
  (`registerBlock`, `installSlot`, `mutableStateOf`, …).
- **Call form (v1)** — the exact shape of a call on the RDMA object or on a proxy
  (names, argument order, descriptors). Old plugins keep using it, so it changes only
  additively. Table: [plugin_codegen.md](plugin_codegen.md#call-form-v1).
- **Kernel stub** — a plugin-side declaration generated from `rdma_api.json` that
  mirrors an `@RDMA` symbol; its inline body is the call form. See
  [plugin_codegen.md](plugin_codegen.md).
- **Handle** — a reference, held in one runtime, to an object that lives in the other
  runtime's memory. When JS holds a native object, the handle is a JNI global ref or a
  Kotlin/Native `StableRef`.
- **Proxy** — a JS object standing for a native object. It carries a `NativeState` with
  the handle and has a shared prototype, so it behaves like an ordinary Kotlin/JS object
  (collection proxies inherit the stdlib prototypes). See
  [proxies_and_handles.md](proxies_and_handles.md).
- **NativeState** — the JSI mechanism that attaches native data to a JS object. It owns
  the handle and releases it when the proxy is collected.
- **Block** — a plugin JS function registered under a numeric id so native code can call
  it.
- **Block holder** (`RdmaBlockRef` and friends) — the native object that keeps a block
  alive. The block is released when the holder is collected or, for composition-owned
  blocks, when it is replaced. See [blocks_and_lambdas.md](blocks_and_lambdas.md).
- **Type descriptor** — a string attached to every crossing point that loses the static
  type (state values, collection elements, callback arguments, `suspend` results, slot
  arguments). Grammar: `list:T`, `map:K:V`, `?`, `!`. See
  [type_descriptors.md](type_descriptors.md).
- **Materialize / morph** — copy a plugin collection into a native one on its first
  crossing / turn the same JS object into a proxy of it. See
  [collections.md](collections.md).

## Threads

- **Hermes thread** — the single thread that runs all JS. Invariant: no other thread ever
  executes JS.
- **UI thread** — the platform main thread; the native Compose runtime runs on it.
- **Sync call / async call / fire-and-forget** — the caller waits for a result / gets it
  later (`suspend`, callback) / never gets one (its errors go to the global handler).
- **Rendezvous** — while a thread waits for the other runtime, it keeps serving that
  runtime's requests, so synchronous UI ↔ Hermes calls never deadlock.
- **Top-level task** — work posted to the Hermes thread's loop and run when the thread is
  idle (posts, callbacks, timers).
- **Nested task** — work run on the Hermes thread while it waits inside a native call
  (rendezvous), on behalf of that call.

See [threading.md](threading.md).

## UI

- **Widget** — an `@RDMA @Composable` kernel function.
- **Compose protocol** — how a plugin composition drives the native Compose runtime: the
  Composer proxy, widget entries, groups, state. See
  [compose_protocol.md](compose_protocol.md).

## Versions and releases

- **Changelog** (`changelog.json`) — the append-only history of kernel releases; the
  single source of truth.
- **Manifest** (as in `compatibility.md`) — a fold of the changelog: a module's state at a
  version. Derived, never edited.
- **`rdma_api.json`** — a kernel module's API description, written by the kernel compiler
  and read by the plugin compiler to generate stubs.
- **`kernel_manifest_<platform>.json`** — the per-platform analysis of a kernel build
  (hashes, signatures, sources), the input of a kernel release. Despite its name it is
  not the manifest above.
- **`H`**, **generation `G`**, **polyfill**, **F-/R-bundle** — as in
  [compatibility.md](../design/compatibility.md) and
  [toolchain_compatibility.md](../design/toolchain_compatibility.md).
- **Hotfix** — replacing a kernel symbol's behaviour without an app update. Every caller,
  JS and native, runs the new version.
- **Slot** — the per-symbol cell checked at the top of a native `@RDMA` function. Filled by
  `installSlot`, it routes native callers to the polyfill. See
  [hotfix_slots.md](hotfix_slots.md).
- **Vtable** — the dispatch table that lets native code call a plugin's override of an
  `open` kernel class. See [subclassing_and_vtable.md](subclassing_and_vtable.md).
