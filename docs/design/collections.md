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
# Collections: List and Map Bridge

This document defines how `List`/`MutableList` and `Map`/`MutableMap` values cross
the kernel/plugin boundary.

`suspend` is out of scope for this version.

## Terms

- **Native list** — the `ArrayList` owned by the kernel.
- **Native map** — the `LinkedHashMap`/`HashMap` owned by the kernel.
- **Proxy list** — a JS `ArrayList` whose storage methods call the native list.
- **Proxy map** — a JS `LinkedHashMap`/`HashMap` whose storage methods call the native map.
- **Crossing** — the first transfer of a collection across the runtime boundary.
- **Materialize** — copy a JS collection into a native collection.
- **Morph** — replace a JS object's storage methods with calls to a native collection.
- **Storage methods** — the collection methods that read or write the backing storage.

## Model

A list is JS-owned until its first crossing.

At the first crossing:

1. Classify the value.
2. Materialize it into a native `ArrayList`.
3. Morph the same JS object into a proxy list.

From then on, the native list is the source of truth. All JS operations go through
the proxy list's methods.

A list created in the kernel is wrapped in a new proxy list when returned.
Plugin-created and kernel-returned lists therefore behave identically from JS.

Crossing points are:

- `@RDMA` function argument;
- `@RDMA` constructor argument;
- mutable `@RDMA` property setter;
- element of another `List`, `Map`, or `@RDMA` object.

Nested lists are classified, materialized, and morphed recursively. If any nested
value is unsupported, the whole crossing fails.

## Supported implementations

Only `ArrayList` is supported.

`EmptyList` is special: it is materialized into a native immutable empty list. The
JS singleton is not morphed. Both sides therefore always see an empty list, and
mutation throws.

`ArrayDeque`, `SubList`, and custom `List`/`MutableList` implementations are
rejected with `RdmaException`.

## Classification

Classification is done by a generated Kotlin/JS helper, not by heuristics in C++.

- `value is ArrayList<*>` → `ArrayList`.
- `value === emptyList<Any?>()` → `EmptyList`.
- Anything else → `RdmaException`.

## Materialization

For `ArrayList`, elements are read from the backing JS array (`array_1`). If the
backing array is missing, the crossing fails.

Elements are materialized according to their declared type descriptor, including
nested lists and `@RDMA` references.

Kernel-returned lists are not materialized: the existing native list is wrapped in
a new proxy list.

## Proxy list

A proxy list is an `ArrayList` instance whose storage methods are replaced with
wrappers around the native list.

Wrappers are discovered on the prototype chain by name prefix and installed on the
instance:

| Prefix | Native operation |
|---|---|
| `get_size_` | size |
| `get_` | get |
| `set_` | set |
| `add_` | add element (1 argument) or add at index (2 arguments) |
| `removeAt_` | remove at index |
| `removeRange_` | remove range |
| `clear_` | clear |
| `toString` | native string |
| `toArray`, `toArray_` | native snapshot |

If a required method is missing, the crossing fails with `RdmaException`.

Patched mutators call the original `checkIsMutable` before mutating the native list.

The following methods are not patched. They delegate to the storage methods:

`iterator`, `contains`, `indexOf`, `lastIndexOf`, `equals`, `hashCode`, `subList`,
`listIterator`, `addAll`, `removeAll`, `retainAll`, `remove(element)`, `sort`,
`sortWith`.

Direct access to `array_1`, `asJsArrayView`, `asJsReadonlyArrayView`, or other
ArrayList internals is outside the contract.

## Read-only

For plugin-created lists, the `isReadOnly` flag is preserved. A read-only
`ArrayList` throws on mutation even after a cast to `MutableList`.

For kernel-returned lists, the native list decides. The proxy list is mutable; if
the native list rejects a mutation, the exception propagates to JS.

## Identity and equality

`==` and `equals` are structural and work through the proxy list.

`===` between different proxy lists is not guaranteed, even when they wrap the same
native list.

