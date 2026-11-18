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
# Overview

A map of the framework for the people who work on it. Each mechanism has its own page;
this one only shows how they fit together. Terms: [glossary.md](glossary.md).

## Two runtimes, one process

```
┌──────────────────────────────────────────────────────────────┐
│ Plugin — Kotlin/JS in Hermes (Hermes thread)                 │
│   val p = Person("Ann", 30); p.name; Text("hi")              │
│     ↓ kernel stubs inline the call form                      │
│   RDMA.createPerson("Ann", 30); p.getName(); RDMA.composeText│
└───────────────────────────┬──────────────────────────────────┘
                            ↓ JSI host functions
┌───────────────────────────┴──────────────────────────────────┐
│ C++ runtime — librdma_runtime (+ per-app librdma_user)       │
│   proxies on shared prototypes, blocks, rendezvous, slots    │
└───────────────────────────┬──────────────────────────────────┘
                            ↓ JNI (Android) / C ABI registry (iOS)
┌───────────────────────────┴──────────────────────────────────┐
│ Kernel — Kotlin on JVM / Kotlin/Native                       │
│   @RDMA classes, functions, widgets; native Compose runtime  │
└──────────────────────────────────────────────────────────────┘
```

- **Data** lives in kernel memory. The plugin holds proxies; every call on a proxy is a
  crossing. Primitives are copied.
- **UI** lives in the native Compose runtime. The plugin's composables run in JS and drive
  it through the Compose protocol; kernel widgets render natively.
- **Threads**: all JS runs on the Hermes thread; Compose runs on the UI thread; the two
  meet through the rendezvous. A synchronous call from JS into the kernel stays on the
  Hermes thread.

## Build pipeline

1. **Kernel compilation** — the kernel compiler plugin reads the `@RDMA` declarations and
   writes the bridge (C++ and Kotlin glue), `rdma_api.json` and
   `kernel_manifest_<platform>.json`; it injects slot prologues and the vtable into the IR.
   → [kernel_codegen.md](kernel_codegen.md)
2. **Kernel release** — `kernel_manifest_<platform>.json` is diffed against the changelog;
   a release appends to it and, for a dynamic release, materializes polyfills.
   → [versioning.md](versioning.md)
3. **Plugin compilation** — a JVM pass checks the plugin against the real kernel and
   writes the JS sources: kernel stubs from `rdma_api.json` plus the plugin's files with
   the Compose entry points and `open`-class subclasses rewritten; the JS compilation
   turns them into bundles. → [plugin_codegen.md](plugin_codegen.md)
4. **App build** — the app Gradle plugin compiles the bridges into `librdma_user`, adds
   the widget entries and bakes the plugin bundles. → [modules.md](modules.md)

## Runtime mechanisms

| Topic | Page |
|---|---|
| Bridge contract, JNI vs C ABI | [bridges.md](bridges.md) |
| Proxies, handles, prototypes, lifetime | [proxies_and_handles.md](proxies_and_handles.md) |
| `List` / `Map` | [collections.md](collections.md) |
| Type descriptors | [type_descriptors.md](type_descriptors.md) |
| Plugin functions held by the kernel | [blocks_and_lambdas.md](blocks_and_lambdas.md) |
| Compose protocol, state, effects | [compose_protocol.md](compose_protocol.md) |
| Hermes thread, rendezvous, queues, timers | [threading.md](threading.md) |
| Exceptions | [exceptions.md](exceptions.md) |
| `suspend` | [suspend.md](suspend.md) |
| Loading plugins, store, cache | [plugin_loading.md](plugin_loading.md) |
| Hotfixes and slots | [hotfix_slots.md](hotfix_slots.md) |
| `open` classes and the vtable | [subclassing_and_vtable.md](subclassing_and_vtable.md) |

## Working on the framework

[contributing.md](contributing.md) (build, publish, configuration) and
[testing.md](testing.md) (unit tests, the stand, e2e). Why things are the way they are:
[design/](../design/).
