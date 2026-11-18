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
# Design: collections across the boundary

How it works: [internals/collections.md](../internals/collections.md).

## Problem

`List` and `Map` are the most common values crossing the boundary. They must:

- behave as ordinary Kotlin collections in the plugin (`is List`, `map`, `forEach`, `for`,
  destructuring of map entries), whoever created them;
- stay shared: a change made in the kernel is visible in the plugin and vice versa;
- cost close to nothing per crossing.

## Decisions

- **The native collection is the source of truth after the first crossing.** A plugin
  collection is copied into a native one once (*materialize*) and the same JS object becomes
  a view of it (*morph*). Copying on every crossing would break sharing; keeping JS as the
  owner would force the kernel to call into JS for every read, from any thread.
- **Identity of a plugin collection is preserved** by morphing the same object instead of
  returning a new one: plugin code that kept a reference keeps working.
- **Proxies inherit the stdlib prototypes.** A proxy's prototype carries the storage methods
  and itself inherits `ArrayList.prototype` / `LinkedHashMap.prototype`, so Kotlin's
  `is`/`instanceof` checks and every stdlib function work without the plugin knowing.
  The alternative — wrapper classes in the plugin — would need the plugin compiler to
  rewrite every collection use and would still fail for values typed as `List`.
- **Classification in Kotlin/JS, not in C++.** The C++ runtime does not depend on the stdlib
  internal layout, which is version-specific; the guest bridge classifies and extracts.
- **Only `ArrayList`, `LinkedHashMap`, `HashMap` and the empty singletons.** Supporting
  arbitrary implementations would mean calling their JS methods from native code; rejecting
  them at the first crossing is explicit and cheap.
- **Snapshot iteration (experimental, D1).** A full iteration costs one crossing instead of
  two per element. Changes made during an iteration are not seen by it — the same as
  Compose's `SnapshotStateList`, where Kotlin's `ArrayList` would throw. A live iterator
  remains possible behind the same entry.
- **Guards against stdlib drift.** Correctness depends on shadowing every stdlib method that
  reads the JS backing storage; the runtime checks this at startup and a generation test
  checks the compiled stdlib of each toolchain.

## Non-goals

Proxy identity or caching, crossing `subList` views, custom implementations, access to
collection internals.
