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
# Features

This document explains *what you can do* with the framework and, just as
importantly, *how to think about it*. It is a narrative, not a reference dump —
the compiler plugins give you a model where both sides look like plain Kotlin.
For the runtime mechanics see [architecture.md](architecture.md); for the formal
type contract see [types.md](types.md); for how the code gets generated see
[codegen.md](codegen.md).

## The mental model

The framework connects two runtimes — the **kernel** (JVM/Android) and the
**plugin** (Hermes/JavaScript). The key promise is that **both sides are just
Kotlin**. You don't write serialization code, glue code, or a foreign-function
interface by hand:

```kotlin
// kernel — shipped inside the app
@RDMA
class Person(val name: String, val age: Int)

// plugin — loaded at runtime
import com.example.kernel.Person

val p = Person("Иван", 30)
println(p.name)
```

The only thing you ever do differently from a normal Kotlin project is put
`@RDMA` on the declarations that may cross the boundary. That annotation *is*
the boundary: it declares, for each type, exactly what the plugin is allowed to
see and call.

Everything else is handled by the compiler plugins: they scan the kernel for
`@RDMA` declarations, generate the C++ bridge, and rewrite the plugin source so
that the ordinary-looking calls above actually round-trip over JSI/JNI.

There are **two different realities** behind this single mental model, and it
helps to keep them separate:

- **Data** — a `@RDMA` object always lives in kernel (JVM) memory. The plugin
  never gets a copy; it gets a *handle* that proxies every call back to the real
  object. A primitive, by contrast, is copied.
- **UI** — the Compose tree also lives in the kernel. The plugin is a
  *`@Composable` guest* that describes the tree; the kernel's Compose runtime
  actually renders it.

Both are "just Kotlin", but the direction of control differs: for data, the
plugin calls *into* the kernel; for UI, the kernel pulls composition *out of* the
plugin.

## The language: what crosses the bridge

### What can be `@RDMA`

Four kinds of declarations can be marked `@RDMA`:

1. **A class** — a shared data type. The plugin can construct it, read and write
   its properties, and call its methods.
2. **An `open` class** — the plugin can *subclass* it and override its `open`
   methods (see below).
3. **A companion `val`** — a static/singleton value, exposed as
   `RDMA.<Class><Name>()` (e.g. `Alignment.Center`).
4. **A top-level function** — either a plain function or a widget. A function
   that is also `@Composable` becomes a **widget** (see the UI section).

### The types that may cross

The plugin can only touch values whose type is on the allowed list. Types are
validated recursively — including inside `List<T>` and function signatures — and
a violation is a **compile error**, not a runtime failure.

| Type | How it crosses | Where the value lives |
|---|---|---|
| `Int`, `Long`, `Float`, `Double`, `Boolean`, `String` | by copy | a copy on each side |
| nullable variants (e.g. `String?`) | by copy (may be `null`) | as above |
| `@RDMA` class | by reference (handle) | only in kernel memory |
| `List<T>` / `MutableList<T>` of an allowed `T` | by reference (handle) | materialized in kernel memory |
| function / lambda type | registration + callback | body lives in the plugin; kernel holds an id |
| `Unit` | — | return type only |

Two of these deserve a note:

- **`@RDMA` references are handles.** The object is created once in the kernel;
  every property read and method call on the handle mutates the *kernel* object.
  There is no object identity across runtimes — each call that crosses back
  produces a fresh handle to the same underlying object.
- **`List<T>` is a special case.** A list first created in the plugin is
  materialized into a kernel-side `ArrayList` on its first crossing and kept
  there from then on, so element mutation happens on the kernel side.

### What the plugin can write

This is where the model really pays off. The plugin writes idiomatic Kotlin, and
the FIR compiler plugin rewrites it. The table shows a few representative
rewrites (full list in [codegen.md](codegen.md)):

| You write | The compiler emits |
|---|---|
| `Person("str", 42)` | `RDMA.createPerson('str', 42)` |
| `p.name` | `p.getName()` |
| `p.status = "x"` | `p.setStatus("x")` |
| `p.toString()` | unchanged — dispatches dynamically on the JS proxy |
| `Alignment.Center` | `rdmaAlignmentCenter()` |
| `class Cyborg(n: String) : Person(n, 0) { override fun greet() = "..." }` | `RDMA.createWithOverrides('Person', [...], { greet: function() { return "..."; } })` |
| `Text("hi")` (a widget) | `rdmaText("hi")` |

