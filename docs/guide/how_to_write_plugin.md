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
import com.example.kernel.internal.runRdmaApp

@Composable
fun App() {
    val people = remember { findPeople("a") }
    Column {
        people.filter { it.age > 18 }.forEach { Text(it.greet()) }
    }
}

fun main() {
    runRdmaApp { App() }
}
```

There is no serialization or glue code to write. Behind the scenes a kernel object stays
in the kernel and the plugin holds a proxy to it; a kernel `List` is a real Kotlin list in
the plugin, so the whole stdlib works on it.

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
- `SideEffect`, `DisposableEffect`, `LaunchedEffect`, `rememberCoroutineScope()`;
- the kernel's widgets.

Anything else from `androidx.compose.*` (`derivedStateOf`, `snapshotFlow`,
`movableContentOf`, `produceState`, animations, …) is a compile error:

```
kernel doesn't support 'derivedStateOf' — the plugin is limited to the base Compose protocol (remember/mutableStateOf/widgets)
```

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
  kernel does the work on its own threads. See
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
