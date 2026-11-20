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
# Runtime rework: object model, lifetime, typed plugin surface, hotfix dispatch

**Status:** stages 1–4 implemented and verified (2026-11-18); follow-ups are in the
[backlog](#11-backlog). This is the decision record; the current mechanisms are described
in [internals/](../internals/overview.md).

This document is the design and the plan for one coordinated rework of the bridge.
It has four implementation stages and one verification stage:

| Stage | Topic | Main outcome |
|---|---|---|
| 1 | Object model on shared prototypes | values from the kernel are real Kotlin objects; no per-crossing overhead beyond the call itself |
| 2 | Lifetime and callbacks | no leaks of JS blocks / remembered values / strings; typed callback arguments |
| 3 | Typed plugin surface (A3) | plugin source compiles as-is against typed kernel declarations; no `dynamic`, no `js("")` |
| 4 | Hotfix dispatch slots | a hotfixed `@RDMA` callable is used by native callers too (no split implementation) |
| 5 | Bring-up, verification, tests | build, run, measure, close the test gaps |

The stages are ordered so that each one only relies on the previous ones. Stages 1–4
are implemented first; building and testing the reworked project is stage 5 (see
[§8](#8-implementation-order-and-bring-up) for the bring-up policy).

## 1. Why

The trigger: an object that crosses from native to JS does not behave like an object
created in JS — `forEach`/`map`/`filter` fail on it. The study of the code found that
this has two independent causes, and several more problems of the same nature.

| # | Problem | Where | Stage |
|---|---|---|---|
| P1 | Plugin code sees kernel values as `dynamic`; Kotlin extension functions (`map`, `forEach`, …) are not resolved and compile to non-existent JS methods | `RdmaPluginTransformState.kt` (bridges return `dynamic`), `js("RDMA.createX(...)")` | 3 |
| P2 | Kernel-created collections are not Kotlin collections: a plain `jsi::Object` on Android (`ListHandle.cpp:781`), a `HostObject` on iOS. `is List` is false, `as MutableList` throws, stdlib takes fallback paths | `ListHandle.cpp`, `MapHandle.cpp`, `RdmaComposeCAbi.cpp` | 1 |
| P3 | Typed iteration over `Map.keys/values/entries` is broken — for kernel maps **and** for plugin maps after their first crossing (views only have `size()`/`get(index)`); index access is O(n), iteration O(n²) | `MapHandle.cpp:137` | 1 |
| P4 | Per-crossing cost: ~25 host functions + aliases + `evaluateJavaScript` per collection on Android; a new `jsi::Function` per property access on iOS; per-instance method copying for `@RDMA` objects | `ListHandle.cpp`, `MapHandle.cpp`, `CppGenerator.kt:emitClassSurface`, `ListCAbiHost::get` | 1 |
| P5 | Compose protocol proxies (`ComposerProxyHost`, `StateProxyHost`, `ScopeUpdateScopeProxyHost`) are `HostObject`s that allocate a function per call — on the scroll hot path | `RdmaComposerProxy.cpp`, `RdmaCompose.cpp` | 1 |
| P6 | Per-call JNI lookups: `unboxJni` does 7 `FindClass` per value, list ops do `GetObjectClass`+`GetMethodID` per call, `wrapUserObject` does `FindClass`+`getName`+string chain per module; local refs not released in loops | `RdmaCompose.cpp`, `ListHandle.cpp`, `CppGenerator.kt` | 1 |
| P7 | Layout hack `*(jobject*)((char*)ns.get() + 16)` in generated code | `CppGenerator.kt:574` | 1 |
| P8 | JS blocks are never freed: every callback, widget lambda, content block and `updateScope` block stays in `g_scopeBlocks` forever (only effects are freed). Closures pin JS objects and, through their `NativeState`, kernel objects | `RdmaCompose.cpp:911,1216`, `RdmaLambda.kt` | 2 |
| P9 | `remember { jsObject }` values (`g_jsValues`) are never freed | `RdmaComposerProxy.cpp:394` | 2 |
| P10 | iOS: every kernel→JS string is allocated in `cstringArena`, which is never cleared | `CAbiShim.kt` | 2 |
| P11 | `List`/`Map` arguments of callbacks arrive as `undefined` (callbacks are marshalled by `wrapAny`, without a descriptor) — on both platforms, although the validator accepts them | `RdmaCompose.cpp:runCallback`, `RdmaComposeCAbi.cpp:runCallbackOrLambda` | 2 |
| P12 | `remember { 1.5 }` returns `1` (`boxJsi` boxes every number as `Integer`) | `RdmaCompose.cpp:boxJsi` | 2 |
| P13 | Kernel widgets receive a new lambda on every recomposition and can never be skipped | `RdmaWidgetGenerator.kt:entry` | 2 |
| P14 | A hotfix (polyfill) replaces only the JS-visible entry; native callers keep calling the baked-in implementation (split implementation) | [compatibility.md §6.4](compatibility.md) | 4 |
| P15 | The documentation describes a design the code does not follow (`collections.md`: "factory creates an ArrayList"; `architecture.md`: "prototype HostFunctions"; ORIGINAL_DESIGN's `createWithPrototype` is not used) | docs | 1 |

## 2. Invariants (what must not break)

### 2.1 Compatibility

The compatibility model of [compatibility.md](compatibility.md) and
[toolchain_compatibility.md](toolchain_compatibility.md) is unchanged:

- a **new plugin on an old host** runs; kernel symbols the old host lacks arrive as
  `F` polyfills and register themselves on `RDMA`;
- an **old plugin on a new host** runs;
- everything else described in those documents keeps working.

What this rework adds is an explicit name for something that was implicit: the
**call form** — the exact JS shape in which plugin code addresses kernel symbols and
in which kernel values look in JS (`RDMA.createPerson(...)`, `p.getName()`, enum entries
as name strings, lambdas as `registerBlock` ids, `Long` as the plugin's `Long` object,
…). The call form is a global rule, not a per-symbol property, so it is not recorded
in the changelog. It is fixed and versioned in stage 3 ([§5.1](#51-the-call-form-and-its-version)).

Consequences for stages 1–2 (before the call form is versioned):

- **Old bundles keep their exact call form.** Every name an old bundle may call stays
  available on the new objects: plain names (`get`, `size`, `add`, `getName`, …) for
  `dynamic` code and compile-mangled names for typed code. `RDMA.*` entry points and
  their argument shapes do not change.
- **New guest-bridge hooks are optional.** The guest bridge (`RdmaRuntimeBridge.kt`) is
  compiled into every plugin bundle. Any new hook it provides is an extra `RDMA` field:
  an old host ignores it; a new host falls back to the current behaviour when no loaded
  bundle provides it.
- **The plugin compiler output does not change in stages 1–2.** New plugins therefore
  run on old hosts exactly as today.

### 2.2 Lifetime (GC)

As in [ORIGINAL_DESIGN.md](ORIGINAL_DESIGN.md): a handle keeps the kernel object alive
exactly as long as the JS object holding it is alive; the handle lives in a
`jsi::NativeState` and is released from its destructor (`DeleteGlobalRef` /
`rdma_disposeStableRef`). Prototypes never hold handles. Stage 2 extends the same
guarantee to the reverse direction (kernel holding JS blocks).

### 2.3 Threading

Unchanged ([threading.md](../internals/threading.md)):
JS objects are created and touched only on the Hermes thread; compose ops rendezvous
to the UI thread; async callbacks go through the low-priority queue. Stage 4 adds one
new path (a blocking call into JS from a thread that is neither UI nor Hermes).

### 2.4 Exceptions

Unchanged ([exception_propagation.md](exception_propagation.md)): every path keeps
propagating exceptions in both directions with the original type, message, stack and
`cause` chain.

### 2.5 Performance principles

The crossing itself (~12 µs measured) is the budget; nothing else may add a comparable
cost. Concretely, on any per-call or per-crossing path there is:

- no allocation of `jsi::Function`s, no property installation on instances;
- no lookup by string (`FindClass`, `GetMethodID`, `HostObject::get` + `utf8()`,
  `rt.global().getProperty(...)` per object);
- no JS source evaluation;
- no virtual dispatch or `dynamic_pointer_cast` where a tag check suffices;
- no JNI local reference that outlives its use in a loop.

Everything that is per-type is built **once per runtime**.

### 2.6 Threading invariant

**JS is executed only by the Hermes thread, always.** No other thread runs JS, not even
while the Hermes thread is idle or parked in native code (borrowing, D6, is rejected for
this reason, 2026-11-18). Every native → JS call from another thread switches to the
Hermes thread: the UI thread through the rendezvous, other threads through the low queue with a timeout (D5 = B, §6.4).
A synchronous JS → native call runs on the Hermes thread (no switch); Compose operations
are the exception and switch to the UI thread.

### 2.7 Hotfix invariant

**A hotfixed symbol runs its new version everywhere** — for plugin (JS) callers, for
other polyfills and for native call sites alike. No place may keep calling the old
version while another already calls the new one. Where a call site cannot be redirected
yet, the hotfix must not be delivered as a partial one (fixed 2026-11-18; how this is
enforced: §6.8).

### 2.8 Exception invariant

**Exceptions always cross between the runtimes**, in both directions, with their
`cause` chain. The only exception is fire-and-forget work (async callbacks, lambdas,
continuations, timers): there is no caller to receive it, so it goes to the global
uncaught-exception handler — never only to the log (fixed 2026-11-18).

## 3. Stage 1 — object model on shared prototypes

### 3.1 Shape

Every JS-side proxy is a plain JS object with a `NativeState` (the handle and its
descriptor) whose prototype is a **shared, per-runtime prototype** carrying host
functions. For collections that prototype sits between the object and the Kotlin
stdlib prototype, so the object *is* a Kotlin collection:

```
proxy object             — NativeState only (handle + element descriptor + flags)
  └─ NativeListProto     — host functions under compile-mangled names (get_anv4hu_k$, add_…, …),
                           plain names for old bundles (get, add, sizeImpl, …), `size` accessor
      └─ ArrayList.prototype        — its storage methods are shadowed and never run
          └─ AbstractMutableList.prototype — iterator, equals, contains, subList, …:
                                              built on get/size → land in NativeListProto
```

Why this is correct: Kotlin/JS checks interfaces as `obj[iface.Symbol] === true`
(`isInterface` in the compiled stdlib) and classes with `instanceof`; both follow the
prototype chain. So `is List`, `is MutableList`, `is ArrayList`, `as MutableList` hold,
and every stdlib function works.

Why the shadowing is complete: the only methods in the compiled stdlib that read
`ArrayList.array_1` (the JS backing array) are `ArrayList`'s own methods `get_size`,
`get`, `set`, both `add`, both `addAll`, `removeAt`, `remove`, `removeRange`, `clear`,
`indexOf`, `lastIndexOf`, `toString`, both `toArray`, `asJsArrayView`. All of them
except `asJsArrayView` (outside the contract) are storage methods the proxy prototype
implements. Two guards keep this true across Kotlin upgrades:

- **Build-time guard (per runtime):** when the proxy prototype is built, every own
  method of `ArrayList.prototype` must either be shadowed or be on a known-safe list
  (`checkIsMutable`, `build`, `trimToSize`, `ensureCapacity`); anything else is a loud
  startup error, never a silent read of a missing array.
- **Generation test:** a test over the compiled stdlib of each toolchain generation
  lists every access to `array_1` (and the map storage fields) and fails on an
  unknown one. It runs as part of `generation-release`.

The same is done for `Map` over `LinkedHashMap.prototype` and its storage fields.

### 3.2 Common C++ layer

Today the collection logic exists twice (JNI: `ListHandle.cpp`/`MapHandle.cpp`; C ABI:
`RdmaComposeCAbi.cpp`) with different object models. It becomes one platform-agnostic
layer plus thin platform operations:

- `common/RdmaProxyCore.{h,cpp}` — prototype registry, prototype construction, all
  host functions (list, map, views, entries, iterators, `@RDMA` objects, compose
  protocol proxies), descriptor-driven marshalling dispatch.
- `common/RdmaHostOps.h` — the platform operations the core calls: `listSize`,
  `listGet`, `listSet`, `listAdd`, …, `mapGet`, `mapPut`, …, `snapshot`, value boxing and
  unboxing by descriptor, release of a handle. Declared once, implemented once per
  platform (`RdmaHostOpsJni.cpp`, `ios/RdmaHostOpsCAbi.cpp`). These are plain functions
  selected at link time — no virtual dispatch.
- One handle state hierarchy: `RdmaHandleState : jsi::NativeState` with a `kind` tag
  (`Object`, `List`, `Map`, `MapView`, `Iterator`, `State`, `Composer`, `Scope`) and the
  platform handle (`jobject` global ref / `StableRef`). Type checks are a tag compare
  plus `static_pointer_cast`. This removes `ListCAbiHost`, `MapCAbiHost`,
  `MapViewHost`, `MapEntrySetHost` and the `+16` layout hack (generated code calls
  `rdmaListHandleOf(obj)` / `rdmaMapHandleOf(obj)` from the runtime header).

The prototype registry is owned by a runtime-scoped object destroyed before the
runtime (not by function-local statics), so no `jsi::Object` outlives the runtime.

### 3.3 Discovering the stdlib prototypes and names

The runtime needs `ArrayList.prototype`, `LinkedHashMap.prototype`, the prototypes that
give `Set`/`Collection`/`Map.Entry` identity to views and entries, and the exact
compile-mangled method names of the plugin toolchain.

- The guest bridge gets one new hook, `RDMA.collectionPrototypes()`, returning the
  prototypes of live samples (`arrayListOf()`, `linkedMapOf()`, a sample entry, the
  sample's `keys`/`values`/`entries`). Mangled names are read directly from those
  prototypes (the existing `RDMA.listMethodNames`/`mapMethodNames` stay for old
  bundles).
- The stdlib lives in shared-runtime, so there is one `ArrayList.prototype` per
  runtime; any loaded bundle that provides the hook is enough.
- **Fallback (only old bundles loaded):** the proxy prototype's parent is
  `Object.prototype` and names come from `listMethodNames` — today's behaviour, but
  still with one shared prototype instead of per-object installation.

Hermes at the pinned commit implements `createObjectWithPrototype` and
`setPrototypeOf` (`hermes.cpp:2628`), which is all the core needs.

### 3.4 List

- **Kernel-created list** → `jsi::Object::create(rt, NativeListProto)` +
  `setNativeState`. Nothing else per crossing.
- **Plugin-created list, first crossing** → materialize (as today), then
  `setNativeState` + `setPrototypeOf(obj, NativeListProto)`. Identity is preserved.
  The stale `array_1` is deleted (it would otherwise keep element proxies — and their
  kernel objects — alive).
- **Read-only lists:** mutators call the stdlib `checkIsMutable` (its mangled name is
  discovered once per runtime) before mutating; a plugin-created read-only list keeps
  its `isReadOnly` field on the instance, a kernel list has none and is mutable unless
  the native list rejects the mutation — today's semantics.
- **`EmptyList`:** unchanged (not morphed).
- **`size`:** one accessor on the prototype, defined once through `Object.defineProperty`
  called via JSI (no `evaluateJavaScript`).
- **State-backed lists** (`mutableStateListOf`): a sibling prototype
  `NativeStateListProto` whose functions register the composition read first and then
  run the shared implementation. Plain lists pay nothing for this.
- `contains`, `indexOf`, `lastIndexOf`, `equals`, `hashCode`, `toString` stay native
  (one crossing) as today.

### 3.5 Map, views, entries

- `NativeMapProto` over `LinkedHashMap.prototype` (a kernel `HashMap` crosses with the
  same prototype; `is HashMap` holds since `LinkedHashMap` extends it in Kotlin/JS).
- `keys`, `values`, `entries` return view objects with their own prototypes over the
  stdlib `Set`/`Collection` prototypes: `size`, `contains`, `iterator`, `remove`
  delegate to the native map; `add` throws (as in Kotlin). Entries are objects with a
  `Map.Entry` identity whose `key`/`value`/`setValue` go to the native entry.
- Views keep the legacy plain surface (`size()`, `get(index)`) for old bundles.
- This fixes P3: typed `for ((k, v) in map)`, `map.forEach`, `keys.size` work for kernel
  maps and for plugin maps after crossing.

### 3.6 Iteration — snapshot (experimental)

Today `for`/`map`/`forEach` over a kernel list costs two crossings per element
(`hasNext()` and `next()` each go to native; for a state-backed list in composition
each of them is also a rendezvous to the UI thread).

**Decided (D1): snapshot iterator, as an experiment.** `iterator()` performs **one**
crossing that returns all elements already converted into a JS array;
`hasNext`/`next` are pure JS (an iterator prototype defined once per runtime);
`remove()` goes to native and adjusts the cursor. Cost: 1 crossing + element
conversion instead of 2N crossings.

Semantics: a modification of the list made *during* the iteration — by the loop body
(directly or through a kernel call) or by another native thread — is not seen by that
iteration; the iteration sees one consistent state of the list. Kotlin's own
`ArrayList` would throw `ConcurrentModificationException` in that situation, and
Compose's `SnapshotStateList` already iterates a snapshot. This is documented as
experimental in `collections.md`; the live iterator (shared prototype, `NativeState` =
list handle + index) stays implementable behind the same `iterator` entry if practice
shows the snapshot is wrong.

The same applies to map views (one crossing returns `[k0, v0, k1, v1, …]`).
`get(i)` in an index loop stays one crossing per call either way.

### 3.7 `@RDMA` objects

- One prototype per `@RDMA` class, built once at `installUserBridge` with all
  methods, getters and setters as host functions; `createXWrapper` becomes
  `Object::create(rt, proto)` + `setNativeState`. This is the design ORIGINAL_DESIGN
  describes.
- The vtable check for `open` methods is unchanged (it reads the `NativeState`).
- `wrapAny`/`wrapUserObject` dispatch by a **type id**, on both platforms: the kernel
  generates `typeIdOf(obj): Int` (a `when (obj) { is A -> 1 … }`), one call returns the
  index into a prototype table. This replaces `GetObjectClass` + `Class.getName` +
  string comparisons per module on Android; iOS already uses this shape.

### 3.8 Compose protocol proxies via `NativeState`

**The Compose model does not change.** There is one source of truth, in native: the
slot table, the groups and the UI tree live in the native Compose runtime (Jetpack
Compose on Android, Compose Multiplatform on iOS). Plugin composables execute in JS,
but every composer operation they perform (`startRestartGroup`, `endRestartGroup`,
`rememberedValue`, `changed`, …) is forwarded to the native `Composer`; kernel widgets
compose natively; values the plugin `remember`s live in the native slot table as
`JsValueHolder`s. On iOS the composer never crosses the boundary. This section changes
only **how the JS composer object finds its methods**, not what they do.

`ComposerProxy`, `StateProxy` and `ScopeUpdateScopeProxy` become plain JS objects with
a `NativeState` (not `HostObject`s) over shared prototypes (one each). The behaviour of
every method (rendezvous, `rememberedValue`, `changed`, state reads in composition, …)
is unchanged; only the dispatch changes: no `HostObject::get`, no string comparison, no
function allocation per call — a call goes straight to the right host function.

To put the methods on a prototype once, the runtime must know their exact names. They
are compile-mangled by the plugin toolchain (the plugin's compose-generated code calls
`$composer.startRestartGroup_xxx_k$(…)`), and the suffix is computed by the Kotlin
compiler, not by us. `Composer` is a sealed interface, so the guest bridge cannot
implement it. Instead the guest bridge gets a second hook, `RDMA.protocolMethodNames()`:
it takes an ES6 `Proxy` (a JS object that intercepts and records property accesses;
enabled by default in Hermes), casts it to `Composer` / `MutableState` /
`ScopeUpdateScope` and calls every protocol method on it once. The compiler emits those
calls with the exact mangled names; the `Proxy` records them; the runtime builds the
prototypes from that list once per runtime, before the first composition. The call
list is generated from `RdmaComposerProtocol.baseProtocol`, so it cannot drift.

Fallback (only old bundles loaded): the current `HostObject` proxies stay as the
legacy path.

### 3.9 Hot-path hygiene

- Boxed-type classes and methods (`Integer`, `Long`, `Double`, `Float`, `Boolean`,
  `String`, `Enum.name`, the `List`/`Map`/`Iterator`/`Map.Entry` interface methods) are
  cached as global refs / `jmethodID`s at JNI cache init, on the UI thread.
- Host functions that create local refs in loops release them (or use
  `PushLocalFrame`/`PopLocalFrame`).
- No `evaluateJavaScript` after runtime init.

### 3.10 Compatibility of stage 1

- Old bundles (`dynamic` code): plain names are present on every prototype; `RDMA.*`
  unchanged; hooks optional.
- Old bundles (typed code on plugin-created collections): mangled names present.
- New bundles on old hosts: the compiler output is unchanged; the new hooks are
  ignored by old hosts.

### 3.11 Documents updated

`collections.md` (kernel-created collections, iteration, views), `bridges.md`
(collections, lifecycle), `architecture.md` (NativeState vs HostObject, now true),
`ios_design.md` (no HostObject collections).

## 4. Stage 2 — lifetime and callbacks

### 4.1 Who owns a JS block

A "block" is a JS function registered through `RDMA.registerBlock` (or internally) and
referenced from the kernel by id.

| Block | Kernel holder | Released by |
|---|---|---|
| widget content lambda, widget callback, list of callbacks | slot of the widget call in the composition | `RememberObserver.onForgotten/onAbandoned` (deterministic) |
| `updateScope` block | `ComposerScopeBlock` held by the recompose scope | finalization of the holder (Compose has no callback for it) |
| `remember { jsObject }` value | `JsValueHolder` | `RememberObserver.onForgotten/onAbandoned` (deterministic) |
| effect body / dispose result | effect observer | already deterministic, unchanged |
| callback passed to an `@RDMA` function (service) | `RdmaFunctionN` | finalization of the holder |
| suspend continuation | completion | already deterministic, unchanged |

### 4.2 Registry with holder counts

- `registerBlock(fn)` returns the existing id if `fn` already has one (the id is stored
  on the function as a hidden property). Compose memoizes lambdas whose captures are
  stable, so a memoized lambda keeps its id across recompositions.
- Each entry counts its kernel **holders**. A holder retains the entry when it is
  constructed (synchronously, inside the JS task that registered the block — widget
  calls and `@RDMA` calls construct their holders before returning) and releases it
  when it goes away.
- An entry that has no holder at the end of the JS task that created it is freed
  (sweep at the task boundary) — it can no longer be retained by anyone.
- The registry map is guarded by a mutex (holders are created and released on other
  threads); the `jsi::Function` itself is created and destroyed only on the Hermes
  thread.

### 4.3 Release protocol

- A release never touches the JS runtime. It enqueues the id as a task in the
  **same FIFO low-priority queue** as callback invocations (`rdmaPostJs`), so an
  invocation posted before the holder became unreachable always runs before the
  release.
- The Hermes thread is the only one that removes entries and destroys
  `jsi::Function`s, so there is no concurrency with JS execution.
- Finalization has no timing guarantee; a late finalizer only delays memory reclaim,
  never affects correctness. Ids are monotonic and never reused.
- **Android** (`minSdk` 24: no `java.lang.ref.Cleaner`, no `reachabilityFence`): holders
  register a `PhantomReference` on a `ReferenceQueue` drained by one daemon thread.
  `invoke` passes the holder itself as an argument of the native call, which keeps it
  reachable until the invocation is enqueued.
- **iOS:** `kotlin.native.ref.createCleaner`.

### 4.4 Compose-owned blocks and widget skipping

The generated kernel widget entry wraps every lambda parameter in
`rdmaRememberBlock(id) { … }` = `remember(id) { RdmaBlockRef(id) }`, where
`RdmaBlockRef` is a `RememberObserver` that retains on `onRemembered` and releases on
`onForgotten`/`onAbandoned`; the Kotlin lambda passed to the widget is created inside
that `remember`. When the plugin lambda is memoized the id is stable, the widget
receives the same lambda instance and Compose can skip it (fixes P13). When it is not,
the old block is released deterministically and the widget recomposes — exactly the
behaviour of plain Compose with an unstable lambda.

### 4.5 Typed callback arguments

The kernel codegen knows the parameter types of every lambda parameter
(`RdmaType.FunctionType`). When a host function turns a JS callback into a holder, it
attaches the parameter and return descriptors to the registry entry; `runCallback` /
`runLambda` / `runCallbackOrLambda` marshal arguments by those descriptors with the same
dispatcher as everything else (fixes P11). Widget callbacks get their descriptors the
same way. The plugin-side call form does not change.

### 4.6 Other fixes

- **iOS strings (P10):** strings no longer go through `cstringArena`. The C++ side
  passes a sink; the Kotlin side writes the UTF-8 bytes into it within the call
  (`usePinned`). One copy, no retained memory.
- **Remembered numbers (P12):** `boxJsi` boxes every JS number as `Double`; Kotlin
  `Int`/`Double` are both JS numbers, so this is lossless, and keys compare
  consistently.

### 4.7 Known limitation (documented, not fixed)

A cycle across runtimes — a JS closure holding a kernel object that holds the closure
(e.g. a listener registered on the object itself) — is invisible to both GCs. As with
ordinary listeners, it needs an explicit unsubscribe. `features.md` gets a note.

## 5. Stage 3 — typed plugin surface (A3)

### 5.0 Prototype results and the revised approach — **proposal (D7)**

A prototype (a plugin compiled to JS against hand-written stubs) showed that kernel
symbols need **no IR lowering at all**. The stubs are ordinary Kotlin declarations in
the kernel packages whose members are `inline` and whose bodies are exactly call form
v1:

```kotlin
class PlatformInfo private constructor() {
    val os: String inline get() = asDynamic().getOs().unsafeCast<String>()
    inline fun describe(): String = asDynamic().describe().unsafeCast<String>()
}
inline fun echoIntList(list: List<Int>): List<Int> = RdmaAbi.echoIntList(list).unsafeCast<List<Int>>()
inline fun EnumConfig(method: HttpMethod): EnumConfig = RdmaAbi.createEnumConfig(method.name).unsafeCast<EnumConfig>()
class HttpMethod private constructor(n: Nothing) { companion object { inline val GET: HttpMethod get() = "GET".unsafeCast<HttpMethod>() } }
@Composable fun Text(text: String) { RdmaAbi.composeText(text) }
```

Compiled output of ordinary, unrewritten plugin code:
`RDMA.echoIntList(list)`, `info.getOs()`, `c.setMethod(...)`,
`RDMA.httpGet(url, RDMA.registerBlock(fn, …))`, and `xs.map { }` resolved statically to
the stdlib (`iterator_…_k$`, `collectionSizeOrDefault`) — served by the stage 1
prototypes. Composable stubs compile to the same code as today's generated widget
bridge. Consequences:

- **Call form unchanged (v1)** → new plugins keep running on old hosts; no bridge ABI
  bump is needed for stage 3 (D2 stays the rule for future incompatible changes).
- **Zero runtime cost**: every stub member is inlined; nothing is added per call.
- **Enums as classes whose runtime value is the entry name string** (implemented; a
  `value class` was tried first and rejected: it is boxed in generic positions —
  `List<HttpMethod>`, `MutableState<HttpMethod>`, lambda parameters — while the kernel
  hands plain strings). `when` compares strings; an entry unknown to an older plugin
  stays representable (no forward-compatibility regression, unlike a real `enum`). A
  `when` over an enum is exhaustive against the real enum in the JVM pass but not
  against the stub, so the JVM pass adds `else -> throw NoWhenBranchMatchedException()`
  to every enum `when` without `else` (FIR does not mark the last statement of a lambda
  as `usedAsExpression`; without the branch the JS pass silently compiled
  `list.map { when (it) { … } }` into a statement yielding nothing — caught by the e2e).
- `inline` suspend functions cover suspend functions **and** suspend members.
- A stub class has a private constructor; construction goes through a same-named
  top-level `inline fun`, so `Person("a", 1)` in plugin code resolves to it.
- `is`/`as` against a kernel class would test the stub class (`instanceof`) and fail for
  a native proxy → compile-time error in v1 (as already planned in §5.5).

What still needs a compiler step are the **Compose entry points** (`mutableStateOf` and
friends need the static type → descriptor; effects must be kernel-hosted) and
**subclassing an `open` kernel class**. Doing them as an IR lowering in the JS
compilation depends on the relative order of our IR extension and the Compose compiler
plugin, which is not controlled — too fragile. Proposal: keep the existing JVM resolve
pass, but reduce its source rewrite to exactly these two constructs (they become calls of
typed bridge functions), and stop rewriting kernel symbols entirely: imports stay, calls
stay, the stubs make them typed. The stubs are generated by the same pass from the
manifests (they replace `RdmaFunctionBridge`/`RdmaWidgetBridge`/`RdmaStaticBridge`/
`RdmaEnumBridge`). §5.2–§5.4 below describe the original IR-lowering plan and are
superseded by this section if D7 is accepted.

### 5.1 The call form and its version

The call form v1 is documented as it is today (inventory taken from the runtime and
the generators):

| Kernel symbol | JS call form v1 |
|---|---|
| constructor | `RDMA.create<Class>(args…)` |
| plugin subclass of an `open` class | `RDMA.createWithOverrides('<Class>', [args…], { <method>: fn, … })` |
| property get / set | `obj.get<Name>()` / `obj.set<Name>(v)` |
| method | `obj.<name>(args…)` |
| top-level function | `RDMA.<name>(args…)` |
| suspend function / method | `RDMA._<name>Async(id, args…)` / `RDMA._<Class>_<m>Async(id, self, args…)` with `RDMA.registerContinuation` |
| widget | `RDMA.compose<Name>(args…)` |
| companion `val` | `RDMA.<class><Name>()` |
| enum entry / `ordinal` / `valueOf` | name string / `RDMA.<enum>Ordinal(name)` / `RDMA.<enum>ValueOf(name)` |
| lambda argument | id from `RDMA.registerBlock(fn, pluginId)` |
| defaulted trailing argument | omitted / `undefined` |
| state factories | `RDMA.mutableStateOf(v, desc)`, `mutableStateListOf`, `mutableStateMapOf` |
| values | primitives by value, `Long` as the plugin's `Long` object, collections as proxies, `null` as `null` |

**Decision needed (D2) — versioning the call form.** Today no axis versions it: `G`
hashes Kotlin/Compose/Hermes, `H` is the kernel version. Proposal: a **bridge ABI
version** becomes a component of `G`. The default rule stays "change only additively";
an incompatible change bumps the bridge ABI version and therefore `G`, and the
existing per-generation machinery ([toolchain_compatibility.md §7](toolchain_compatibility.md))
serves every host bundles compiled by the toolchain that matches its runtime. This
requires a change in `toolchain_compatibility.md` and is applied only after approval.
Stage 1 changes the runtime behaviour of returned objects (they become real Kotlin
collections), so stages 1–3 together ship as bridge ABI v2.

### 5.2 Pipeline

Today: the plugin is compiled for the JVM against the kernel (FIR checkers record text
edits), the edited text plus generated bridges is compiled to JS.

After: **one compilation, to JS, of the original plugin source**:

1. The plugin Gradle plugin generates **kernel declarations** ("stubs") for the
   visible kernel modules from the manifest snapshot at `builtAgainstKernel`, in the
   original packages and with the original names and Kotlin types, as `external`
   declarations (no bodies).
2. The plugin compiles against them; imports stay as written. FIR checkers (compose
   allowlist, state type descriptors, unsupported constructs) run on this compilation.
3. An IR lowering in our compiler plugin, applied to the JS compilation, replaces every
   reference to a stub symbol with its call form v1 (table in [§5.4](#54-lowering-rules)).
   After lowering no reference to a stub remains, so nothing is looked up by global name.
4. The same pass collects `usedSymbols` and writes `plugin.json`.

The JVM resolve pass, the `*_rdma.kt` text rewrite and `RdmaFunctionBridge.kt` /
`RdmaWidgetBridge.kt` / `RdmaStaticBridge.kt` / `RdmaEnumBridge.kt` go away; the
`android` target of plugin modules is no longer needed. The runtime part of the guest
bridge (`RdmaRuntimeBridge.kt`) stays.

### 5.3 Manifest model

Class members in the manifest use a flat string model (`MethodInfo.isList`,
`listElementType`, …) while functions use `RdmaTypeRef`. Stubs need full types, so class
members move to `RdmaTypeRef` too, and both kernel generators consume the one model.
The manifest is an internal artifact produced and consumed by the same build, so its
format changes in lockstep. This also removes duplicated type handling in
`CppGenerator`/`CAbiCppGenerator`.

### 5.4 Lowering rules

| Source construct | Lowered to (call form v1) | Notes |
|---|---|---|
| `Person(a, b)` | `RDMA.createPerson(a, b)` | named arguments reordered by the IR; omitted trailing defaults dropped |
| `p.name` / `p.status = v` | `p.getName()` / `p.setStatus(v)` | |
| `p.greet(x)` | `p.greet(x)` | external member call already has this shape |
| `foo(x)` | `RDMA.foo(x)` | |
| lambda argument | `RDMA.registerBlock(fn, pluginId)` | typed parameters come from the stub signature |
| `suspend` function / method | generated typed suspend helper over `registerContinuation` | |
| widget call | `RDMA.composeText(…)` | content lambdas typed `@Composable () -> Unit` |
| `Alignment.Center` | `RDMA.alignmentCenter()` | |
| `mutableStateOf(x)` etc. | as today, descriptor from the IR type | |
| `class Cyborg : Person(…) { override … }` | constructor calls → `RDMA.createWithOverrides(…)` with the override **compiled Kotlin functions** | no more expression-body text extraction; state fields in such subclasses stay unsupported in v1 |

### 5.5 Constructs that need a prototype before committing

- **Enums — resolved by the stubs (§5.0).** The runtime representation stays the name
  string (v1); `HttpMethod.GET`, `.name`, `.ordinal`, `valueOf` and `when` type-check
  against the stub class. `values()`/`entries` and `is`/`as` remain compile errors.
- **`is` / `as` against a kernel class** (`x is Person`, `x as Person`,
  `filterIsInstance<Person>()`, `when (x) { is Person -> }`). The plugin-side type is the
  stub class, the runtime object is a native proxy, so the `instanceof` check would fail.
  **Decided: compile error in v1**, with a clear message. Known fix for later: the same
  technique as the stage 1 collections — the guest bridge hands the stub class prototypes
  to the runtime (like `RDMA.collectionPrototypes`) and each per-class proxy prototype
  inherits its stub class prototype, so `is`/`as` hold on hosts that have it. Old hosts
  would still answer `false`, which is why v1 rejects the construct instead of compiling
  it.
- **Lambdas in methods of `@RDMA` classes.** Rejected by the kernel validator today
  (class members cannot have function types). Not changed by this rework; listed as a
  follow-up.

### 5.6 Polyfills

The materializer emits polyfills against the same stubs instead of the `dynamic`
`external object RDMA` (`RdmaPolyfillMaterializer.dynamicSignature`), so polyfill code
is typed exactly like plugin code. Polyfills for classes remain out of scope.

### 5.7 Compatibility of stage 3

- The lowering emits only call form v1, so old hosts understand every call.
- Behaviour of kernel values on old hosts is the old one (P2/P3 present there); with D2
  each host gets bundles from the toolchain matching its runtime.
- Old bundles on new hosts: unaffected (call form v1 is still served).

## 6. Stage 4 — hotfix dispatch slots

### 6.1 Scope

Every callable of the `@RDMA` surface that can be hotfixed. Today that is **top-level
`@RDMA` functions only**: the polyfill materializer registers only
`TOP_LEVEL_FUNCTION` entries (`RdmaPolyfillMaterializer.registrationBlock`); a change
inside a class marks the class node dirty, but nothing registers a JS implementation
for its methods, so class members cannot be hotfixed at all yet.

**Decision needed (D3):** stage 4 covers top-level functions; hotfixing class members
(methods, custom accessors; constructors) needs class-member polyfills first and is a
separate follow-up. With stage 1 prototypes it has a natural shape — replace the
method on the class prototype for JS callers, fill the method's slot for native
callers — but a method body that touches private state is not emulatable, which needs
its own design.

### 6.2 Slots (implemented)

- Every slotted callable gets a private static field with its **slot cell**, created
  from the callable's FQN and its parameter/result type descriptors. The cell class
  (`RdmaSlotCell`) and two helpers are generated into each kernel module package by the
  kernel Gradle plugin, so a kernel module needs no runtime dependency.
- The kernel compiler plugin (`RdmaSlotTransformer`) injects the prologue
  `if (cell.blockId != 0L) { r = rdmaSlotInvoke(cell, arrayOf(…)); if (rdmaSlotHandled(r)) return r }`.
  Not hotfixed: a static load, a volatile load and a predictable branch.
- A cell registers itself with the runtime by FQN (C++ registry on Android, `RdmaSlots`
  on iOS); `RDMA.installSlot` fills it. Either may come first.
- Slotted today: top-level `@RDMA` functions that are not `@Composable`, not `suspend`
  and have no function-typed parameter or result (§11).

### 6.3 Registration

A polyfill bundle keeps registering `RDMA.<name> = fn` (old hosts understand only
this) and additionally calls `RDMA.installSlot("<fqn>", fn)` when the host provides
it (feature detection; old hosts skip it). The host registers `fn` as a block and fills
the cell; arguments and the result are marshalled by the cell's descriptors.
Exceptions propagate in both directions as for any call.

### 6.4 Threads — contract (D5 decided: B, 2026-11-18)

The invariant of §2.6 holds: **JS is executed only by the Hermes thread.** A native
caller of a hotfixed callable on another thread therefore hands the call to the Hermes
thread and waits for the result.

**Contract:** *a hotfixed `@RDMA` callable always executes on the Hermes thread; the
native caller waits until it returns (result or exception, as for any call). If the
wait exceeds the timeout, the caller gets an `RdmaException` instead of hanging.*

| Caller thread | Invocation |
|---|---|
| Hermes thread (JS → native → native caller) | direct, reentrant |
| UI thread | the existing rendezvous (`rdmaCallJs`) |
| any other thread (IO, pools, library threads) | a task on the Hermes **low-priority** queue; the caller waits with a timeout |

- **Low priority:** the high queue is serviced inside compositions; a background call
  must never run plugin code in the middle of one. The low queue runs only as loop
  (top-level) tasks.
- **Not through the UI thread:** that would block the UI thread on JS work and gives no
  guarantee.
- **Arguments and result** cross as thread-safe handles (JNI global refs /
  `StableRef`s); conversion to and from JS values happens inside the task, on the
  Hermes thread. A JS exception comes back as `RdmaExceptionInfo` and is rethrown on
  the waiting thread as `RdmaException` with its `cause` chain.
- **Timeout:** a warning is logged after 1 s of waiting; after 5 s the caller gets an
  `RdmaException` naming the callable and the waiting thread. The task is then
  *abandoned*: if it has not started it is skipped; if it is running, its result is
  discarded when it finishes. Either way the task releases everything it owns.

**"Our" deadlock.** A plain call of a non-`suspend` Kotlin function never changes the
thread; in Kardman a few calls do, invisibly: UI → Hermes (plugin content composed by a
kernel widget), Hermes → UI (a widget call from the plugin), any thread → Hermes (a
hotfixed callable). Code that relies on "same thread" — reentrant locks, `ThreadLocal`s
— breaks across such a switch. The deadlock: the caller holds a lock and waits for the
Hermes thread, while native code running on the Hermes thread needs the same lock
(called back from the hotfix's JS, or already waiting for it). In a plain JVM app the
same code runs on one thread and the lock is simply re-entered. B cannot prevent it
without breaking §2.6; it makes it loud (timeout). Mitigations:

1. **Contract for kernel code** (user guide, "Threads"): synchronous `@RDMA` callables are
   fast and never block; slow work is `suspend` or takes a callback; locks are held
   across a call of a hotfixable callable only when the author is sure.
2. **Lint** (§11): blocking primitives reachable from `@RDMA` callables.
3. **Timeout** with a diagnostic, as above.
4. **`RdmaLock`** (§11, later): a framework lock whose ownership follows the logical call
   across our thread switches, and whose waiter on the Hermes thread keeps serving the
   owner's queued requests.

Remaining costs, documented for users: **latency** (the call waits until the Hermes
thread is free) and **pool exhaustion** (many background threads waiting at once).
`suspend` callables will suspend instead of blocking once they are slotted (§11).

**Rejected: borrowing the runtime (D6).** A waiting background thread would execute the
hotfix's JS itself while the Hermes thread is parked in native code. It would untangle
the lock cycle above, but it breaks the invariant of §2.6 (rejected 2026-11-18); so is
any variant that runs JS on the calling thread.

**Vtable overrides follow the same contract.** A kernel call of an `open` method that a
plugin subclass overrides (`rdmaVtableDispatch`) today calls the JS override on
**whatever thread called the method** — a violation of §2.6 and a possible concurrent
use of the runtime when that is not the Hermes thread. It is routed exactly like a
slot. Its error semantics stay as today (a JS exception or a timeout is logged and the
native method body runs) — see the open question in §6.7.

### 6.5 Class hotfix — whole class moves to JS (stage 4b, sketch)

Method-level granularity is deferred. Instead, when any member of an `@RDMA` class
changes, the **whole class** (with its private closure) moves to JS:

- The polyfill contains the full class and registers it: `RDMA.create<Class>` and the
  class prototype for JS callers are replaced, so plugin code creates and uses a pure JS
  object (no crossing at all).
- Every member of the native class (constructors, methods, accessors) gets a slot.
  A native caller that constructs the class gets a native **shell** object holding a
  reference to its JS twin; every member of the shell forwards through its slot to the
  twin (threading as in §6.4). The state lives only in the JS twin.
- A JS instance passed to native is wrapped in a shell the same way; a shell passed back
  to JS unwraps to its twin, so identity is preserved in both directions.
- Instances created before the class hotfix is registered keep the native
  implementation (their state is native and cannot be migrated). Hotfix bundles are
  loaded before plugin content at startup, so this only concerns a hotfix delivered
  while the app is running.

Needs its own detailed design (shell lifetime via the stage 2 holder model, equality and
hashing, `open` classes and vtables) before implementation.

### 6.6 Documents

[compatibility.md §6.4](compatibility.md) ("native callers keep calling the baked-in
implementation") was rewritten after D5 was implemented (approved 2026-11-18).

### 6.7 Implementation plan (fixed 2026-11-18; implemented 2026-11-18)

Scope: D5 = B, the invariants of §2.7 and §2.8 where they are already decided. Full
hotfix coverage (suspend, lambdas, widgets, properties, classes, polyfills compiled
against the stubs) is designed and done afterwards (§11).

1. **Runtime helper** (`common/RdmaRendezvous.cpp`, both platforms):
   `rdmaCallJsBlocking(body, discard, what)` — runs `body` on the Hermes thread and
   waits, routed by the calling thread (Hermes: inline; UI: rendezvous; other: low
   queue + timeout). Shared state lives on the heap under one mutex, so a timed-out
   waiter can return while the task is pending or running; `discard` releases what the
   task owns when nobody waits for it any more. Warning after 1 s, timeout after 5 s.
2. **Slots** on Android (`rdmaSlotInvokeJni`) and iOS (`rdma_nativeInvokeSlot`) use the
   helper instead of falling through on background threads. The timeout error names
   the callable and the waiting thread.
3. **Vtable** (overrides of `open` methods by plugin subclasses) on both platforms:
   routed through the helper; **a JS exception propagates to the native caller as
   `RdmaException`** (§2.8) instead of being logged with the base method running
   instead; **results of every boundary type** are converted by the method's result
   descriptor (today only `String`). The prologue passes the arguments too, but the
   overrides do not receive them yet: plugin subclass overrides are generated from
   expression-body text without parameters — redesigning plugin subclassing is a
   separate item (§11).
4. **Timer callbacks** (`setTimeout`): a JS error goes to the global uncaught-exception
   handler, not only to the log (§2.8).
5. **Release gate** (§6.8): a dynamic release is rejected when it modifies a symbol whose
   call sites cannot all run the new version yet.
6. **e2e:** a hotfixed callable called from `Dispatchers.Default` (`IO_HOTFIX`), the
   timeout (`IO_TIMEOUT`), an override called from native code on the UI thread with a
   non-`String` result and a throwing override; the release gate refusing a `suspend`
   change.
7. **Docs:** user guide "Threads", `codegen.md`, this section's status, the backlog; the
   proposed text for `compatibility.md` §6.4 for approval.

### 6.8 Release gate for partial hotfixes

A dynamic release ships changed symbols as F polyfills. Per §2.7 a symbol may be
hotfixed only if every call site can run the new version. The kernel indexer marks each
declaration `hotfixable`: today that is a top-level `@RDMA` function that is not
`suspend`, not `@Composable` and has no function-typed parameter or result. A dynamic
release whose polyfill entries include a symbol that existed before (`modify` — directly
changed or through its private closure) and is not hotfixable is rejected with a message
naming the symbol and the reason; such a change needs a static release. Added symbols
(`add`) are not affected: they have no native call sites on older hosts. The gate relaxes
as coverage grows (§11).

## 7. Stage 5 — bring-up, verification, tests

1. Unit tests of the compiler plugins and release tooling (`scripts/test.sh`).
2. Stand builds for Android and iOS.
3. All existing e2e scripts on the Android emulator and the iOS simulator.
4. Point measurements per mechanism, before/after: proxy creation, a call on a proxy,
   `map` over 100 kernel elements, a composer call, a state read.
5. New tests for the gaps found during the study:
   - kernel-returned `List`/`Map` with stdlib operations, `is`, `as`, `for`, map views;
   - collections in returns of methods/properties of `@RDMA` classes and in callback
     arguments;
   - callbacks end-to-end (service and widget);
   - `open` class override called from the kernel;
   - GC: registry size and live handle count after N recompositions and after a forced
     GC; no growth;
   - an old bundle (built by the current compiler, kept as a fixture) on the new runtime;
   - polyfills returning collections and `@RDMA` objects; a hotfixed function called
     from native on each thread kind;
   - exception propagation on iOS (only Android has e2e today);
   - the generation test over `array_1` (§3.1).

## 8. Implementation order and bring-up

| Step | Work | Main files |
|---|---|---|
| 1.1 | `RdmaHandleState`, prototype registry, `RdmaHostOps` interface | new `common/RdmaProxyCore.*`, `common/RdmaHostOps.h` |
| 1.2 | List: prototypes, morph via `setPrototypeOf`, iterator (per D1) | `RdmaProxyCore`, `RdmaHostOpsJni.cpp`, `ios/RdmaHostOpsCAbi.cpp`; `ListHandle.cpp` and list part of `RdmaComposeCAbi.cpp` removed |
| 1.3 | Map, views, entries | same; `MapHandle.cpp` and map part of `RdmaComposeCAbi.cpp` removed |
| 1.4 | Guest-bridge hooks `collectionPrototypes`, `protocolMethodNames`; fallbacks | `RdmaPluginGradlePlugin.kt` |
| 1.5 | `@RDMA` object prototypes, `typeIdOf`, removal of `+16` | `CppGenerator.kt`, `CAbiCppGenerator.kt`, `CAbiKotlinGenerator.kt`, `RdmaDefaultWrapperGenerator.kt`, `RdmaAppGradlePlugin.kt` (aggregate) |
| 1.6 | Compose protocol proxies on `NativeState` | `RdmaComposerProxyGenerator.kt`, `CAbiComposerProxyGenerator.kt`, generated `RdmaComposerProxy.cpp` ×2, `RdmaCompose.cpp`, `RdmaComposeCAbi.cpp` |
| 1.7 | JNI caches, local refs, no eval | `RdmaCompose.cpp`, `RdmaHostOpsJni.cpp` |
| 1.8 | Docs of stage 1 | see §3.11 |
| 2.1 | Block registry with holders, sweep, release queue | `RdmaCompose.cpp`, `RdmaComposeCAbi.cpp` |
| 2.2 | Holders: `RdmaBlockRef`, `RdmaFunctionN`, `ComposerScopeBlock`, `JsValueHolder`; phantom queue / cleaner | `rdma-runtime` and `rdma-runtime-ios` Kotlin |
| 2.3 | Widget entries with `rdmaRememberBlock` | `RdmaWidgetGenerator.kt`, `CAbiWidgetGenerator.kt`, `CAbiWidgetIrGenerator.kt` |
| 2.4 | Descriptors for callback arguments | `CppGenerator.kt`, `CAbiCppGenerator.kt`, widget generators, runtime callback paths |
| 2.5 | iOS string sink; `boxJsi` as `Double` | `CAbiShim.kt`, `RdmaComposeCAbi.cpp`, `RdmaCompose.cpp` |
| 3.1 | Manifest model unification | deferred: the stub generator reads the flat class-member model as is |
| 3.2 | Stub generator (D7) | **done:** `RdmaKernelStubGenerator.kt` in `rdma-plugin-compiler-plugin`, written by the JVM pass |
| 3.3 | JVM pass reduced to Compose entry points + `open`-class subclassing; `is`/`as` error; enum `when` `else`; contract | **done:** `RdmaPluginCheckers.kt`, `RdmaPluginTransformState.kt` |
| 3.4 | Polyfills on stubs | deferred: kernel polyfills are compiled separately and unchanged |
| 3.5 | Docs (`codegen.md`, `architecture.md`, `modules_architecture.md`, `features.md`, `types.md`, `contribution.md`) | **done**; `toolchain_compatibility.md` per D2 is not needed — the call form stays v1 |
| 4.1 | Slot cells + IR prologue | **done:** `RdmaSlotTransformer.kt`; cells generated per module package by `RdmaKernelGradlePlugin.kt`; registry in `RdmaCompose.cpp` (Android) and `RdmaSlots.kt` (iOS). Slotted: top-level non-composable, non-suspend functions without function-typed parameters/results |
| 4.2 | `installSlot`, native→JS invokers, any-thread blocking call | **done for the Hermes and UI threads**; other threads: plan §6.7 (D5 = B) |
| 4.3 | Polyfill registration with feature detection | **done:** `RDMA.installSlot` guarded by presence (`RdmaPolyfillMaterializer.kt`) |
| 4.4 | `compatibility.md §6.4` | pending approval |
| 5 | Bring-up and tests | §7 |

**Decision needed (D4) — bring-up policy.** The plan is to implement stages 1–4 and
then build and test. Recommendation: at the end of each stage run a **compile-only**
check (unit tests + stand compilation, no devices) — it is cheap and keeps compile
errors from accumulating across stages; devices, e2e and measurements stay in stage 5.

## 9. Decisions needed

| # | Question | Recommendation |
|---|---|---|
| D1 | Snapshot or live iteration over kernel collections (§3.6) | **decided:** snapshot, experimental |
| D2 | Bridge ABI version as a component of `G` (§5.1) | **decided:** additive by default; an incompatible call-form change is a new generation (`generation-release`), old forms kept on new hosts during a deprecation window |
| D3 | Scope of hotfix slots (§6.1) | **decided:** top-level functions in stage 4; a change in a class moves the whole class to JS (stage 4b, §6.5); method-level granularity deferred |
| D4 | Compile-only check after each stage (§8) | **decided:** yes |
| D5 | Threading contract of hotfixed callables (§6.4) | **decided (2026-11-18): B** — background callers switch to the Hermes low queue and wait with a 5 s timeout (plan §6.7) |
| D6 | Runtime borrowing by a background caller while the Hermes thread is parked in native (§6.4) | **rejected (2026-11-18):** breaks the invariant "JS only on the Hermes thread" (§2.6) |
| D7 | Stage 3 via inline typed stubs (no IR lowering for kernel symbols; JVM pass rewrites only Compose entry points and `open`-class subclassing) (§5.0) | yes |

## 10. Risks

| Risk | Mitigation |
|---|---|
| A future stdlib method reads `array_1` / map storage directly | startup guard + generation test (§3.1) |
| Enum lowering conflicts with backend `when` lowering | prototype first; fallback to current string-literal treatment (§5.5) |
| Compose protocol names cannot be recorded through `Proxy` in some construct | legacy `HostObject` path stays as fallback (§3.8) |
| Deadlock in a hotfixed function called with a lock held ("our" deadlock, §6.4) | contract + lint + 5 s timeout with diagnostic; `RdmaLock` later (§11) |
| Snapshot iteration turns out to be the wrong semantics in practice | live iterator remains implementable behind the same entry (§3.6) |
| Old bundles relying on undocumented properties of current proxies | every plain name kept; old-bundle fixture test in stage 5 |
| Compiler plugin on the JS backend increases per-generation migration work | the plugin already migrates per Kotlin version; the IR API surface used is small (calls, property accesses, constructors) |

## 11. Backlog

Work agreed on and deliberately postponed (2026-11-18). Nothing here is broken today;
each item names what it would add.

**Next, agreed to take:**

| Item | What it adds |
|---|---|
| ~~Lambda parameters on `@RDMA` class members~~ | **done (2026-11-18):** `ParameterInfo.functionType` carries the full type; methods and constructors take callbacks on both backends (JNI `createRdmaFunction`, C ABI block id → Kotlin lambda), stubs register blocks. Lambda results and lambda-typed properties added the same day (sync calls for plugin lambdas with a result; kernel lambdas wrapped as JS functions). Also fixed: default wrappers rendered function types as raw `kotlin.FunctionN` |
| ~~Supertypes of `@RDMA` classes in the stubs~~ | **done (2026-11-18):** the API description records `superClass`/`isOpen`; a superclass stub is `open` with a `protected` constructor, a subclass stub extends it and does not repeat inherited members |
| ~~Per-platform analysis + file renames~~ | **done (2026-11-18):** `rdma_manifest.json` → `rdma_api.json` (API description, read by the plugin compiler); `rdma_analysis.json` → `kernel_manifest_<platform>.json` (`android`/`ios`, input of kernel releases). Revisit later: "manifest" also names the changelog fold in `compatibility.md` |
| ~~Vtable dispatch on the caller's thread~~ | **done (2026-11-18), §6.7 step 3:** routed like slots, typed results, exceptions propagate. Also fixed: a plugin subclass constructed in a file checked before its declaration was not rewritten (constructor rewrites now happen at flush) |
| User guide `threads_and_performance.md` (+ `how_to_write_kernel.md`) | Mental model: plugin is single-threaded; sync kernel calls run on its thread and must not block; slow work as `suspend` (IO inside the kernel) or callbacks. **done (2026-11-18):** `docs/guide/threads_and_performance.md`, `docs/guide/how_to_write_kernel.md` |
| Lint: blocking primitives reachable from `@RDMA` callables (§6.4, layer 2) | Flags `runBlocking`, `Future.get`, `join`, … in code that runs on the plugin's thread |
| ~~iOS two-pass generation~~ | **done (2026-11-18), option (a′):** the app's iOS compilation compiles the kernels' `capi/kotlin` glue in the same build (like `widget-kotlin` on Android); the kernel compiler plugin runs there in `capi-glue` mode for the widget entry bodies. Verified: a clean iOS build passes `ios-list-behavior` first time; adding then removing an `@RDMA` symbol rebuilds cleanly. Full IR generation of the glue remains possible later if the glue ever needs `internal` access |
| ~~Shadow-guard false positives (iterator private-field accessors)~~ | **done (2026-11-18)** |
| ~~e2e scripts that bake the crash-test plugin~~ | **done (2026-11-18):** the crash-test plugins carry `rdmaBakeInApp=false`; `exception-propagation.sh` bakes them with `-PrdmaForceBakePlugins` |
| ~~Block-lifetime probe on iOS~~ | **done (2026-11-18):** the iOS stand host probes after a GC; `ios-suspend-behavior.sh` asserts it (4 live blocks after 200 dropped lambdas) |
| ~~Background-thread calls of hotfixed callables (D5 = B)~~ | **done (2026-11-18), §6.7;** `compatibility.md` §6.4 updated (approved) |
| ~~Components (several compositions in one runtime)~~ | **done (2026-11-19):** `registerComponent("name") { a: A, b: B -> … }` (0–3 parameters) replaces `runRdmaApp`; the host renders `RdmaComposeHost.Component("<pluginId>/<name>", args…)` anywhere and any number of times, each place with its own state; a new plugin version replaces every place at the next update point ([compose_protocol.md](../internals/compose_protocol.md#components)). The single-content path (`registerContent` / `Content()`) was removed. Also fixed: iOS `SideEffect` lost its block before running it |
| ~~Cancellation of kernel `suspend` calls~~ | **done (2026-11-19):** stubs wait in `suspendCancellableCoroutine`; cancelling calls `RDMA.cancelContinuation`, which cancels the kernel `Job` ([suspend.md](../internals/suspend.md#cancellation)). Prerequisite for `Flow`. Also fixed: Android returned `Boolean` results as numbers |
| ~~Modifiers~~ | **done (2026-11-19):** an ordinary `@RDMA` class in the kernel, no framework type and no transfer optimisation; the full implementation is in `demo/kernel/internal` ([how_to_write_kernel.md](../guide/how_to_write_kernel.md#modifiers)). Framework change: `RdmaFunctionN` wrappers of one block are equal, so a chain holding a memoized plugin lambda is equal and the widget skips. Widgets keep defaulted parameters trailing (lambdas default to `{}`) |
| ~~Compatibility model alignment~~ | **done (2026-11-19)** (agreed design; `compatibility.md` edits await approval): emulatability = "calls only code that exists in the plugin runtime, or has a manual polyfill" (the indexer records `nativeRefs`; widgets stay non-emulatable until widget polyfills exist); a plugin's `minHost` is declared (`rdmaMinHost`, default `kernel.oldestActiveKernel`) and the build fails instead of raising it; `@RuntimeLocal` + state-split detection; `release-dynamic` reports every problem at once and ships nothing on any (`--dry-run`); update points: `RdmaBridge.stageUpdate` / `applyUpdates`, components follow the loaded version. Also fixed: uses of properties (through accessors and fields) and `object`s were missing from the usage graph |
| ~~Bugs from the field report (2026-11-19)~~ | **done:** JVM boolean getter/setter names (`isX`/`getX`); C/C++ keywords as C-ABI parameter names; iOS `suspend` bridges converting C arguments after the call returned (use-after-free; also lost `@RDMA` arguments); default-parameter list parser (lambdas, strings, comments, `a < b`); `@RDMA` enum widget parameters arriving as `null`; nullable `String` in both directions (a `null` constructor argument aborted the app on Android, iOS turned `null` into `""`); Android strings cross JNI as UTF-16 (emoji and U+0000 intact), iOS refuses U+0000 loudly. Not reproduced: a changed component argument not reaching the content — covered by tests for a primitive (`CARD: c 5`) and a new kernel object (`BOX: two`); `changed = 0` means "uncertain", so the plugin composable compares the arguments itself |
| ~~Third field report (2026-11-20)~~ | **done:** a kernel module with only widgets (or nothing `@RDMA` in one platform's compilation) got no bridge files, so the app's aggregate bridge included a missing `RdmaBridge_<module>.h` and every iOS build failed — the bridges and the manifest are now always generated; plugin code using `StringBuilder.append(Char)` (and any member whose name dead-code elimination changes) called a name the shared runtime does not have — plugins and polyfills are now linked without DCE, like the shared runtime. The other two items (a changed `String` component argument, defaults in the signature baseline) were fixed earlier the same day |
| ~~Fourth field report (2026-11-20)~~ | **done:** a plugin source file outside the plugin's root package could not resolve the rewritten state/effect calls (`rdmaMutableStateOf`, `rdmaLaunchedEffect`) — they are now qualified with the root package; a value captured by a widget's content lambda stayed stale when the parent recomposed (the widget call is skipped with the same memoized lambda, and the lambda could not invalidate its content because the Composer proxy returned no recompose scope) — `recomposeScope` now hands a plugin `ComposableLambdaImpl` the real scope (`RdmaLambdaScopes`: recorded used, handed out once until invalidated); `@RDMA` in a plugin is a compile error (it did nothing; plugin classes are plain Kotlin and cannot be state values). The other items were the third report's |
| Runtime performance counters | Idea from the fourth field report: bridge call counters and composition timings available out of the box, instead of each team measuring with markers. Not designed |
| ~~Second field report (2026-11-20)~~ | **done:** `Long` widget arguments arrived as `0` (the widget entry took only JS numbers; a plugin `Long` is an object); `String?` widget parameters broke the iOS widget entry (no nullable `String` in the C-ABI path) |
| ~~Open from the same report~~ | **done (2026-11-19):** the shared runtime is required (the app build fails without it, and a plugin library missing from it is an error instead of one plugin's trimmed copy winning); kernel modules are exported to the iOS framework's ObjC header (`rdmaExportKernelToObjC=false` opts out). Also: bundles are served gzip-compressed (level 9, about a third of the size) |
| Export only marked kernel types to Swift | Prototype (2026-11-19): a kernel type referenced from the framework's public API lands in the header with its members even without module export (named with the module prefix, `InternalTextBox`). An annotation could generate such references instead of exporting whole modules |
| C-ABI glue in the iOS header | The app compiles the kernels' C-ABI glue, so its `rdma_*` functions and widget entries are public in the framework header; they could be `internal` |

**Release pipeline — production readiness (review 2026-11-19, done 2026-11-20).** A release
is tests → prepare → build → contract tests → commit (`scripts/release.sh`, `commit-release`):
changelog entries carry a release `type` (static releases are the host versions); `F` is built
for every supported host, once per distinct native state of the module, and for every live
generation (`blocked` when its toolchain is unavailable); `F`/`R` bundles are content-addressed
with `polyfills.json` as the index; the store offers only versions whose polyfills it has;
bundles are signed (Ed25519, `RDMA_SIGNING_KEY`) and apps check hash and signature
(`RdmaBridge.verifyBundle`, Monocypher); the kernel's `commonTest` runs as contract tests on an
emulator against the native code and each `F` (`scripts/contract-test.sh`); `RdmaHost` carries
the app's `H`, `G` and keys; releases take both platforms' analyses; re-sync telemetry is
persisted for `R` retention; the CI workflow serializes releases, persists the release state and
uploads the bundles. Also fixed the same day: widget defaults were recorded as required in the
signature baseline (so appending a defaulted widget parameter was refused); `SideEffect` ran only
once when its block was a remembered lambda (Android).

| Still open | Why |
|---|---|
| Store client as a framework module | Parked (2026-11-20): download, cache, persisting staged updates for the cold start, retry/backoff and cleanup still live in the stand/demo apps |
| Do not rebuild unchanged `F` | Unchanged polyfills get the same content hash (no new bundle), but are still compiled each release |
| Store server for production | The in-memory store with an unauthenticated `/reload` is a development stand-in |

**Later:**

| Item | Why later |
|---|---|
| ~~Identity keys for `changed(object)`~~ | **done (2026-11-19):** the composer proxy passes kernel objects and states as themselves and other JS objects as `JsIdentityKey(id)` (a per-runtime `WeakMap`), for `changed` and effect keys, on both platforms ([compose_protocol.md](../internals/compose_protocol.md#object-keys)). Containers, plugin composables with object parameters, `remember(obj)` and object effect keys now skip as in Compose. Plugin objects compare by identity, not `equals` |
| `StateFlow` / `Flow` from the kernel | Agreed to take after components and cancellation; `StateFlow` maps onto existing state, `Flow` on top of cancellation; needs a short design |
| Plugin subclassing redesign (paused, 2026-11-19) | Overrides compiled as real Kotlin functions with parameters (today: expression-body text without parameters), so native callers can pass arguments to an override (§6.7 step 3) |
| Full hotfix coverage (§2.7): polyfills compiled against the stubs, then `suspend`, lambdas, widgets, properties/statics | Every kind of symbol runs its new version everywhere; each item gets a short design first. Today a `suspend`/lambda hotfix does not even reach JS callers (wrong call form) |
| Class-member hotfix (stage 4b, §6.5) | Needs its own design: shells, identity, vtables |
| Slots for `suspend` callables | Needs the reverse async path (JS result → suspended native caller); first in line after the above |
| Slots for callables with function-typed parameters/results | Needs kernel → JS lambda marshalling |
| Slots for widgets | Needs a JS composable inside a kernel-driven composition |
| `is`/`as` against kernel types (§5.5) | Old hosts would still answer `false`; tied to versioning the call form (D2) |
| Effects through the composer protocol | Works today; would only shrink the JVM pass |
| `RdmaLock`: a framework lock for kernel code | Removes "our" deadlocks (§6.4) for code that uses it, without breaking §2.6: ownership follows the logical call across our thread switches (re-entry by the Hermes thread working for the owner is granted), and a Hermes thread waiting for it keeps serving the owner's queued requests (the rendezvous principle). `synchronized` and foreign locks stay the user's responsibility |
| Polyfills compiled against the stubs (step 3.4) | Polyfills work through the text rewrite; gains type safety |
| Spot measurements (`demo/plugin/bench`) | Benchmarks planned on a large project |
| `demo/versions` deprecation gate | The demo only shows the pipeline; its static release is not maintained |
| Diagnostics for a call to a released block | A kernel that keeps a plugin lambda past its composition (e.g. a native delegate created once) calls a block the plugin already replaced and released; today the call does nothing, silently (found in the demo's iOS `ButtonList`, fixed there with `rememberUpdatedState`). At least a debug-build warning; the exact behaviour needs a short design |
| Widget polyfills | The materializer cannot build a `@Composable` polyfill yet, so widgets stay non-emulatable anchors of the `floor` (`compatibility.md` §5.2 *current limit*) |
| New field on an existing native class; layout fingerprint in the manifest | Open design questions (`compatibility.md` §6.7 *open*), to settle with the user |
| Nullable primitive widget parameters (`Double?`, `Int?`, …) | A primitive widget argument crosses unboxed, so `null` cannot cross (an omitted argument still selects the default through the mask). Needs a boxed path for the nullable case |
