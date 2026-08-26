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
