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
# Boundary types

Every type in an `@RDMA` declaration — properties, parameters, results, and anything
nested inside a `List`, `Map` or function type — must be one of the types below. A
violation is a compile error in the kernel.

| Type | Crosses | Where the value lives |
|---|---|---|
| `Int`, `Long`, `Float`, `Double`, `Boolean`, `String` (and nullable) | by copy | a copy on each side |
| `@RDMA` enum | by copy, as the entry name | the plugin holds the name string; the kernel rebuilds the entry |
| `@RDMA` class | by reference | in kernel memory; the plugin holds a proxy |
| `@RDMA` companion `val` | by reference | a kernel singleton |
| `List<T>` / `MutableList<T>` | by reference | in kernel memory after the first crossing |
| `Map<K, V>` / `MutableMap<K, V>` | by reference | in kernel memory after the first crossing |
| function type | by registration | the body stays in the plugin; the kernel holds a reference |
| `Unit` | — | results only |

## Semantics

- **Primitives** are copied. `Long` crosses with all 64 bits: in the plugin it is
  Kotlin's `Long` object, never a JS number, so `Long.MAX_VALUE` round-trips unchanged.
- **`@RDMA` classes** are reference types. The object is created and kept in the kernel;
  properties and methods reached through the proxy act on the kernel object. There is no
  identity across runtimes: two crossings of one object give two proxies, and `===`
  between them is not guaranteed.
- **Enums** cross as the entry name. In the plugin `HttpMethod.GET` *is* the string
  `"GET"`, so `when` compares strings without calling the kernel; `.ordinal` and
  `valueOf` ask the kernel, which rejects an unknown name. A `when` without `else` gets one
  that throws, because a newer kernel may add entries.
- **`List`** — `ArrayList` and `emptyList()` cross. A list created in the plugin is copied
  into a native list on its first crossing and from then on *is* a view of it: changes on
  either side are visible on the other. A read-only list stays read-only. Other
  implementations (`ArrayDeque`, `subList` views, custom lists) are rejected with
  `RdmaException`. Iterating a kernel list reads it once and then iterates that snapshot
  (experimental).
- **`Map`** — `LinkedHashMap` (`mapOf`, `mutableMapOf`), `HashMap` (`hashMapOf`) and
  `emptyMap()` cross, with the same sharing as lists. Keys must be primitives, `String`
  or `@RDMA` types; values may be any boundary type. `keys`/`values`/`entries` are live
  views.
- **Function types** — the plugin's lambda is registered and the kernel gets a reference
  to it; calling it from the kernel runs the lambda in the plugin asynchronously. Its
  parameter types must be boundary types. Lambda *results* and properties of a function
  type are not supported yet.

How collections work inside: [collections.md](../internals/collections.md).

## State values

Every boundary type except `Unit` can be a `mutableStateOf` value: primitives (nullable
included), enums, `@RDMA` references, `List`, `Map`.

- **The static type must be concrete** — `mutableStateOf<Any?>(…)` or a `dynamic`
  initial value is a compile error.
- **The type is fixed at creation.** Writing a value that does not fit throws
  `RdmaException`. `null` is accepted on a nullable state and rejected otherwise.
- **Mutation semantics match plain Compose.** Mutating a plain `List`/`Map` held in
  `mutableStateOf` in place does not recompose; assign a new one.
  `mutableStateListOf`/`mutableStateMapOf` recompose on every mutation.

## Default parameter values

`@RDMA` functions, methods and constructors may declare defaults
(`fun foo(a: Int, b: Int = 5)`):

- **Trailing only** — once a parameter has a default, every later parameter needs one.
  This is what makes adding a defaulted parameter purely appending.
- **Evaluated in the kernel** — the plugin forwards only the arguments the caller wrote;
  the kernel (or a hotfix polyfill) fills the rest. The default expression must also
  compile in the JS polyfill.
- **Lambda defaults are empty-only** — a lambda parameter may default only to `{}`.
- **No `private` references** in a default expression; `internal`, public, companion and
  object references are fine.

Adding a trailing defaulted parameter is forward- and backward-compatible. Reordering,
removing, renaming, retyping or inserting a parameter elsewhere is rejected at release
time (see [compatibility.md](../design/compatibility.md)).

## Not supported (yet)

- Composite value types without `@RDMA` (data classes, `Array`).
- Generics other than `List` and `Map`.
- Overloaded functions and methods, secondary constructors, extension functions.
- Enums inside `List`/`Map`, `values()`/`entries`.

The full list: [limitations.md](limitations.md).
