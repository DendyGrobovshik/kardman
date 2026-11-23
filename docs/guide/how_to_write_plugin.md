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
# How to write a plugin

A plugin is ordinary Kotlin compiled to JavaScript and loaded at runtime. It imports the
kernel's `@RDMA` declarations and uses them as if they were local:

```kotlin
import com.example.kernel.internal.Person
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.registerComponent

@Composable
fun App() {
    val people = remember { findPeople("a") }
    Column {
        people.filter { it.age > 18 }.forEach { Text(it.greet()) }
    }
}

fun main() {
    registerComponent("main") { App() }
}
```

There is no serialization or glue code to write. Behind the scenes a kernel object stays
in the kernel and the plugin holds a proxy to it; a kernel `List` is a real Kotlin list in
the plugin, so the whole stdlib works on it.

`@RDMA` is a kernel annotation: a plugin uses `@RDMA` declarations but does not declare
them — writing `@RDMA` in a plugin is a compile error. The plugin's own classes are plain
Kotlin and live in JS; a subclass of an `open` kernel class needs no annotation.

## Components

A plugin shows UI by registering **components** in `main()`; the app renders them by name
wherever it wants — a screen, a cell of a native list, a dialog — and as many times as it
wants. A component may take up to 3 parameters of boundary types:

```kotlin
fun main() {
    registerComponent("feed-card") { item: FeedItem, compact: Boolean -> FeedCard(item, compact) }
}
```

```kotlin
// app (native)
RdmaComposeHost.Component("alice:feed/feed-card", item, true)
```

- The full name is `"<pluginId>/<name>"`.
- Each place that renders a component has its own state: two cards of one plugin in a feed
  do not share their `remember`s.
- A place that renders a component before the plugin is loaded shows nothing and fills in
  as soon as the component is registered.
