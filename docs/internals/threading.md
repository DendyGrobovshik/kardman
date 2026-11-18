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
# Threading

User-facing rules: [threads_and_performance.md](../guide/threads_and_performance.md).
Terms: [glossary.md](glossary.md#threads).

## Invariant

**JS is executed only by the Hermes thread, always.** No other thread runs JS — not even
while the Hermes thread is idle or waiting in native code (running JS on the calling thread
was considered and rejected: [runtime_rework.md §2.6](../design/runtime_rework.md#26-threading-invariant)).
Every native → JS call from another thread switches to the Hermes thread. A synchronous
JS → native call runs on the Hermes thread with no switch; Compose operations are the
exception and switch to the UI thread.

## Threads and queues

```
UI thread                                   Hermes thread
┌──────────────────┐   JS tasks: high / low   ┌──────────────────┐
│ Compose          │ ───────────────────────▶ │ jsi::Runtime     │
│ nativeInvoke*    │ ◀─────────────────────── │ content, blocks, │
│ (compose ops)    │   UI tasks (compose ops) │ callbacks, eval  │
└──────────────────┘                          └──────────────────┘
        any other thread ── low queue + wait (rdmaCallJsBlocking) ──▶
```

The Hermes thread runs an event loop over two JS queues — **high** (composition, init) and
**low** (callbacks, continuations, timers, block releases, background calls) — plus a timer
heap. The API (`include/RdmaRendezvous.h`, `common/RdmaRendezvous.cpp`):

| Function | From | Does |
|---|---|---|
| `rdmaCallJs(task)` | UI thread | runs `task` on the Hermes thread and waits (rendezvous) |
| `rdmaCallUi(task)` | Hermes thread, inside a rendezvous | runs `task` on the UI thread and waits |
| `rdmaCallJsBlocking(body, discard, what)` | any thread | Hermes: inline; UI: `rdmaCallJs`; other: low queue + wait with timeout |
| `rdmaPostJs(task)` | any thread | posts to the low queue |
| `rdmaPostJsHigh(task)` | any thread | posts to the high queue |
| `rdmaPostJsSelf(task)` | Hermes thread | posts to the low queue from JS itself (`setTimeout`) |
| `rdmaScheduleTimer` / `rdmaCancelTimer` | Hermes thread | timer heap |

## Rendezvous

Composition goes back and forth: the UI thread composes plugin content (UI → Hermes), the
plugin calls a widget (Hermes → UI), the widget composes a plugin content lambda
(UI → Hermes), and so on. Both sides run **reentrant service loops**: while a thread waits
for the other, it services the other's queue. The nested `UI → JS → UI → JS …` chain is
therefore correct and cannot deadlock.

- A task run on the Hermes thread while it waits inside a native call, on behalf of that
  call, is a **nested** task; a task taken from the loop when the thread is idle is a
  **top-level** task. The low queue runs only as top-level tasks, so callbacks and
  background calls never run in the middle of a composition.
- `rdmaCallUi` is legal only while the UI thread is already at the boundary (inside
  `rdmaCallJs`). This is asserted in debug builds; in release it returns `undefined`
  instead of blocking forever. So a coroutine running asynchronously in the plugin must
  stay on direct calls and never rendezvous outside composition — which is why state writes
  are not rendezvous calls ([compose_protocol.md](compose_protocol.md#state)).
- Thread ids are captured at start and asserted in debug builds.

## Background threads → JS

A native caller on a thread that is neither UI nor Hermes (IO, pools, library threads) —
a hotfixed function's slot or a plugin override through the vtable — uses
`rdmaCallJsBlocking`: the call becomes a low-queue task and the caller waits.

- Arguments and the result cross as thread-safe handles (global refs / `StableRef`s); JS
  values are created and converted inside the task, on the Hermes thread.
- A JS exception comes back as `RdmaExceptionInfo` and is rethrown on the waiting thread as
  `RdmaException`.
- A warning is logged after 1 s; after 5 s the caller gets an `RdmaException` naming the
  callable and the thread. The task is then abandoned: skipped if not started, its result
  discarded if running; either way `discard` releases what it owns. Shared state lives on
  the heap under one mutex, so a timed-out waiter can return safely.

Why not through the UI thread: that would block the UI thread on JS work with no
guarantee. Why low priority: a background call must never run plugin code in the middle of
a composition. The deadlock this cannot prevent — a caller holding a lock that code on the
Hermes thread needs — is made loud by the timeout; see
[runtime_rework.md §6.4](../design/runtime_rework.md#64-threads--contract-d5-decided-b-2026-11-18).

## Coroutines and timers

`Dispatchers.Main`/`Default` in Kotlin/JS are `setTimeout`-based. Hermes has no `window`,
so the runtime installs a global `setTimeout` shim backed by `rdmaPostJsSelf`: a coroutine
body runs after the current composition, never in the middle of a rendezvous;
`Dispatchers.Main.immediate` runs inline.

`setTimeout(fn, ms > 0)` records an absolute deadline in a timer heap owned by the Hermes
thread; the loop sleeps with `wait_until(next deadline)`. A due timer is appended to the
low queue, after any high-priority task and already-queued low tasks. `ms <= 0` is a plain
low-queue post. `clearTimeout` cancels lazily. An exception in a timer callback goes to the
global uncaught handler.

## Startup

`RdmaBridge.nativeInit` fills the JNI caches on the UI thread (app classes need the app
class loader) and starts the Hermes thread; bundle evals are queued asynchronously.
`nativeIsReady()` is true once the runtime is up and all evals have drained; a load error is
thrown from it ([exceptions.md](exceptions.md#channels)).
