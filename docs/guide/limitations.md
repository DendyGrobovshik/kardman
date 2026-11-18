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
# Limitations

What does not work yet, in one place. Each item names the workaround where there is one.

## Kernel API

- **No overloads** — overloaded functions and methods, secondary constructors and
  extension functions cannot be `@RDMA`. Use distinct names.
- **No generics** other than `List` and `Map`; no `Array`, no data classes without
  `@RDMA`. Make the type `@RDMA` or pass its fields.
- **Function types** work as parameters (of functions, methods and constructors). A lambda
  as a *result*, or a property of a function type, is not supported.
- **Enums** cannot be elements of a `List`/`Map`; `values()`/`entries` are not available
  in plugins.
- **Nullable primitive widget parameters** (`Double?`, `Int?` in a `@Composable @RDMA`
  function) cannot receive `null`; an omitted argument still takes its default.
- **`suspend` methods cannot be `open`.**

## Plugin code

- **`is`/`as` against kernel types** is a compile error (a kernel object is a proxy, not an
  instance of the plugin-side class). Compare by a property instead.
- **Overrides of `open` kernel methods** must have an expression body (`= expr`) and do not
  receive the method's arguments when the kernel calls them.
- **Compose** is limited to `remember`, state, the three effects,
  `rememberCoroutineScope` and kernel widgets (see
  [how_to_write_plugin.md](how_to_write_plugin.md#ui)).
- **Effect keys** that are objects or kernel proxies count as changed on every
  composition; primitive keys compare normally.
- **Cancelling** a plugin coroutine does not cancel a kernel `suspend` call already in
  flight.

## Objects and memory

- **No identity across runtimes** — two crossings of one kernel object give two proxies;
  `===` between them is not guaranteed. `==` on collections is structural.
- **Cycles through both runtimes are not collected.** A plugin lambda handed to the kernel
  is kept alive by the kernel object that holds it; if the lambda (directly or through its
  closure) also holds that kernel object — e.g. a listener registered on the object
  itself — neither garbage collector can free the pair. Unsubscribe explicitly, as with any
  listener.
- **A kernel that keeps a plugin lambda past its composition** (a native delegate created
  once) calls a lambda the plugin already replaced, and the call does nothing. Read the
  lambda through `rememberUpdatedState` (see
  [how_to_write_kernel.md](how_to_write_kernel.md#widgets)).

## Hotfixes

Only top-level `@RDMA` functions that are not `suspend`, not widgets and have no
function-typed parameters or results can be hotfixed; other changes need an app update.
See [hotfix_and_releases.md](hotfix_and_releases.md#what-a-hotfix-can-change).

## Threads

A hotfixed function or a plugin override called from a kernel background thread waits for
the plugin thread and times out after 5 s. See
[threads_and_performance.md](threads_and_performance.md).
