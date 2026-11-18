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
# Type descriptors

Some crossing points lose the static type of their values: an asynchronous result arrives
long after its call site (*temporal* erasure), or a single universal channel serves every
type — the state proxy's `get_value`/`set_value`, collection elements, callback arguments,
slot arguments (*structural* erasure). Why this matters and the alternatives:
[design/type_descriptors.md](../design/type_descriptors.md).

## Mechanism

**Code generation bakes a descriptor into the call that opens the channel; the runtime
keeps it with the channel and converts by it, never by the value's shape.**

1. **Emit** — the generator derives the descriptor from the static type at the
   channel-opening point. The descriptor always comes from compile-time information
   (Kotlin/JS cannot recover `T` at runtime).
2. **Register** — it travels with the channel: a field of the state proxy, the element
   type of a collection proxy, the parameter descriptors of a block, the result
   descriptor of a `suspend` completion, the descriptors of a slot cell.
3. **Convert** — both directions follow the descriptor. A value that does not fit raises a
   cross-runtime `RdmaException` instead of being coerced or dropped. `null` is a *value*:
   accepted on a nullable channel, rejected on a non-nullable one.

| Channel | Opened by | Descriptor comes from |
|---|---|---|
| `MutableState` value | `rdmaMutableStateOf(x, typeDesc)` | the plugin JVM pass ([plugin_codegen.md](plugin_codegen.md)) |
| `mutableStateListOf`/`mutableStateMapOf` | the same | the same |
| collection elements | a collection proxy | the element type in the signature |
| callback arguments (kernel → plugin) | the holder created for a lambda | the lambda's parameter types, registered with its block |
| `suspend` result | `_<name>Async` | the function's result type, keyed by completion id |
| slot / vtable arguments and result | the slot cell / the `open` method | the function's signature |

The grammar, the parser and the conversion dispatcher exist once (`RdmaTypeDesc` in
`common/`); the platforms differ only in *how* they create a kernel value (JNI class lookup
vs the function registry), never in *which* value.

## Grammar

```
descriptor := leaf | "list:" descriptor | "map:" descriptor ":" descriptor
leaf       := <type fqn> ["?"] ["!"]
```

- `leaf` — `kotlin.Int`, `kotlin.Long`, `kotlin.Double`, `kotlin.Float`, `kotlin.Boolean`,
  `kotlin.String`, an `@RDMA` class FQN or an `@RDMA` enum FQN.
- `?` — nullable.
- `!` — `@RDMA` enum: crosses as the entry name in both directions; the kernel rebuilds the
  entry through `valueOf`, and an unknown name is an `RdmaException`.
- `kotlin.Unit` — results only.
- `rdma.BlockId` — internal: a plugin block id (a plain non-negative integral JS number),
  boxed as a `Long`; used for lists of callbacks.
- A function type degrades to `kotlin.Any`: callbacks travel by id, not by value.

The encoding is a private contract between the generated bridge and the runtime, both
compiled into the app. It is not part of a plugin bundle.

## `Long`

A `kotlin.Long` crossing is exact on every channel. In the plugin a `Long` is the
`Long(low, high)` object (a JS number cannot hold 64 bits), and it crosses as its two
32-bit halves into the kernel's 64-bit `Long`; every value, `Long.MAX_VALUE` included,
round-trips unchanged. A *plain JS number* on a `Long` channel is rejected — accepting it
would round silently. `Int`, `Long`, `Double` and `Float` are four distinct descriptors; a
channel never infers one from another's shape.

## Lifetime

A descriptor lives as long as its channel: a state's descriptor is released with the state
proxy, a collection's with the collection proxy, a block's with the block, a completion's
when it completes.

## Not covered

- The generic callback *return* path (`wrapAny`) is still untyped: a lambda result of a
  `List`/`Map`/`@RDMA` type is not supported.
- Enums as collection elements.
