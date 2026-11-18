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
# Kernel code generation

The kernel compiler plugin (`:rdma-kernel-compiler-plugin`, an `IrGenerationExtension`)
runs on every kernel module compilation. It turns the module's `@RDMA` declarations into
the bridge for its platform, the API description for plugins, and the input of a release.
The plugin side is [plugin_codegen.md](plugin_codegen.md).

## Steps

1. **Extract** — `RdmaClassExtractor` and `RdmaFunctionExtractor` walk the module IR and
   build the model of `:rdma-types`: classes (constructor, properties, methods, companion
   statics, superclass, `open`), enums, top-level functions; a `@Composable` function is a
   **widget**. A `suspend` function is recorded with its logical result type (the
   `Continuation` parameter stripped). A parameter of a function type records its full
   type (`functionType`).
2. **Validate** — every type in a signature must be a boundary type
   ([types.md](../guide/types.md)); a referenced class from another module must itself be
   `@RDMA` (looked up through the dependency classpath). Defaults must be trailing; a
   lambda default must be `{}`.
3. **Write the API description** — `rdma_api.json`: the module's `@RDMA` surface, read by
   the plugin compiler to generate stubs.
4. **Write the release input** — `kernel_manifest_<platform>.json` (`android` or `ios`):
   every declaration's hash, signature, source and `hotfixBlocker`, built by
   `RdmaSymbolIndexer`. See [versioning.md](versioning.md).
5. **Generate the bridge** for the module's backend (below).
6. **Transform the IR** — `RdmaSlotTransformer` adds the hotfix slot prologue to slotted
   functions ([hotfix_slots.md](hotfix_slots.md)); `RdmaVtableTransformer` adds the vtable
   check to `open` methods ([subclassing_and_vtable.md](subclassing_and_vtable.md)).

## Output

Everything goes to `<module>/build/generated/rdma/`:

| Path | Content |
|---|---|
| `rdma_api.json` | API description for plugins |
| `kernel_manifest_<platform>.json` | release input |
| `cpp/` | JNI backend (Android) |
| `capi/cpp/`, `capi/kotlin/` | C ABI backend (iOS) |
| `widget-kotlin/` | Kotlin compiled into the Android app: widget entries, default-parameter wrappers, `suspend` bridges |
| `kotlin/`, `androidMain/`, `iosMain/` | static files written by the kernel Gradle plugin before compilation: slot cell and vtable declarations |

### JNI backend (`backend=jni`, Android)

| File | Content |
|---|---|
| `{Class}Proxy.h/cpp` | the class's prototype (methods, getters, setters as host functions) and wrapper creation |
| `RdmaJniCache_<mod>.h/cpp` | `jclass`/`jmethodID`s, looked up once at init |
| `RdmaBridge_<mod>.h/cpp` | `installUserBridge`: constructors, functions, statics, enums on `RDMA`; `typeIdOf`-based wrapping; `createWithOverrides` |
| `RdmaWidgetBridge_<mod>.h/cpp` | one host function per widget (`RDMA.compose<Name>`) |
| `RdmaComposerProxy.h/cpp` | the Composer proxy — framework-owned, compiled into the runtime, not the app |

Generators: `CppGenerator`, `RdmaWidgetGenerator`, `RdmaComposerProxyGenerator`.

### C ABI backend (`backend=capi`, iOS)

