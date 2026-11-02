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
# Boundary Types (specification)

`@RDMA` marks the API surface that the **plugin** (Kotlin/JS, guest) may use from the
**kernel** (JVM, host). It is the single boundary between the two runtimes: the plugin
may only touch things that are `@RDMA` (a class or a top-level function) or one of the
allowed types below.

## Allowed types

| Category | Crossing | Where the value lives |
|---|---|---|
| Primitives (`Int`, `Long`, `Float`, `Double`, `Boolean`, `String`) | by copy | a copy on each side |
| `@RDMA` class | by reference (handle) | object lives only in kernel memory; plugin holds a handle |
| `@RDMA` companion `val` (static) | by reference (handle) | singleton exposed as `RDMA.<class><Name>()` (e.g. `RDMA.alignmentCenter()`) |
| Function / lambda | registration + callback | body lives in the plugin; kernel holds an id |
| `List<T>` / `MutableList<T>` | special case (`ArrayList`) | native `ArrayList` in kernel memory after first crossing; plugin holds a proxy list |
| `Map<K, V>` / `MutableMap<K, V>` | special case (`LinkedHashMap`/`HashMap`) | native map in kernel memory after first crossing; plugin holds a proxy map |
| `Unit` | — | return type only |

## Semantics

- **Primitives** cross the boundary by copy. After the call, later mutations on one side
  are not visible on the other.
- **`@RDMA` class** is a reference type. The object is created and kept in the JVM
  (kernel); the plugin receives a proxy handle. Fields/methods are reached through the
  handle, so a mutation happens on the kernel-side object.
- **`List`** is a special case. Only `ArrayList` crosses. A list first created in the plugin
  is materialized into a native `ArrayList` on its first crossing (a JVM `ArrayList` on
  Android, a Kotlin `ArrayList` held via `StableRef` on iOS) and morphed into a proxy list.
  From then on the native list is the source of truth and all JS operations go through the
  proxy list. `EmptyList` materializes into a native immutable empty list. See
  [collections.md](design/collections.md).
- **`Map`** is a special case. Keys must be a primitive/`String`/`@RDMA`; values may be any
  allowed type (including nested `List`/`Map`/`@RDMA`). Only `LinkedHashMap`, `HashMap` and
  `emptyMap` cross. A map first created in the plugin is materialized into a native map on
  its first crossing and morphed into a proxy map; from then on the native map is the source
  of truth. `keys`/`values`/`entries` are live views backed by the native map. See
  [collections.md](design/collections.md).
- **Function / lambda** — a parameter or return type of a function crossing the boundary
  must itself be an allowed type (`@RDMA` or primitive). The lambda is registered in JS,
  the kernel receives its id and invokes it through JNI; a lambda's return value is
  marshaled exactly like a function's return value.
- **Unit** is a return-only type (void).

## Invariant

Every type in an `@RDMA` class (properties, methods) or an `@RDMA` function (parameters,
return) — including types nested inside function/lambda signatures — must be an allowed
type. A violation is a compile error.

## Not supported (yet)

- Composite value types: data classes without `@RDMA`, `enum`, `Array`.
- Generics, except the `List<T>` / `MutableList<T>` and `Map<K, V>` / `MutableMap<K, V>` special cases.
- `suspend` / async functions, overloaded functions/constructors, default parameter values.

## Base Compose protocol (plugin)

The plugin is a `@Composable` guest: the host kernel owns the Compose runtime and
exposes only a **base protocol** — the structural `Composer` methods needed for
`if`/`for`/`when`, `remember` (with keys), `changed`/skip and state.

The exact protocol is defined in `RdmaComposerProtocol.kt`
(`rdma-kernel-compiler-plugin`) and cross-checked against the resolved
`androidx.compose.runtime.Composer` interface at build time, so a compose-runtime
upgrade that changes the interface fails fast.

**Allowed for the plugin** (whitelist in `ComposeAllowlist.kt`):

| Symbol | Notes |
|---|---|
| `@Composable` | composable functions / lambdas |
| `remember { ... }` (with keys) | keys go through `changed` |
| `mutableStateOf` | rewritten to `rdmaMutableStateOf` |
| `var x by ...` (`getValue`/`setValue`) | state delegation |
| `SideEffect { ... }` | rewritten to `rdmaSideEffect` (kernel-hosted) |
| `DisposableEffect(keys) { ... }` | rewritten to `rdmaDisposableEffect` (kernel-hosted) |
| `LaunchedEffect(keys) { ... }` | rewritten to `rdmaLaunchedEffect` (kernel-hosted, `Dispatchers.Main`) |
| `rememberCoroutineScope()` | rewritten to `rdmaRememberCoroutineScope` |
| kernel widgets (`Text`, `Column`, `Button`, `TextField`, ...) | via `@RDMA` |

**Forbidden (compile error):** `derivedStateOf`, `snapshotFlow`, `movableContentOf`,
`produceState`, animations, and any `androidx.compose.*` symbol outside the whitelist.

The plugin compiler plugin rejects any call or import of a forbidden compose symbol
with an error of the form:

```
kernel doesn't support 'derivedStateOf' — the plugin is limited to the base Compose protocol (remember/mutableStateOf/widgets)
```
