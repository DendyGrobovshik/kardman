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
# How to write a kernel

The kernel is the part of your code that ships inside the app. Plugins see only what you
mark `@RDMA`; that annotation *is* the boundary. Everything else in the kernel stays
private to native code.

```kotlin
@RDMA
class Person(val name: String, var age: Int) {
    fun greet(): String = "Hi, $name"
}

@RDMA
fun findPeople(query: String): List<Person> = repository.search(query)
```

The kernel compiler plugin generates the bridge for every `@RDMA` declaration; plugins get
typed declarations of the same names and use them as ordinary Kotlin.

## What can be `@RDMA`

| Declaration | The plugin can |
|---|---|
| class | construct it (primary constructor), read and write its properties, call its public methods |
| `open` class | additionally subclass it and override its `open` methods ([below](#open-classes)) |
| subclass of an `@RDMA` class | use it wherever the superclass is expected; inherited members are not repeated |
| `enum class` | use its entries, `.name`, `.ordinal`, `valueOf`, `when` |
| companion `val` | read it as a static (`Alignment.Center`) |
| top-level function | call it; `suspend` functions are awaited |
| `@Composable` top-level function | call it as a **widget** from plugin composables |

Every type in an `@RDMA` signature must be a boundary type — primitives, `String`,
`@RDMA` classes and enums, `List`/`Map` of those, function types, `Unit`. The full table
and the semantics of each: [types.md](types.md). A violation is a compile error in the
kernel.

Not supported: overloaded functions and methods, secondary constructors, extension
functions, generics other than `List`/`Map`. See [limitations.md](limitations.md).

## Default parameters

`@RDMA` functions, methods and constructors may have default values, **trailing only**
(once a parameter has a default, all later ones need one too). The default is evaluated
in the kernel, so the plugin passes only what the caller wrote. Adding a new trailing
defaulted parameter is compatible in both directions; anything else that changes a
signature is a release error. Details: [types.md](types.md#default-parameter-values).

## Callbacks and `suspend`

A parameter of a function type receives a plugin lambda. Calling it from the kernel
**posts** the call to the plugin's thread and returns at once; the lambda runs
asynchronously and its errors go to the global uncaught-exception handler
([exceptions.md](exceptions.md)). You may call it from any thread.

```kotlin
@RDMA
fun httpGet(url: String, onSuccess: (Response) -> Unit, onError: (String) -> Unit)
```

A `suspend` function is the simpler alternative: the plugin awaits the result and gets
exceptions thrown into its coroutine.

```kotlin
@RDMA
suspend fun fetch(url: String): Response = withContext(Dispatchers.IO) { client.get(url) }
```

**Prefer these two for anything slow.** A synchronous `@RDMA` function runs on the
plugin's own thread; while it runs the plugin waits. The rules:
[threads_and_performance.md](threads_and_performance.md).

## Widgets

A `@Composable` `@RDMA` function is a widget: it renders natively, and the plugin calls it
from its composables.

```kotlin
@RDMA
@Composable
fun Button(label: String, onClick: () -> Unit) { M3Button(onClick) { M3Text(label) } }

@RDMA
@Composable
fun Column(content: @Composable () -> Unit) { M3Column { content() } }
```

- A `@Composable` lambda parameter (`content`) runs plugin code inside your layout.
- A plain lambda parameter (`onClick`) is a callback.
- Widgets run on the UI thread.
- A lambda the plugin passes in belongs to the current composition: when the plugin
  passes a new one, the old one is released. If your widget keeps a lambda beyond one
  composition (a native listener or delegate created once), read it through
  `rememberUpdatedState`, as for any Compose code:

  ```kotlin
  val currentActions by rememberUpdatedState(actions)
  val delegate = remember { Delegate { i -> currentActions[i]() } }
  ```

## `open` classes

The plugin may subclass an `open` `@RDMA` class and override its `open` methods. The
override is called both from the plugin and from kernel code — from any kernel thread; it
runs on the plugin's thread and an exception it throws reaches the kernel caller. Today an
override must have an expression body (`= expr`) and does not receive the method's
arguments when the kernel calls it. `suspend` methods cannot be `open`.

## Public API of a kernel module

A kernel module's non-`@RDMA` **concrete classes** must be `internal` or `private`: a
public non-`@RDMA` class could otherwise leak into an `@RDMA` signature. Interfaces,
objects and top-level `val`s may stay public (for example a `KernelServiceProvider`
interface and a `KernelServices` registry the app implements), as may the entry point
`runRdmaApp`.

## Referencing another kernel module

An `@RDMA` signature may use an `@RDMA` type from another kernel module (a widget in
`:kernel:user:alice` taking a `Color` from `:kernel:internal`), as long as the module
depends on the owner through Gradle. The only check is that the referenced type is itself
`@RDMA`.

## Changing a released kernel

Kernel changes reach users in two ways: with an app update (a *static* release), or as a
**hotfix** delivered to installed apps (a *dynamic* release, where the changed functions
run as JS polyfills). Not every change can be a hotfix; the release rejects those that
cannot and names the reason. See [hotfix_and_releases.md](hotfix_and_releases.md).

Remove a public `@RDMA` symbol only after a `@Deprecated` period: older plugins may still
call it.
