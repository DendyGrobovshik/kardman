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
# Subclassing and the vtable

A plugin may subclass an `open` `@RDMA` class and override its `open` methods. The override
must run both for plugin callers and for kernel code calling the method on that object.

## Plugin side

The plugin JVM pass removes the subclass declaration and rewrites each constructor call:

```kotlin
class Cyborg(n: String) : Person(n, 0) { override fun greet() = "Beep" }
val c = Cyborg("x")
```

```js
RDMA.createWithOverrides('Person', ["x", 0], { greet: function() { return "Beep"; } })
```

The rewrite happens at the flush point, after all files are checked, so it does not depend
on file order. Today the overrides are taken from expression-body text and take no
parameters; a redesign compiling them as real Kotlin functions is in the backlog.

## Native side

`createWithOverrides` (dispatched across modules by the app's aggregate bridge) creates the
native object, stores the override functions in an `RdmaVtable`, and attaches the vtable to
the object.

`RdmaVtableTransformer` injects into every `open` method of an `@RDMA` class a check:

```kotlin
val vt = <vtable of this>
if (vt != 0L) {
    val r = rdmaVtableDispatch(vt, methodId, arrayOf(args…), paramDescs, resultDesc, fallthrough)
    if (r !== fallthrough) return r as T
}
// original body
```

- **Where the vtable lives**: Android — an IR-injected `__vtable: Long` field, set through
  `SetLongField`; iOS — the generated source cannot reference an IR-injected field, so the
  pointer lives in a runtime registry (`rdmaVtableSet`/`rdmaVtableGet`).
- **Dispatch** follows the slot rules ([hotfix_slots.md](hotfix_slots.md)): the override runs
  on the Hermes thread whatever thread called the method (UI through the rendezvous, others
  through the low queue with a 5 s timeout); the result is converted by the method's result
  descriptor (every boundary type); a JS exception reaches the native caller as
  `RdmaException`. A method the plugin did not override returns `fallthrough` and runs its
  native body.
- `rdmaVtableDispatch` is declared in the module's package by the kernel Gradle plugin;
  the app generates its JNI export per kernel module (Android), the runtime implements it on
  iOS.

## Plugin calls

Plugin code calling a method on the object calls the proxy's prototype function, which goes
to native, where the same check dispatches to the override. One path serves both callers.

## Limits

Overrides take no arguments yet; block-body overrides, state fields in subclasses and
`open suspend` methods are not supported.
