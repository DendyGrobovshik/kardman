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
# Threads and performance

The mental model in three sentences:

1. **The plugin is single-threaded.** All plugin code runs on one thread, the *Hermes
   thread*, and no other thread ever runs plugin code.
2. **A synchronous call from the plugin into the kernel runs on that same thread.** There
   is no thread switch — that is what keeps a call at about 12 µs — so while the kernel
   function runs, the plugin waits.
3. **Slow work belongs in `suspend` functions or behind callbacks.** Then it runs on the
   kernel's own threads and the plugin stays responsive.

## What this means for kernel code

Kernel code called synchronously from the plugin shares the thread with all plugin code
and its composition. If it blocks — network, disk, waiting for a lock or another
thread — the whole plugin stands still.

- **Synchronous `@RDMA` functions must be fast and never block.**
- **Slow work is `suspend`** (switch to `Dispatchers.IO` inside the kernel; the plugin
  awaits the result) **or takes a callback.** Network requests then run on IO, the plugin
  thread stays free, and nothing can deadlock.

  ```kotlin
  @RDMA
  suspend fun loadProfile(id: String): Profile = withContext(Dispatchers.IO) { api.profile(id) }
  ```

- **Don't touch main-thread-only APIs** (Android `View`s, `Toast`, UIKit) from a
  synchronous `@RDMA` function, and don't wait for the main thread there
  (`runBlocking(Dispatchers.Main)`); post to it from a coroutine instead. Writing Compose
  state (`MutableState`) is fine.
- **Widgets are the exception:** `@Composable` `@RDMA` functions run on the UI thread; the
  framework switches threads for them.
- **Callbacks into the plugin** may be called from any kernel thread. The call returns at
  once; the lambda runs later on the plugin's thread.

## Hotfixes and locks

A hotfixed kernel function (see [hotfix_and_releases.md](hotfix_and_releases.md)) runs
as plugin code — on the Hermes thread — even when kernel code calls it. A call from a
background thread therefore switches to the Hermes thread and waits: a warning is logged
after 1 s, and after 5 s the caller gets an `RdmaException` instead of hanging.

So **don't hold a lock while calling an `@RDMA` function that may be hotfixed**, unless
you are sure: if the hotfix calls back into kernel code that needs the same lock, the two
threads wait for each other. In a plain app the same code would run on one thread and
simply re-enter the lock. Prefer `suspend` and callbacks; use locks only when you have
verified them under these conditions.

The same holds for kernel code calling an `open` method that a plugin overrides.

## What this means for plugin code

- A `suspend` function in the plugin does not move work off the thread; use the kernel
  for parallelism ([how_to_write_plugin.md](how_to_write_plugin.md#coroutines)).
- Each access to a kernel object is a crossing. Read a property once into a local when you
  use it in a loop.
- Iterating a kernel `List` or `Map` reads it in one crossing, not one per element;
  `list[i]` in an index loop is one crossing per call.

## Costs at a glance

| Operation | Cost |
|---|---|
| a call or property access on a kernel object | one crossing (~12 µs) |
| `for`/`map`/`forEach` over a kernel collection | one crossing plus element conversion |
| a callback from the kernel | posted; runs when the plugin thread is free |
| a widget call | one crossing plus a switch to the UI thread |

How the threads work inside: [threading.md](../internals/threading.md).

## Recomposition tracer

Plugin content is composed on the plugin thread, and every Composer operation it does
(a group, a `changed` check, a widget call) is a round trip to the UI thread, which costs
far more than a crossing. A plugin composable that runs more often than it needs to is
therefore the first thing to look for when plugin UI is slow. The recomposition tracer
counts how often each plugin composable runs and why:

```kotlin
RdmaDiagnostics.traceRecompositions(true)   // main thread; clears what was collected
// ... use the screen ...
RdmaDiagnostics.logRecompositionReport()    // logcat tag `RdmaTrace` (iOS: `RdmaTrace: ` lines)
val text = RdmaDiagnostics.recompositionReport()
RdmaDiagnostics.traceRecompositions(false)
```

```
com.x.Feed (Feed.kt:12): runs=4 first=1 restarts=3 byParent=0 self=3.1ms total=41.0ms
  restarted by: state@1a2b3c<Integer>, read in com.x.Feed (Feed.kt:12) x3
com.x.Banner (Banner.kt:20): runs=4 first=1 restarts=0 byParent=3 self=2.0ms total=9.8ms
  called again by: com.x.Feed (Feed.kt:12) x3
  changed params: #0 x2; none x1
```

Each run of a composable is one of:

- `first`: the first run of its place in the composition.
- `restarts`: it ran again because something it read changed. `restarted by` names the
  cause: a state (`state@<id><value type>`, with the composables that read it), a
  `lambda update` (a content lambda got new captures, so the composables it ran in run it
  again), or `invalidate()` (a kernel invalidated the scope).
- `byParent`: its caller ran again and called it. `changed params` lists the argument
  positions (`#0` is the first) that changed; `none` means it was called with the same
  arguments and still ran, which is worth a look (an unstable argument, a non-skippable
  function).

`self` and `total` are wall time on the plugin thread, without and with the composables it
called; they include the round trips to the UI thread. Tracing itself slows composition
down, so compare counts and causes, not timings, with an untraced run.

While off, the tracer costs nothing: Compose's own trace hooks in plugin code see no
tracer, and no kernel composition has an observer. Plugins see the tracer through their
bridge, so a plugin built before the tracer existed is not counted until it is rebuilt.
