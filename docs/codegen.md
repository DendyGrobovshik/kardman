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

1. **Kernel side** — an IR compiler plugin scans the `@RDMA` declarations in each
   kernel module (`:kernel:internal`, `:kernel:user:*`, …) and emits C++ JNI/JSI glue,
   Kotlin vtable scaffolding, a JSON manifest, and the base Compose protocol proxy.
2. **Plugin side** — a FIR compiler plugin resolves the plugin's original source
   against the merged manifest of the kernel modules visible to it and rewrites it
   into bridge calls, emitting `*_rdma.kt`.

The two stages are connected by `rdma_manifest.json`: each kernel module writes its own;
the plugin gradle plugin merges the manifests of the visible modules (internal + the
plugin's own user module) and passes them to the plugin compiler plugin.

### Multi-module namespacing + aggregate bridge

Each kernel module's generated C++ is wrapped in its own `facebook::rdma::<moduleId>`
namespace and its module-level files are suffixed (`RdmaBridge_internal.cpp`,
`RdmaJniCache_user_alice.cpp`, …) so multiple modules can be compiled into one
`librdma_user.so` without symbol/file collisions. The `rdma-app` plugin additionally
generates `RdmaBridgeAggregate.cpp`, which composes the per-module
`installUserBridge`/`initUserBridgeJniCaches`/`wrapUserObject` and owns the global
`createWithOverrides`/`wrapUserObject` dispatch (the runtime hooks accept a single
bridge). The `rdmaVtableDispatch` JNI export is emitted once per kernel module package.

### Cross-module `@RDMA` references

A kernel module's `@RDMA` signature may reference an `@RDMA` type declared in
**another** kernel module (e.g. a widget in `:kernel:user:*` taking a `Color`/`Dp`
from `:kernel:internal`). The generated glue must marshal such a type without
knowing its concrete C++ class — that class lives in the *other* module's namespace
and header set, which is invisible at this module's generation time.

To stay decoupled, the codegen is **type-erased**:

- every generated `{Class}NativeState` derives from a shared `RdmaObjectNativeState`
  (`rdma-runtime-android` header), which owns the global JVM ref and exposes a
  **non-virtual inline** `getObject()`;
- a `Ref` **parameter** is unpacked via
  `std::static_pointer_cast<RdmaObjectNativeState>(obj.getNativeState(r))->getObject()`
  — no concrete type, no cross-module include;
- a `Ref` **return** from the *same* module keeps the fast concrete
  `create{Class}Wrapper`; a cross-module return falls back to `wrapAny(...)`, which
  dispatches by runtime class name through the aggregate `wrapUserObject`.

Structural type classification (primitive / `List` / `Function*` / "everything else
is a handle") is what decides the marshal path — the codegen never needs the
referenced module's FQN set. The only cross-module lookup is in the **validator**,
which resolves the referenced FQN via `pluginContext.referenceClass(...)` and checks
for the `@RDMA` annotation to reject non-bridgeable types.

#### Why this is zero-overhead

`RdmaObjectNativeState` introduces **no virtual call**: `getObject()` is a plain
inline member load, and under single inheritance `std::static_pointer_cast` to the
base is a no-op (base sub-object at offset 0). The emitted machine code for
unpacking a parameter is therefore identical to the previous
`static_pointer_cast<ConcreteNativeState>` + inline accessor — the only difference
is the compile-time type name. The fast `create{Class}Wrapper` path is preserved
for same-module returns; `wrapAny` (which does an extra `GetObjectClass` +
class-name comparison) is used only for cross-module returns, which are rare.

## Stage 1 — kernel (IR) generation

Triggered by `:kernel:compileKotlinJvm`. The plugin (`IrGenerationExtension`)
walks the module IR and:

- extracts every `@RDMA` class (`RdmaClassExtractor`) and every `@RDMA`
  top-level function (`RdmaFunctionExtractor`); functions that are also
  `@Composable` are classified as **widgets**;
- serializes the model into `rdma_manifest.json` (`RdmaManifest { classes, functions }`);
- generates the C++ glue and the `Composer` proxy. There are two backends,
  selected by the `backend` option (`jni` for Android, `capi` for iOS):
  `CppGenerator`/`RdmaWidgetGenerator`/`RdmaComposerProxyGenerator` (JNI) vs
  `CAbiCppGenerator`/`CAbiWidgetGenerator`/`CAbiComposerProxyGenerator` (C ABI);
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

### The C ABI backend (iOS)

The `capi` backend replaces JNI with a **function-pointer registry** because a
Kotlin/Native framework cannot export C symbols for `@CName` functions (they are
exported only as Objective-C methods). See [ios_design.md](ios_design.md).

Concretely, for each kernel module the backend emits:

| File | Content |
|------|---------|
| `RdmaCAbi_<mod>.kt` | C-compatible functions (`rdma_<mod>_<Class>_<member>`, `typeIdOf`, `setVtable`) + the `@EagerInitialization`/`staticCFunction` registration |
| `RdmaCAbi_<mod>.h` | `inline` wrappers that call the registered functions via `rdma_lookupFunction` |
| `RdmaWidgetEntries_<mod>.kt` | widget entry **stubs** + content/callback lambda helpers + registration |
| `RdmaWidgetBridge_<mod>.h/cpp` | widget `HostFunction`s (no `Composer`/`changed` crossing) |
| `{Class}Proxy.h/cpp`, `RdmaBridge_<mod>.h/cpp` | same proxy structure as JNI, but the leaves call the `rdma_<mod>_*` wrappers |

Three things differ from the JNI backend:

1. **String crossing** — `String` is not C-compatible, so it crosses as
   `const char*` (`CPointer<ByteVar>`); `rdmaToKString`/`rdmaStringToCStr`
   convert on the Kotlin side.
2. **Composer** — never crosses; the shim holds `currentComposer` in a global and
   `rdmaGetCurrentComposer()` returns it. Composer proxy methods and widget
   wrappers read the global instead of taking a `Composer` argument.
3. **Widget wrappers are IR** — `CAbiWidgetIrGenerator` (an
   `IrGenerationExtension` running after the compose compiler) fills the stub
   body with a direct IR call to the *already-lowered* `@Composable` widget, e.g.
   `Text(rdmaToKString(text), rdmaGetCurrentComposer() as Composer, 0)`.

The build is two-pass: pass N generates the `capi/kotlin` sources, pass N+1
compiles them and the IR pass injects the widget bodies. `RdmaFunctionExtractor`
fails fast if a `@Composable @RDMA` function is not yet lowered (missing
`$composer`), which guards the plugin ordering against future changes.


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
| `LaunchedEffect(keys) { ... }` | `rdmaLaunchedEffect(keys) { ... }` |
| `rememberCoroutineScope()` | `rdmaRememberCoroutineScope()` |
| `Alignment.Center` (companion `val`) | `rdmaAlignmentCenter()` |

Method calls on `@RDMA` receivers are left untouched — they dispatch dynamically
on the JS proxy. The `ComposeAllowlist` checker rejects any `androidx.compose.*`
call/import outside the base protocol (`Composable`, `remember`, `mutableStateOf`,
`getValue`, `setValue`, plus the bridged `SideEffect`, `DisposableEffect`,
`LaunchedEffect` and `rememberCoroutineScope` symbols).
`SideEffect`/`DisposableEffect`/`LaunchedEffect` are rewritten to the
kernel-hosted `rdmaSideEffect`/`rdmaDisposableEffect`/`rdmaLaunchedEffect`; their
bodies stay in the plugin (JS). `LaunchedEffect` reuses the `DisposableEffect`
bridge and simply launches the `suspend` body on `Dispatchers.Main`.

The generated guest-side bridge files (written by the Gradle plugin +
compiler plugin) complete the picture:

- `RdmaRuntimeBridge.kt` — `external object RDMA` + `rdmaRunApp`/`rdmaMutableStateOf`
  and the `rdmaSideEffect`/`rdmaDisposableEffect`/`rdmaLaunchedEffect`/
  `rdmaRememberCoroutineScope` helpers (the DisposableEffect ones carry the
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
