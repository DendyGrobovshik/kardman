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
# Type Descriptors: marshalling values through erased channels

**Applies to:** Android (JNI) and iOS (C ABI)

## Problem

A cross-boundary channel can lose the static type `T` its values are declared with,
even though the compiler knew `T` exactly. Erasure happens in two shapes:

- **Temporal** — an asynchronous result arrives long after the call site (and its
  signature) is gone; see [suspend.md](suspend.md).
- **Structural** — the channel is a single universal proxy with no per-signature
  glue: `StateProxyHost` (`get_value`/`set_value`), the generic callback path
  (`wrapAny`), collection element materialization.

The two crossing directions are asymmetric:

- **kernel → JS** values self-describe: a boxed JVM value carries its class
  (`Integer` vs `Double` vs a `java.lang.Enum`), so a runtime dispatch is sound.
- **JS → kernel** values carry only a JS *shape* (`number`/`string`/`bool`/
  `object`). For `number` the declared type alone decides what it becomes on the
  kernel side (`Int` vs `Double` vs `Long` vs `Float`). Guessing by shape is what
  silently truncated `19.9` to `19` and dropped unsupported writes without a
  trace.

## Mechanism

**Codegen bakes a type descriptor into the bridge call that opens the channel;
the runtime keeps it with the channel and marshals by it, never by value shape.**

1. **Emit** — the bridge generators derive a descriptor string from the static
   type at the channel-opening point (suspend: `_<name>Async` registers the
   result descriptor for the `completionId`; state: the `mutableStateOf` /
   `mutableStateListOf` / `mutableStateMapOf` call site passes the descriptor of
   its static type). No reification: Kotlin/JS cannot recover `T` at runtime, so
   the descriptor always comes from compile-time information.
2. **Register** — the descriptor travels with the channel: a
   `completionId → descriptor` entry for suspends, a field on the state proxy
   for state.
3. **Marshal** — both conversion directions follow the descriptor. There is no
   shape-based fallback on a typed channel: a value that does not fit the
   descriptor raises a cross-runtime `RdmaException`
   ([exception_propagation.md](exception_propagation.md)) instead of being
   coerced or dropped. `null` is a *value*: it is accepted on a nullable
   channel and rejected on a non-nullable one — a null write is never confused
   with a failed conversion.

The grammar, the parser and the marshalling dispatcher live once, shared by both
backends (`RdmaTypeDesc` in the runtime). A JNI-only or C-ABI-only variant of any
of the three is a bug: the dispatchers may differ per platform in *how* they
create a kernel value (JNI class lookup vs. the function-pointer registry), but
not in *which* value they create.

## Grammar

```
descriptor := leaf | "list:" descriptor | "map:" descriptor ":" descriptor
leaf       := <type fqn> ["?"] ["!"]
```

- `leaf` — the Kotlin type FQN: `kotlin.Int`, `kotlin.Long`, `kotlin.Double`,
  `kotlin.Float`, `kotlin.Boolean`, `kotlin.String`, an `@RDMA` class FQN, or an
  `@RDMA` enum FQN.
- `?` — nullable: `null`/`undefined` cross as `null`.
- `!` — `@RDMA enum`: crosses as the entry **name string** in both directions
  (JS sees `"GET"`; the kernel materializes via `valueOf`, and an invalid name is
  an `RdmaException`).
- `kotlin.Unit` — return-only.
- A function/lambda type degrades to `kotlin.Any` — callbacks travel by id, not
  by value (see [collections.md](collections.md) for elements).

The encoding is a private contract between the generated kernel bridge and the
runtime (both compiled into the app). It is not part of a plugin bundle and not
a stable external format.

## Long policy

A `kotlin.Long` crossing is exact on every channel — function arguments and
returns, suspend results, state values, `@RDMA` fields, collection elements.
There is no code path where a `Long` may lose bits quietly.

Exactness is structural, not a range check. On the plugin side a `kotlin.Long` is
a `Long(low, high)` object (a JS number cannot hold a 64-bit integer), and it
crosses the boundary as its two 32-bit halves all the way into the kernel's
64-bit `Long` (`jlong` on Android, `Long` on iOS). Both sides are 64-bit exact,
so every value — including `Long.MAX_VALUE` — round-trips unchanged, and plugin
arithmetic on a returned `Long` keeps working (it is still a `Long` object).

A *plain JS number* arriving on a `Long` channel is **not** a `Long` — it is rejected
with `RdmaException`. A raw JS number cannot represent an arbitrary 64-bit integer
(double has 53 bits of mantissa), so silently accepting one for `Long` would round
without a trace; plugin code crossing a 64-bit value must use the plugin's `Long`
object (`Long(low, high)`), which carries its exact halves. `Long`, `Int`, `Double`
and `Float` are four distinct Kotlin types and four distinct type descriptors — a
channel never infers one from another's shape.

## Invariants

- A channel's descriptor is fixed when the channel opens. For state that is the
  static type of `mutableStateOf(...)`; the type never changes afterwards, and a
  write of a foreign-typed value is an `RdmaException`, not a coercion.
- State creation with a non-concrete static type (`Any`, `Any?`, `dynamic`) is a
  **compile error** in the plugin: an erased channel without a descriptor would
  recreate the guessing this mechanism exists to remove.
- `null` is a value (nullable channels only); everything else that does not fit
  the descriptor is an error. The two are never conflated.

## Lifecycle

The descriptor lives exactly as long as its channel. Suspend registries are
freed with the completion (`freeBlock`); a state's descriptor is held by the
state proxy and released with the proxy destructor (the same place that
releases the kernel object handle — see [bridges.md](../bridges.md#lifecycle)).

## Clients

| Channel | Erasure | Status |
|---|---|---|
| suspend completion | temporal | migrated onto the shared layer |
| `MutableState.get_value`/`set_value` | structural | supported (this mechanism) |
| `mutableStateListOf`/`mutableStateMapOf` | structural | supported on both backends (state-backed proxies; structural reads register the composition dependency) |
| generic callback return (`wrapAny`) | structural | TODO: lift the `List`/`Map`/`@RDMA` return restriction |
| collection elements | structural | scalars/`Long` leaves exact, `@RDMA` enum entries rebuild via `valueOf` |

## Non-goals

- Cross-runtime object identity (`===`, `equals`) — unchanged by this mechanism;
  see [features.md](../user_guide/features.md#the-types-that-may-cross).
- Stable effect keys (object/`@RDMA` keys are always "changed") — a separate
  limitation.
- Reified generics on the plugin side.

## State semantics

Descriptor-typed state values pair with standard Compose semantics: mutating a
plain collection held in `mutableStateOf` in place does **not** schedule a
recomposition (the value reference is unchanged) — assign a new collection to
trigger. Use `mutableStateListOf`/`mutableStateMapOf` when mutations themselves
must trigger: they back the state with a `SnapshotStateList`/`SnapshotStateMap`,
whose mutators notify the Compose snapshot. See
[bridges.md](../bridges.md#state-creation-reads-and-writes).
