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
# Proxies and handles

How a native object looks in JS, and how long it lives.

## Shape of a proxy

Every JS proxy — an `@RDMA` object, a collection, a Compose protocol object — is a plain
JS object with a `jsi::NativeState` whose prototype is a **shared, per-runtime prototype**
that carries the host functions:

```
proxy object        — NativeState only: the handle (+ descriptor, flags)
  └─ class prototype — host functions (methods, get<Name>/set<Name>), built once per runtime
      └─ (collections) the Kotlin/JS stdlib prototype, e.g. ArrayList.prototype
```

A crossing costs `Object.create(prototype)` + `setNativeState`: no per-instance methods, no
`HostObject::get` per property access, no function allocation. A host function recovers
its object from `this` with `getNativeState()` and a tag check
(`RdmaStateKind`), then a `static_pointer_cast` — no `dynamic_cast`.

- **`@RDMA` objects**: one prototype per class, built at `installUserBridge`. Properties are
  exposed as `get<Name>()`/`set<Name>(v)` methods rather than JS accessors.
- **Collections**: the prototype sits between the object and the stdlib prototype, so the
  object *is* a Kotlin collection. See [collections.md](collections.md).
- **Compose protocol objects** (`Composer`, `MutableState`, `ScopeUpdateScope`): the same
  shape. Their method names are compile-mangled by the plugin toolchain, so the runtime
  learns them once per runtime from the guest bridge hook `RDMA.protocolMethodNames()`
  (an ES6 `Proxy` records the names the compiler emits); old bundles without the hook fall
  back to `HostObject` proxies.

The shared core lives in `common/RdmaProxyCore.cpp`: the prototype registry (owned by a
runtime-scoped object, so no `jsi::Object` outlives the runtime), prototype construction
and the platform-independent host functions. Platform operations (`listGet`, `mapPut`,
value boxing, handle release, …) are plain functions implemented once per platform and
selected at link time — no virtual dispatch.

## Wrapping a native object

When a kernel object crosses to JS, the bridge must pick its prototype. The same-module
case uses the concrete wrapper directly. Otherwise `wrapAny` asks the kernel for the
object's **type id** — a generated `typeIdOf(obj): Int` (`when (obj) { is A -> 1 … }`) — and
indexes a prototype table; across modules the aggregate bridge dispatches to the owning
module. Both platforms use this shape.

## Handles and lifetime

A handle keeps the native object alive exactly as long as the JS proxy holding it is
alive:

- the handle (a JNI global ref / a `StableRef`) lives in the proxy's `NativeState`;
- when Hermes collects the proxy, the `NativeState` destructor releases it
  (`DeleteGlobalRef` / `rdma_disposeStableRef`), and the native GC may collect the object;
- prototypes never hold handles.

The reverse direction — native code holding plugin JS functions — follows the same rule
through block holders: [blocks_and_lambdas.md](blocks_and_lambdas.md).

A cycle that runs through both runtimes (a JS closure holding a kernel object that holds
the closure) is invisible to both garbage collectors and needs an explicit unsubscribe.

## Identity

Each crossing of a kernel object creates a new proxy; there is no proxy cache, so `===`
between two proxies of one object is not guaranteed. `equals` on collections is
structural. A plugin collection keeps its identity on its first crossing: the same JS
object becomes the proxy.

`is`/`as` against a kernel class would test the plugin's stub class and fail for a proxy,
so the plugin compiler rejects them. A possible fix — each class prototype inheriting its
stub class prototype, as collections inherit the stdlib — is in the backlog
([runtime_rework.md §5.5](../design/runtime_rework.md#55-constructs-that-need-a-prototype-before-committing)).

## Performance rules

A crossing (~12 µs) is the budget; nothing on a per-call path may add a comparable cost.
On any per-call or per-crossing path there is:

- no allocation of `jsi::Function`s and no property installation on instances;
- no lookup by string (`FindClass`, `GetMethodID`, `HostObject::get` + `utf8()`, global
  property reads per object);
- no JS source evaluation after init;
- no virtual dispatch or `dynamic_pointer_cast` where a tag check suffices;
- no JNI local reference that outlives its use in a loop.

Everything per type is built once per runtime. JNI classes and method ids (including the
boxed types and collection interfaces) are cached at init, on the UI thread, where the app
class loader is available.
