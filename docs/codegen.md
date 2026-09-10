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
# Code Generation Pipeline

This document describes how source turns into the proxy/runtime artifacts. For
the runtime data flow (what happens after generation) see
[architecture.md](architecture.md); for per-module responsibilities see
[modules_architecture.md](modules_architecture.md).

Code generation happens in two independent stages:

1. **Kernel side** — an IR compiler plugin scans the `@RDMA` declarations in
   `:kernel` and emits C++ JNI/JSI glue, Kotlin vtable scaffolding, a JSON
   manifest, and the base Compose protocol proxy.
2. **Plugin side** — a FIR compiler plugin resolves the plugin's original source
   against `:kernel` and rewrites it into bridge calls, emitting `*_rdma.kt`.

The two stages are connected by `rdma_manifest.json`: the kernel plugin writes
it, the plugin plugin reads it to know which classes/functions are bridgeable.

## Stage 1 — kernel (IR) generation

Triggered by `:kernel:compileKotlinJvm`. The plugin (`IrGenerationExtension`)
walks the module IR and:

- extracts every `@RDMA` class (`RdmaClassExtractor`) and every `@RDMA`
  top-level function (`RdmaFunctionExtractor`); functions that are also
  `@Composable` are classified as **widgets**;
- serializes the model into `rdma_manifest.json` (`RdmaManifest { classes, functions }`);
- generates the C++ glue (`CppGenerator`, `RdmaWidgetGenerator`) and the
  `Composer` proxy (`RdmaComposerProxyGenerator`);
- injects the vtable (`RdmaVtableTransformer`) directly into the IR.

Generated files in `kernel/build/generated/rdma/`:

| File | Content |
|------|---------|
| `RdmaJniCache.h/cpp` | `jclass`/`jmethodID` cache per `@RDMA` class |
| `{Class}Proxy.h/cpp` | `{Class}NativeState` + prototype `HostFunction`s for properties/methods |
| `RdmaBridge.h/cpp` | `installUserBridge()`, factory registrations, `createWithOverrides` |
| `RdmaWidgetBridge.h/cpp` | one `HostFunction` per widget (`RDMA.composeXxx`) + widget JNI cache |
| `RdmaWidgetEntries.kt` | one `@Composable` host-side entry per widget |
| `RdmaComposerProxy.h/cpp` | base `Composer` protocol proxy (framework-owned) |
| `rdma_manifest.json` | serialized `RdmaManifest` |
| `kotlin/RdmaVtable.kt` | `external fun rdmaVtableDispatch(...)` (written by the Gradle plugin) |

### The vtable mechanism

`open` methods of an `@RDMA` class can be overridden from the plugin. To make
that work the plugin:

1. adds a `__vtable: Long` field to each `@RDMA` class (`RdmaVtableTransformer`);
2. in every `open` method, checks the field: if it points to a `RdmaVtable`,
   dispatch to the JS override, otherwise call the JVM method normally.

The C++ side (`createWithOverrides`) allocates a `RdmaVtable`, stores its pointer
in `__vtable`, and the generated `{Class}_<method>` stub consults it before
falling through to the JNI call.

### The base Compose protocol

`RdmaComposerProxy.h/cpp` is generated from a version-locked method list
(`RdmaComposerProtocol.baseProtocol`) and is **always** regenerated, independent
of any `@RDMA` class. Before generating, the plugin cross-checks the list against
the resolved `androidx.compose.runtime.Composer` IR and fails the build if a
method is missing (compose-runtime version drift). This proxy is compiled into the
framework runtime, not the user bridge.

## Stage 2 — plugin (FIR) rewrite

Triggered by `:plugin:compileKotlinJvm` (the resolve pass). The FIR plugin
(`FirAdditionalCheckersExtension`) registers checkers that inspect the resolved
tree and record source edits; the `IrGenerationExtension` flush point applies
them to the original text and writes `*_rdma.kt` into
`plugin/build/generated/rdma/`.

Rewrite rules (all offsets are against the original source text):

| Original | Rewritten to |
|----------|--------------|
| `Person("str", 42)` | `js("RDMA.createPerson('str', 42)")` |
| `p.name` | `p.getName()` |
| `p.status = v` | `p.setStatus(v)` |
| `class Cyborg(...) : Person(...) { override fun greet() = "hi" }` | `js("""RDMA.createWithOverrides('Person', [...], { greet: function() { return "hi"; } })""")` |
| `Text(...)` (widget) | `rdmaText(...)` |
| `runRdmaApp { ... }` | `rdmaRunApp { ... }` |
| `mutableStateOf(x)` | `rdmaMutableStateOf(x)` |
| `SideEffect { ... }` | `rdmaSideEffect { ... }` |
| `DisposableEffect(keys) { ... }` | `rdmaDisposableEffect(keys) { ... }` |
| `Alignment.Center` (companion `val`) | `rdmaAlignmentCenter()` |