What falls out of this:

- **Constructors** — the primary constructor, any number of parameters, with
  literals or variable references as arguments.
- **Properties** — both `val` and `var`. Property access becomes getter/setter
  methods, so a `var` is writable from the plugin.
- **Methods** — any public method; return type may be any allowed type. Calls
  pass through unchanged and dispatch on the JS proxy.
- **Inheritance** — subclass a kernel `open` class and override its `open`
  methods. Overridden methods are called from *both* the plugin and the kernel
  (via the vtable — see [codegen.md](codegen.md)). Overrides currently need an
  expression body (`= expr`).
- **Statics** — companion `val`s become singleton getters.

### The invariant and what's rejected

Every type that appears in an `@RDMA` declaration — property, method parameter
or return, function parameter or return, and anything nested inside a `List` or
function signature — must be an allowed type. A non-`@RDMA` class on the
boundary is a compile error. Concretely, the following are **not** supported:

- composite value types without `@RDMA` (data classes, `enum`, `Array`, `Map`);
- generics other than the `List<T>` special case;
- secondary/overloaded constructors, named arguments, default parameter values;
- overloaded methods, extension functions, `suspend`/coroutine functions.

## UI

UI follows the same "just Kotlin" idea but with the direction of control
reversed: the **kernel owns the Compose runtime** and renders everything; the
plugin is a guest that *describes* the tree. The plugin therefore does not get
the full Compose surface — it gets a **base protocol** (the structural
`Composer` methods) plus the widgets the kernel exposes.

### Two kinds of widgets

- **Primitive** widgets (`Text`, `Column`, `Button`, `TextField`) are
  `@Composable` functions declared in the kernel with `@RDMA` and a real
  material3 body. They are the only widgets the kernel knows how to render, so
  they can only live in the kernel.
- **Composite** widgets are ordinary `@Composable` functions built from
  primitives and other composites. They can live anywhere — kernel or plugin. A
  plugin composite's `content` lambda executes in the JS runtime.

### What the plugin can express

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: $count")
        Button("Increment", onClick = { count++ })
    }
}

