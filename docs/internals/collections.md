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
# Collections

How `List`/`MutableList` and `Map`/`MutableMap` values cross. User-facing semantics:
[types.md](../guide/types.md). Terms: **native list/map** — the collection owned by the
kernel; **proxy** — a JS object whose storage methods call the native collection;
**materialize** — copy a JS collection into a native one; **morph** — turn the same JS
object into a proxy.

## Model

A plugin collection is JS-owned until its first crossing. At the first crossing it is:

1. **classified** — by a generated Kotlin/JS helper in the guest bridge, not by C++
   heuristics: `is ArrayList` / `=== emptyList()` for lists; `RDMA.mapKind` for maps
   (`LinkedHashMap`, `HashMap`, `emptyMap`);
2. **materialized** into a native `ArrayList` / `LinkedHashMap`, element by element,
   according to the element [descriptor](type_descriptors.md) — recursively for nested
   collections and `@RDMA` references (a JVM collection on Android, a Kotlin one held by a
   `StableRef` on iOS). List elements are read through the public `toArray()`, map
   entries through `RDMA.mapToEntries`, never through stdlib internals;
3. **morphed** — the same JS object gets the native state and its prototype is swapped to
   the shared collection prototype (`setPrototypeOf`); its stale JS backing storage is
   dropped (it would keep element proxies, and their kernel objects, alive).

From then on the native collection is the source of truth. A collection created in the
kernel is wrapped in a new proxy when it crosses (`Object.create` on the prototype, no
materialization). Plugin-created and kernel-returned collections therefore behave the
same.

Crossing points: function and constructor arguments, mutable property setters, elements of
another collection or `@RDMA` object, state values, callback arguments, `suspend` results.
If any nested value is unsupported, the whole crossing fails and nothing is partially
morphed.

`emptyList()`/`emptyMap()` materialize into a native immutable empty collection; the JS
singleton is not morphed. `ArrayDeque`, `SubList` views and custom implementations are
rejected with `RdmaException`.

## Prototype chain

```
proxy ─▶ NativeListProto (host functions) ─▶ ArrayList.prototype ─▶ AbstractMutableList …
```

Kotlin/JS checks interfaces as `obj[iface.Symbol] === true` and classes with
`instanceof`; both follow the prototype chain, so `is List`, `is MutableList`,
`is ArrayList`, `as MutableList` hold and every stdlib function works on a proxy.

- The stdlib prototypes and the compile-mangled method names are read once per runtime
  from the guest bridge hook `RDMA.collectionPrototypes()` (live samples of `arrayListOf()`,
  `linkedMapOf()`, an entry, the views). Old bundles without the hook fall back to
  `RDMA.listMethodNames` and an `Object.prototype` parent.
- The prototype carries every storage method under its compile-mangled names and under
  its plain names (`get`, `set`, `add`, `removeAt`, `sizeImpl`, …, and a `size` accessor)
  for old `dynamic` bundles.
- **Shadowing guard**: the only stdlib methods that read `ArrayList`'s JS backing array
  are its own storage methods, and the proxy prototype shadows all of them. When the
  prototype is built, every own method of `ArrayList.prototype` must be shadowed or on a
  known-safe list (`checkIsMutable`, `build`, `trimToSize`, `ensureCapacity`, iterator
  field accessors); anything else is reported at startup. The same holds for maps.
- Not shadowed — inherited from the stdlib and built on the storage methods:
  `listIterator`, `equals` with a non-proxy argument, `subList` views of plugin lists,
  `sort`, `sortWith`, the rest of `AbstractMutableList`.
- `contains`, `indexOf`, `lastIndexOf`, `equals`, `hashCode`, `toString` run natively
  (one crossing).

**State-backed collections** (`mutableStateListOf`/`mutableStateMapOf`) use sibling
prototypes whose functions first register the composition read; plain collections pay
nothing for this.

## Maps

`NativeMapProto` sits over `LinkedHashMap.prototype` (a kernel `HashMap` uses the same
prototype; `is HashMap` holds because `LinkedHashMap` extends it in Kotlin/JS). Keys must
be primitives, `String` or `@RDMA` references; values any boundary type.

`keys`, `values`, `entries` are live views with their own prototypes over the stdlib
`Set`/`Collection` prototypes: `size`, `contains`, `remove`, `clear` act on the native
map; `add` throws, as in Kotlin. Entries are `Map.Entry` objects whose `setValue` writes
through. Views keep the plain surface (`size()`, `get(index)`) for old bundles.

## Iteration (experimental)

`iterator()` and `toArray()` read the whole native collection in **one** crossing and
iterate that snapshot in JS: `map`/`forEach`/`for` over N elements cost one crossing, not
2N. `iterator.remove()` removes from the native collection. A modification made *during*
an iteration — by the loop body or by another thread — is not seen by that iteration
(Kotlin's `ArrayList` would throw `ConcurrentModificationException` there; Compose's
`SnapshotStateList` iterates a snapshot as well). Map views iterate a snapshot the same
way. `get(i)` in an index loop is one crossing per call. A live iterator remains
implementable behind the same entry if the snapshot turns out wrong.

## Read-only

A plugin-created read-only list keeps its `isReadOnly` flag on the instance; mutators call
the stdlib `checkIsMutable` first, so it throws even after a cast to `MutableList`. A
kernel list is mutable unless the native list rejects the mutation; the native exception
reaches JS as `RdmaException`.

## Platforms

- **Android** — the proxy owns a JNI global ref to the native collection; operations are in
  `ListHandle.cpp` / `MapHandle.cpp`.
- **iOS** — the proxy owns a `StableRef`; operations are in `ios/RdmaCollectionsCAbi.cpp`.

Classification and morphing are shared.

## Tests

`list-behavior.sh`, `map-behavior.sh` and their iOS twins cover: index access and
mutation, `add`/`removeAt`/`removeRange`/`clear`, iteration with stdlib operations,
`equals`/`hashCode`/`toString`/`sort`, `subList` before crossing and the fail-fast when a
`SubList` crosses, read-only lists, `EmptyList`, nested collections, map views and entries,
`HashMap`/`LinkedHashMap`/`emptyMap`, mutation visible in the other runtime, and collections
in returns, properties and callback arguments.