- A new plugin version takes effect at the app's next update point (a cold start or
  `RdmaBridge.applyUpdates()`); then every place showing the component is recreated with it
  and starts with fresh state. Two versions of a plugin never run side by side. See
  [hotfix_and_releases.md](hotfix_and_releases.md#when-an-update-takes-effect).
- Arguments are converted by the declared parameter types; a value of another type throws
  `RdmaException`.

## Data

- **Construct, read, write, call** — `Person("Ann", 30)`, `p.name`, `p.age = 31`,
  `p.greet()`. Each of these is one crossing into the kernel (about 12 µs).
- **Statics and enums** — `Alignment.Center`, `HttpMethod.GET`, `.name`, `.ordinal`,
  `HttpMethod.valueOf("POST")`, `when (m) { … }`.
- **Collections** — `List`/`Map` from the kernel and your own `listOf`/`mutableMapOf`
  cross both ways. A collection you pass to the kernel becomes shared with it: later
  changes on either side are visible on the other.
- **Lambdas** — pass them to `@RDMA` functions that take callbacks.
- **`suspend`** — call the kernel's `suspend` functions from a coroutine.

Value types and their semantics: [types.md](types.md).

## UI

The plugin describes UI with `@Composable` functions; the kernel's Compose runtime renders
it. Kernel widgets are the building blocks; your own composables can combine them freely.

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: $count")
        Button("Increment", onClick = { count++ })
    }
}
```

Available in the plugin:

- `@Composable` functions and lambdas, `if`/`for`/`when` in composition;
- `remember { … }` (with keys);
- `mutableStateOf`, `mutableStateListOf`, `mutableStateMapOf`, `var x by …`;
- `derivedStateOf { … }` (without a mutation policy), see below;
- `SideEffect`, `DisposableEffect`, `LaunchedEffect`, `rememberCoroutineScope()`;
- the kernel's widgets.

Anything else from `androidx.compose.*` (`snapshotFlow`, `movableContentOf`,
`produceState`, animations, …) is a compile error:

```
kernel doesn't support 'snapshotFlow' — the plugin is limited to the base Compose protocol (remember/mutableStateOf/widgets)
```

### derivedStateOf

`derivedStateOf` computes a value from other states; whoever reads it recomposes only when
the **result** changes, not on every change of its inputs:

```kotlin
var query by remember { mutableStateOf("") }
val canSearch by remember { derivedStateOf { query.trim().length >= 3 } }
Text(if (canSearch) "Ready" else "Type 3 letters") // recomposes when the answer flips, not per keystroke
```

It pays off when the inputs change often, the result rarely, and the reader's UI is more
than a few widgets: thresholds, "the form is valid", a filtered list of a large state. It
does not pay off when the result changes as often as its inputs — then it only adds work.

- The result type follows the [state value rules](types.md#state-values) (no plugin
  classes); the result is compared with `equals`. A mutation policy argument is a compile
  error.
- Keep the calculation short and free of writes: each recalculation is a call into the
  plugin, and the UI thread waits for it
  ([threads_and_performance.md](threads_and_performance.md)).
- Remember it (`remember { derivedStateOf { … } }`), as in Compose. A calculation that
  captures its own state keeps the state alive forever.
- An exception in the calculation reaches the reader as `RdmaException`.

Modifiers are a kernel class too (if your kernel defines one — the demo's is the reference).
Start a chain with `Modifier()` and pass it to a widget:

```kotlin
Row(modifier = Modifier().fillMaxWidth()) {
    Text("Name", modifier = Modifier().weight(1.0))
    Button("Go", modifier = Modifier().padding(4.0)) { count++ }
}
```

Sizes are `Double` dp, colors ARGB `Long` (`0xFFE3F2FD`). Scope modifiers (`weight`,
`align`) work inside the container they belong to and are ignored elsewhere.

State rules:

- The state's type must be concrete: `mutableStateOf<Any?>(…)` does not compile. The type
  is fixed at creation; writing a value of another type throws `RdmaException`.
- As in plain Compose, mutating a plain collection held in `mutableStateOf` in place does
  not recompose — assign a new one, or use `mutableStateListOf`/`mutableStateMapOf`.

## Coroutines

The plugin is single-threaded: everything runs on one thread, and a `suspend` function is
a resumable function on that thread, not a background task.

- `Dispatchers.Main` dispatches after the current composition; `Dispatchers.Main.immediate`
  runs inline (what `rememberCoroutineScope().launch {}` uses).
- `Dispatchers.Default` is the same event loop; `Dispatchers.IO` does not exist in JS and
  is a compile error.
- `delay` and `withTimeout` work on real timers.
- For real parallelism call a kernel `suspend` function or a function with a callback; the
  kernel does the work on its own threads. Cancelling the coroutine that waits for a kernel
  `suspend` call cancels the kernel call too. See
  [threads_and_performance.md](threads_and_performance.md).

## Errors

Kernel exceptions arrive in the plugin as exceptions with the original type name, message
and stack; exceptions the plugin throws back reach the kernel the same way. See
[exceptions.md](exceptions.md).

Don't let an exception escape a `@Composable` body: it breaks the composition. Report
errors through state instead.

## What the compiler rejects

These compile in plain Kotlin but are errors in a plugin, each with a message naming the
reason:

- `is` / `as` / `as?` against a kernel class or enum, and reified checks such as
  `filterIsInstance<Person>()` — a kernel object is a proxy, not an instance of the
  plugin-side class;
- `Enum.values()` / `Enum.entries` of a kernel enum;
- kernel symbols of modules the plugin may not see (only the `internal` modules and the
  plugin's own user module);
- Compose symbols outside the list above.

A `when` over a kernel enum without `else` gets one that throws: a newer kernel may have
entries this plugin does not know.

## Subclassing kernel classes

```kotlin
class Cyborg(name: String) : Person(name, 0) {
    override fun greet() = "Beep, $name"
}
```

Allowed for `open` kernel classes. The kernel calls your override too. Today an override
must be an expression body and does not receive the method's arguments when the kernel
calls it. See [limitations.md](limitations.md).
