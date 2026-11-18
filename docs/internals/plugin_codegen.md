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
# Plugin code generation

A plugin is compiled twice:

1. **JVM pass (resolve pass)** — the original plugin source compiles against the *real*
   kernel. The plugin compiler plugin (`:rdma-plugin-compiler-plugin`: FIR checkers plus an
   `IrGenerationExtension` as the flush point) checks it and writes the JS sources into
   `build/generated/rdma/`.
2. **JS pass** — the JS compilation compiles only `build/generated/rdma/`, so the JVM-only
   original source never reaches it.

`build/generated/rdma/` holds:

| File | Written by | Content |
|---|---|---|
| `RdmaKernelStubs_<package>.kt` | compiler plugin | kernel stubs, one file per kernel package |
| `<File>_rdma.kt` | compiler plugin | the plugin's files with the few rewrites below |
| `RdmaRuntimeBridge.kt` | Gradle plugin | the guest bridge: `external object RDMA`, `rdmaRunApp`, state factories, effect helpers, collection classifiers, prototype hooks |
| `plugin.json` | compiler plugin | the plugin contract: id, the kernel symbols it uses, the kernel version it was built against |

## Kernel stubs

`RdmaKernelStubGenerator` turns each kernel module's `rdma_api.json` into ordinary Kotlin
declarations in the kernel's own packages, with the real names and types. Every member is
`inline` and its body is exactly the [call form](#call-form-v1). Plugin code — imports,
constructors, properties, methods, statics, enums, `suspend` calls — therefore compiles
**unchanged and fully typed**, and after inlining emits the calls the host serves.

```kotlin
// generated (simplified)
@PublishedApi @JsName("RDMA") internal external object RdmaAbi

class Person private constructor(rdmaNoInstances: kotlin.Nothing) {
    val name: String inline get() = asDynamic().getName().unsafeCast<String>()
    inline fun greet(): String = asDynamic().greet().unsafeCast<String>()
}
inline fun Person(name: String, age: Int): Person = RdmaAbi.createPerson(name, age).unsafeCast<Person>()
```

- **Classes** have a single private constructor taking `Nothing`, so they cannot be
  instantiated; construction goes through a same-named `inline fun` factory. A superclass
  stub is `open` with a `protected` constructor; a subclass stub extends it and does not
  repeat inherited members.
- **Enums** are classes whose runtime value *is* the entry-name string
  (`HttpMethod.GET` → `"GET"`); not a `value class`, which would be boxed in generic
  positions (`List<HttpMethod>`, `MutableState<HttpMethod>`, lambda parameters).
- **Widgets** are non-inline `@Composable` functions, so the Compose compiler handles them
  as usual; the body calls `RdmaAbi.compose<Name>(…)`.
- **`suspend`** stubs are `suspend inline` and use `suspendCoroutine` +
  `RDMA.registerContinuation` ([suspend.md](suspend.md)).
- **Lambda parameters** are registered: `RDMA.registerBlock(fn, "<pluginId>")`.
- **Omitted defaulted arguments** pass `undefined`; the kernel fills the default.
- `runRdmaApp { … }` forwards to `rdmaRunApp`.
- Internal names in stub bodies carry an `rdma` prefix so they never shadow user names.

### Call form v1

The call form is the contract between plugin bundles and hosts. Old plugins keep using
it, so it changes only additively; an incompatible change would need a new toolchain
generation ([toolchain_compatibility.md](../design/toolchain_compatibility.md)).

| Kernel symbol | JS call form |
|---|---|
| constructor | `RDMA.create<Class>(args…)` |
| plugin subclass of an `open` class | `RDMA.createWithOverrides('<Class>', [args…], { <method>: fn, … })` |
| property get / set | `obj.get<Name>()` / `obj.set<Name>(v)` |
| method | `obj.<name>(args…)` |
| top-level function | `RDMA.<name>(args…)` |
| `suspend` function / method | `RDMA._<name>Async(id, args…)` / `RDMA._<Class>_<m>Async(id, self, args…)`, `id` from `RDMA.registerContinuation` |
| widget | `RDMA.compose<Name>(args…)` |
| companion `val` | `RDMA.<class><Name>()` |
| enum entry / `ordinal` / `valueOf` | name string / `RDMA.<enum>Ordinal(name)` / `RDMA.<enum>ValueOf(name)` |
| lambda argument | id from `RDMA.registerBlock(fn, pluginId)` |
| defaulted trailing argument | omitted / `undefined` |
| state factories | `RDMA.mutableStateOf(v, desc)`, `RDMA.mutableStateListOf(…)`, `RDMA.mutableStateMapOf(…)` |
| values | primitives by value, `Long` as the plugin's `Long` object, collections as proxies, `null` as `null` |

## Rewrites of the original source

The JVM pass records text edits against the original source (offsets in the file) and
writes the edited file as `<File>_rdma.kt`. Only constructs that need information the JS
compilation does not have are rewritten:

| Original | Rewritten to | Why |
|---|---|---|
| `mutableStateOf(x)` (also `mutableIntStateOf`, `mutableStateListOf`, `mutableStateMapOf`) | `rdmaMutableStateOf(x, typeDesc = "<desc>")` | the descriptor of the call's static type ([type_descriptors.md](type_descriptors.md)) |
| `SideEffect` / `DisposableEffect` / `LaunchedEffect` / `rememberCoroutineScope` | `rdmaSideEffect` / `rdmaDisposableEffect` / `rdmaLaunchedEffect` / `rdmaRememberCoroutineScope` | effects are hosted by the kernel ([compose_protocol.md](compose_protocol.md#effects)) |
| `class Cyborg(…) : Person(…) { override fun greet() = "hi" }` | declaration removed; each constructor call becomes `RDMA.createWithOverrides('Person', [...], { greet: function() { return "hi"; } })` | [subclassing_and_vtable.md](subclassing_and_vtable.md) |
| a `when` over an `@RDMA` enum without `else` | `else -> throw NoWhenBranchMatchedException()` added | exhaustive against the real enum, not against the stub; a newer kernel may add entries |

Subclass constructor calls are rewritten at the flush point, after every file has been
checked, so the result does not depend on the order in which files are checked.

## Checks

The same pass rejects, with a message naming the reason:

- Compose symbols outside the base protocol (`ComposeAllowlist`);
- kernel symbols of modules the plugin may not see;
- `is` / `as` / `as?` and reified checks (`filterIsInstance<Person>()`) against a kernel
  class or enum — a kernel object is a proxy, not an instance of the stub class;
- `Enum.values()` / `Enum.entries` of a kernel enum;
- state factories whose type is not concrete (`Any`, `Any?`, `dynamic`).

It also records the kernel symbols the plugin uses into `plugin.json`, which the store uses
to decide compatibility ([compatibility.md](../design/compatibility.md)).

## Why not an IR lowering in the JS compilation

The stubs make an IR pass for kernel symbols unnecessary: inlining does the work, at zero
runtime cost. The Compose entry points still need the JVM pass — doing them as an IR
lowering in the JS compilation would depend on the order of our extension relative to the
Compose compiler, which is not under our control. Background:
[runtime_rework.md §5.0](../design/runtime_rework.md#50-prototype-results-and-the-revised-approach--proposal-d7).