Method calls on `@RDMA` receivers are left untouched — they dispatch dynamically
on the JS proxy. The `ComposeAllowlist` checker rejects any `androidx.compose.*`
call/import outside the base protocol (`Composable`, `remember`, `mutableStateOf`,
`getValue`, `setValue`, plus the bridged `SideEffect` and `DisposableEffect`
symbols). `SideEffect`/`DisposableEffect` are rewritten to the kernel-hosted
`rdmaSideEffect`/`rdmaDisposableEffect`; their bodies stay in the plugin (JS).

The generated guest-side bridge files (written by the Gradle plugin +
compiler plugin) complete the picture:

- `RdmaRuntimeBridge.kt` — `external object RDMA` + `rdmaRunApp`/`rdmaMutableStateOf`
  and the `rdmaSideEffect`/`rdmaDisposableEffect` helpers (the latter carries the
  guest-side `RdmaDisposableEffectScope` stub)
- `RdmaWidgetBridge.kt` — per-widget `rdmaXxx` stubs that call `RDMA.composeXxx`

`jsMain` compiles **only** `build/generated/rdma/`, so the JVM-only original
source never reaches the JS compiler.

## End-to-end flow

```
┌─────────────────────────────────┐
│ 1. Kernel @RDMA classes         │
│    kernel/src/commonMain/...    │
└───────────────┬─────────────────┘
                │ :kernel:compileKotlinJvm (kernel compiler plugin)
                ▼
┌─────────────────────────────────┐
│ 2. Generate C++ + JSON + vtable │
│    kernel/build/generated/rdma/ │
│    ├── PersonProxy.cpp          │
│    ├── RdmaJniCache.cpp         │
│    ├── RdmaBridge.cpp           │
│    ├── RdmaWidgetBridge.cpp     │
│    ├── rdma_manifest.json       │
│    └── kotlin/RdmaVtable.kt     │
└───────────────┬─────────────────┘
                │ copyGeneratedCpp (excludes RdmaComposerProxy.*)
                ▼
┌─────────────────────────────────┐
│ 3. Copy to the app module       │
│    androidApp/build/generated/  │
│    rdma/cpp/generated/          │
└─────────────────────────────────┘
                │ CMake / NDK (links rdma-runtime-android prefab)
                ▼
┌─────────────────────────────────┐
│ 4. Compile C++ → librdma_user.so│
│    (librdma_runtime.so is the   │
│     generic runtime AAR)        │
└─────────────────────────────────┘

┌─────────────────────────────────┐
│ 5. Plugin original source       │
│    plugin/src/kotlin/           │
│    Main.kt                      │
└───────────────┬─────────────────┘
                │ :plugin:compileKotlinJvm (FIR resolve pass)
                ▼
┌─────────────────────────────────┐
│ 6. Generate transformed code    │
│    plugin/build/generated/rdma/ │
│    Main_rdma.kt                 │
└───────────────┬─────────────────┘
                │ Kotlin/JS compiler
                ▼
┌─────────────────────────────────┐
│ 7. Compile JS → plugin.js       │
└───────────────┬─────────────────┘
                │ assets
                ▼
┌─────────────────────────────────┐
│ 8. APK                          │
│    ├── librdma_runtime.so       │
│    ├── librdma_user.so          │
│    ├── plugin.js                │
│    ├── kotlin-kotlin-stdlib.js  │
│    └── classes.dex              │
└─────────────────────────────────┘
```

## Regeneration & invalidation

- **`copyGeneratedCpp`** (registered on the app module by the `rdma-app` plugin)
  copies the generated `*.h`/`*.cpp` from `kernel/build/generated/rdma/cpp/` into
  `androidApp/build/generated/rdma/cpp/generated/`, excluding the framework-owned
  `RdmaComposerProxy.*`.
- **`invalidateCmake`** deletes `.cxx` whenever the copied sources change, so the
  `file(GLOB)` in CMake picks up newly added/removed classes.
- The kernel plugin deletes stale generated C++ before regenerating, so removing
  an `@RDMA` class/function doesn't leave a dangling proxy referencing a missing
  JNI cache entry.