There is no identity API in this version.

## Errors

Unsupported implementations and missing storage methods fail at the first crossing.

Native mutation errors propagate to JS as `RdmaException`.

A failed crossing exposes no partially morphed state to the plugin.

## Platforms

- **Android** — JNI. The proxy list owns a global `jobject` reference to the native
  list.
- **iOS** — C ABI. The proxy list owns a `StableRef` handle to the native list.

The JS-side classification and morphing logic is shared by both platforms.

Kernel-returned lists use a plugin-side factory to create the JS `ArrayList` before
morphing.

## Non-goals

- `ArrayDeque`, `SubList`, and custom `List` implementations.
- Crossing a `SubList` view.
- Direct access to Java/Kotlin collection internals.
- Proxy identity or proxy caching.
- suspend.

# Map Bridge

`Map`/`MutableMap` values cross the boundary with the same materialize-then-morph
model as `List`. Keys must be a primitive, `String`, or an `@RDMA` reference; values
may be any allowed type, including nested `List`/`Map`/`@RDMA`.

## Model

A map is JS-owned until its first crossing. At the first crossing the value is
classified, materialized into a native map (`LinkedHashMap`), and the same JS object
is morphed into a proxy map. From then on the native map is the source of truth.

A map created in the kernel is wrapped in a new proxy map when returned.

Crossing points are the same as for `List`: function/constructor arguments, mutable
property setters, and elements of another `List`/`Map`/`@RDMA` object. Nested maps
and lists are classified, materialized, and morphed recursively.

## Supported implementations

- `LinkedHashMap` (produced by `mapOf`, `linkedMapOf`, `mutableMapOf`) — kind 0.
- `HashMap` (produced by `hashMapOf`) — kind 1.
- `emptyMap` — kind 2; materializes into a native immutable empty map; the JS
  singleton is not morphed.

Any other `Map`/`MutableMap` implementation is rejected with `RdmaException`.

## Classification and materialization

Classification is done by the generated Kotlin/JS `RDMA.mapKind` classifier, and
entry extraction by `RDMA.mapToEntries` (a JS array of `[key, value]` pairs). This
keeps the C++ runtime independent of the Kotlin/JS `HashMap`/`LinkedHashMap`
internal layout, which is version-specific.

Materialization builds a native `LinkedHashMap` (JVM `LinkedHashMap` on Android, a
Kotlin `LinkedHashMap` held via `StableRef` on iOS), converting each key and value
according to its type descriptor.

## Proxy map

A proxy map's storage methods are replaced with wrappers around the native map.
The surface includes `get`, `set`/`put`, `remove`, `clear`, `containsKey`, `size`,
`isEmpty`, `putAll`, `forEach`, and the `keys`/`values`/`entries` live views.

`keys`, `values`, and `entries` are live views: reads and iteration reflect the
current native map, and mutations through `keys`/`values` mutate the native map.

## Verification

Covered cases:

- `get`/`set`/`remove`/`clear`/`containsKey`/`size`/`isEmpty`;
- `keys`/`values`/`entries` iteration and mutation;
- `Map<String, String>` (header maps), `Map<Int, List<@RDMA>>` (nested values);
- mutation in one runtime visible in the other;
- `emptyMap`, `LinkedHashMap`, `HashMap`, and fail-fast on unsupported implementations;
- Android and iOS end-to-end tests.

## Verification

Covered cases:

- `get`, `size`, `set`, and index access;
- `add(element)`, `add(index, element)`, `removeAt`, `remove(element)`,
  `removeRange`, `clear`;
- `iterator`, `for`, `map`, `filter`, `forEach`;
- `equals`, `hashCode`, `toString`, `toArray`, `sort`;
- `subList` before crossing; fail-fast when a `SubList` crosses;
- read-only plugin-created lists;
- kernel-returned read-only and mutable lists;
- `EmptyList`, nested lists, and fail-fast on unsupported implementations;
- Android and iOS end-to-end tests.
