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
| `List<T>` | special case | list object lives in kernel memory, at least after first border transfer (initial plugin list may be pure plugin memory located list) |
| `Unit` | — | return type only |

## Semantics

- **Primitives** cross the boundary by copy. After the call, later mutations on one side
  are not visible on the other.
- **`@RDMA` class** is a reference type. The object is created and kept in the JVM
  (kernel); the plugin receives a proxy handle. Fields/methods are reached through the
  handle, so a mutation happens on the kernel-side object.
- **`List`** is a special case. A list first created in the plugin is materialized into a
  JVM `ArrayList` on its first crossing and is kept in kernel memory from then on.
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

- Composite value types: data classes without `@RDMA`, `enum`, `Array`, `Map`.
- Generics, except the `List<T>` special case.
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
| kernel widgets (`Text`, `Column`, `Button`, `TextField`, ...) | via `@RDMA` |

**Forbidden (compile error):** effects (`LaunchedEffect`, `DisposableEffect`,
`SideEffect`), `derivedStateOf`, `snapshotFlow`, `rememberCoroutineScope`,
`movableContentOf`, `produceState`, animations, and any `androidx.compose.*`
symbol outside the whitelist.

The plugin compiler plugin rejects any call or import of a forbidden compose symbol
with an error of the form:

```
kernel doesn't support 'LaunchedEffect' — the plugin is limited to the base Compose protocol (remember/mutableStateOf/widgets)
```