fun main() {
    runRdmaApp { Counter() }
}
```

Allowed in the plugin: `@Composable` functions/lambdas, `remember { ... }` (with
keys), `mutableStateOf` + `getValue`/`setValue` (the `var x by ...` delegation),
the kernel's `@RDMA` widgets, three effects — `SideEffect`, `DisposableEffect` and
`LaunchedEffect` — and `rememberCoroutineScope()` (all hosted in the kernel, see
below). `runRdmaApp { ... }` installs the root content.

Lambdas matter here. The compiler distinguishes two kinds of function
parameters on a widget:

- a **content lambda** (a `@Composable () -> Unit` parameter, e.g. `Column`'s
  `content`) — its body runs in the plugin and composes against the host;
- a **callback** (a plain `() -> Unit`, e.g. `Button`'s `onClick`) — registered
  in JS and invoked by the kernel on events like taps.

### Effects

`SideEffect`, `DisposableEffect` and `LaunchedEffect` run their bodies in the
plugin (JS), but the **lifecycle is owned by the kernel**: the Compose runtime
must be the one calling `onRemembered`/`onForgotten`/`onAbandoned`, and a
guest-side `remember` value is just an opaque `JsValueHolder` the kernel cannot
drive. So each effect is rewritten into a kernel-backed bridge:

- `SideEffect { … }` → `rdmaSideEffect { … }` — the kernel composes a real
  `SideEffect` that forwards the block id back into JS after every commit.
- `DisposableEffect(keys) { onDispose { … } }` → `rdmaDisposableEffect(keys) { … }`
  — the kernel composes `remember(keys) { RdmaDisposableEffectObserver(blockId) }`,
  a real `RememberObserver` whose `onRemembered` runs the effect body once and
  whose `onForgotten`/`onAbandoned` dispose the returned result. The `keys` are
  marshalled to the kernel and compared there, so key-change semantics match the
  host. Primitive keys are compared faithfully; `Unit` is normalized to a stable
  sentinel; object/`@RDMA`-handle keys are treated as always-changed (a known
  limitation until stable handle identity lands).
- `LaunchedEffect(keys) { … }` → `rdmaLaunchedEffect(keys) { … }` — the exact same
  `DisposableEffect` machinery, but the body is a `suspend` block: it is launched
  on the plugin's `Dispatchers.Main` (the Hermes thread) and the returned result
  disposes by cancelling the launched `Job`. Key-change and leave-composition
  semantics therefore match the host's `LaunchedEffect` exactly.
- `rememberCoroutineScope()` → `rdmaRememberCoroutineScope()` — a plugin-local
  `CoroutineScope` remembered across recompositions and cancelled when the call
  site leaves the composition (again via the `DisposableEffect` bridge).

Because the kernel must be able to faithfully execute whatever the plugin
composes, everything else from `androidx.compose.*` is rejected at compile time.
Forbidden: `derivedStateOf`, `snapshotFlow`, `movableContentOf`, `produceState`,
animations, and any other Compose symbol outside the allowlist. The error is
explicit:

```
kernel doesn't support 'derivedStateOf' — the plugin is limited to the base Compose protocol (remember/mutableStateOf/widgets)
```

### Coroutines in the plugin

The plugin is **single-threaded**: it runs entirely on the Hermes thread, and a
`suspend` function is not a background thread — it is just a resumable function
on that same thread. `Dispatchers.Main` (and its `immediate` variant) are the
Hermes-thread event loop, backed by a global `setTimeout` shim that the runtime
installs and that schedules continuation work onto the low-priority JS queue —
i.e. after any in-flight composition, never in the middle of it.

- `Dispatchers.Main` always dispatches (deferred), so a `LaunchedEffect` body runs
  after the current composition.
- `Dispatchers.Main.immediate` runs inline, so `rememberCoroutineScope().launch { }`
  starts synchronously, matching the host's current-thread semantics.
- `Dispatchers.Default` on JS is the same `setTimeout`-based event loop (there is
  no background thread pool); `Dispatchers.IO` does not exist on JS and is a
  compile error. Real parallelism lives in the kernel: offload heavy work to an
  `@RDMA` function rather than a dispatcher.
- `delay(ms)` is not yet implemented (timers are a separate follow-up); `launch`
  without `delay` works today.

The plugin must therefore only use **direct** calls inside a coroutine (data-path
JNI and direct state reads/writes); a blocking JS→UI rendezvous outside the
active composition would deadlock, so the runtime asserts against it.

## Dynamic loading

The plugin is compiled to a JavaScript bundle that Hermes **evaluates at
runtime** — it is never baked into the app binary as native or bytecode. Today
the app ships the bundle in its assets and loads it with `RdmaBridge.nativeEvalAsset(...)`.
Because the runtime simply evals JS source, the same bundle can equally be
fetched over the network at runtime (an upcoming, separate module), giving
app-update-free updates of both plugin logic and UI.

## At a glance

**Supported** — `@RDMA` classes (incl. `open` + plugin subclasses), properties
(`val`/`var`), public methods, companion statics, `@RDMA` top-level functions
and widgets; types `Int`/`Long`/`Float`/`Double`/`Boolean`/`String` (nullable
allowed), `@RDMA` references, `List<T>`, lambdas, `Unit` returns; UI via the base
Compose protocol (`remember`/`mutableStateOf`/widgets + content lambdas and
callbacks) plus the `SideEffect`, `DisposableEffect` and `LaunchedEffect` effects
and `rememberCoroutineScope` (single-threaded, `Dispatchers.Main`/`.immediate`).

**Not yet** — non-`@RDMA` value types (`enum`, `Array`, `Map`, data classes),
generics beyond `List<T>`; secondary constructors, named/default arguments;
overloaded methods, extension and `suspend` functions; block-body overrides;
`derivedStateOf`/`snapshotFlow`, animations and anything else outside the
allowlist; `delay(ms)`/timers; stable identity for object/`@RDMA` effect keys;
cross-runtime object identity/`equals`, JNI exception propagation,
async/Promise returns, batch transfers.