Kotlin/Native cannot export C symbols from a framework, so the C++ side calls Kotlin
through a function-pointer registry ([bridges.md](bridges.md#the-function-registry-ios)).

| File | Content |
|---|---|
| `capi/kotlin/RdmaCAbi_<mod>.kt` | C-compatible functions `rdma_<mod>_<Class>_<member>`, `typeIdOf`, `setVtable`, registered at startup |
| `capi/cpp/RdmaCAbi_<mod>.h` | inline C++ wrappers that call the registered functions by name |
| `capi/kotlin/RdmaWidgetEntries_<mod>.kt` | widget entry stubs and lambda helpers |
| `capi/cpp/RdmaWidgetBridge_<mod>.h/cpp`, `{Class}Proxy.*`, `RdmaBridge_<mod>.*` | as for JNI, with C ABI leaves |

Generators: `CAbiCppGenerator`, `CAbiKotlinGenerator`, `CAbiWidgetGenerator`,
`CAbiComposerProxyGenerator`. Differences from JNI: a `String` crosses as `const char*`;
the Composer never crosses (the shim keeps the current one in a global); a lambda argument
crosses as its block id and is wrapped into a Kotlin lambda holding a block holder.

## Widget entries

A widget is called from C++ with plugin arguments. The entry converts them (block ids into
remembered lambdas, raw values into parameters, defaults through a mask) and calls the
widget.

- **Android**: `widget-kotlin/RdmaWidgetEntries_<mod>.kt` declares one `@Composable
  compose<Name>(…)` per widget; the app compiles it, so the Compose compiler lowers it,
  and C++ calls it through JNI with the current composer. A primitive parameter crosses
  unboxed; lambda lists cross as lists of block ids.
- **iOS**: a widget entry must call the *lowered* widget with an explicit composer, which
  source code cannot express. The C ABI entry is a stub whose body
  `CAbiWidgetIrGenerator` fills with an IR call. The glue (`capi/kotlin`) is generated by
  the kernel's iOS compilation but **compiled by the app's iOS compilation**: the app
  Gradle plugin adds every kernel's `capi/kotlin` to the app's `iosMain` and applies the
  kernel compiler plugin there in `capi-glue` mode, which runs `CAbiWidgetIrGenerator`
  against the widgets listed in `rdma_api.json`. There each widget gets a source-level
  `@Composable` trampoline (`<prefix>compose<Name>_widget`), lowered in the app
  compilation; the C entry calls the lowered trampoline. A clean build works in one pass,
  and a removed `@RDMA` symbol cannot leave stale glue behind. `RdmaFunctionExtractor`
  fails fast if a `@Composable @RDMA` function is not lowered yet (no `$composer`).

## Default parameters

A function with defaults gets a wrapper `<name>_rdma_default(args…, mask)`
(`RdmaDefaultWrapperGenerator`, in `widget-kotlin/`) that re-materializes each default
whose mask bit is set. The bridge sets a bit for every argument that is missing or
`undefined`. Function types in wrappers are rendered as Kotlin function types.

## `suspend`

Each `suspend` function gets a plain `_<name>Async(completionId, args…)` wrapper
(`widget-kotlin/RdmaSuspendBridge.kt`) that launches the call and completes the plugin's
continuation. See [suspend.md](suspend.md).

## Several kernel modules in one app

Each module's C++ lives in its own namespace `facebook::rdma::<moduleId>` and its
module-level files carry the module suffix, so all modules compile into one
`librdma_user`. The app plugin's aggregate bridge composes the per-module
`installUserBridge`/cache init and dispatches `wrapUserObject`/`createWithOverrides` across
modules.

A signature may reference an `@RDMA` type of another module. The generated code stays
type-erased: every `{Class}NativeState` derives from `RdmaObjectNativeState`, so a
parameter is unpacked without knowing its concrete class (a non-virtual inline load, no
extra cost), and a cross-module result is wrapped by type id through the aggregate.

## The Composer proxy

`RdmaComposerProxy.h/cpp` is generated from a version-locked method list
(`RdmaComposerProtocol.baseProtocol`), always, independent of `@RDMA` declarations. The
list is checked against the resolved `androidx.compose.runtime.Composer`, so a Compose
upgrade that changes the interface fails the build. See
[compose_protocol.md](compose_protocol.md).

## Regeneration

The plugin deletes its stale output before writing, so a removed `@RDMA` declaration
leaves no proxy behind. On Android the app plugin copies the generated C++
(`copyGeneratedCpp`) and forces CMake to reconfigure when the file set changes
(`invalidateCmake`).
