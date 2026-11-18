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
# Design: type descriptors

How it works: [internals/type_descriptors.md](../internals/type_descriptors.md).

## Problem

Some channels lose the static type of their values: an asynchronous result arrives long
after its call site (temporal erasure), or one universal proxy serves every type — the state
proxy, collection elements, callback arguments (structural erasure).

The two directions are asymmetric. A native value describes itself (a boxed JVM value
carries its class). A JS value carries only a *shape* — `number`, `string`, `bool`,
`object` — and for `number` only the declared type can say whether it is an `Int`, a
`Double`, a `Long` or a `Float`. Guessing by shape silently truncated `19.9` to `19` and
dropped unsupported writes without a trace.

## Decision

**The generated code bakes a descriptor of the static type into the call that opens the
channel; the runtime keeps it with the channel and converts by it, never by shape.** A value
that does not fit is an error, never a coercion.

- The descriptor is compile-time information: Kotlin/JS cannot recover a type argument at
  runtime, and reified generics would not reach erased channels anyway.
- One grammar, one parser and one dispatcher for both platforms: a JNI-only or C-ABI-only
  variant would let the platforms disagree about *which* value a JS value becomes.
- `null` is a value, distinct from a failed conversion: accepted on nullable channels only.
- State with a non-concrete type (`Any?`, `dynamic`) is a compile error: an erased channel
  without a descriptor would bring the guessing back.
- **`Long` is exact or rejected.** A JS number has 53 bits of mantissa; accepting a plain
  number as a `Long` would round silently. The plugin's `Long` object crosses as its two
  32-bit halves.
- The encoding is private to the bridge and the runtime (both compiled into the app), so it
  can change freely; it is not part of the plugin call form.

## Non-goals

Cross-runtime identity, stable effect keys for objects, reified generics in plugins.
