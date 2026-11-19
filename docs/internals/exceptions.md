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
# Exceptions

User-facing behaviour: [exceptions.md](../guide/exceptions.md). Invariant: exceptions
always cross between the runtimes, in both directions, with their `cause` chain; only
fire-and-forget work goes to the global handler
([runtime_rework.md §2.8](../design/runtime_rework.md#28-exception-invariant)).

## Model

An exception is never passed as a live object. At the boundary it is **reborn**: a new
exception is thrown in the other runtime, carrying the original as its `cause`. What crosses
is a description of strings, `RdmaExceptionInfo` (a plain C++ struct):

| Field | Meaning |
|---|---|
| `originalExceptionType` | FQN of the original class (`java.lang.IllegalArgumentException`, `TypeError`, …) |
| `message` | the message |
| `stack` | the stack of the runtime it was thrown in |
| `cause` | the nested description from the previous crossing (recursive) |

`suppressed` exceptions and extra fields of a JS error are not carried.

On the kernel side the type is
`RdmaException(originalExceptionType, message, remoteStack, cause: RdmaException?)`; in JS it
is an `Error` whose `name`/`message` mirror the original, `stack` is the native trace and
`cause` the nested error. An exception may cross several times; the chain grows each time
(`fromJSError` reads a nested `Error.cause` back).

## Channels

| Channel | Delivery |
|---|---|
| plugin → kernel call (function, method, property, widget, state) | the kernel exception is rethrown in JS |
| kernel → plugin synchronous call (content, scope block, effect body, dispose, slot, vtable) | the JS exception is rethrown as `RdmaException` to the native caller |
| background call through `rdmaCallJsBlocking` | the same; a timeout is an `RdmaException` too |
| `suspend` completion | `completeError` → the plugin's continuation resumes with the exception |
| callback, lambda, continuation body, timer | no caller: the global uncaught handler |
| bundle eval | stored, thrown once from `nativeIsReady()` as `BundleLoadException` |

## Global handler

`rdmaUncaughtException(info, pluginId)` delivers fire-and-forget errors to one handler set by
the app (`RdmaBridge.setUncaughtExceptionHandler`), with the plugin id recorded at
`registerBlock`/`registerComponent`. Without a handler the full chain is logged (`LOGW`); the
app does not crash. There is no per-plugin handler.

## Platforms

- **Android**: native exceptions are pending JNI exceptions (`ExceptionCheck`, read into
  strings, local refs released); JS exceptions arrive as `jsi::JSError`.
- **iOS**: an exception must not cross a C frame. Kotlin shim functions catch their own
  exceptions and record them thread-locally (`rdmaCaptureError`); C++ checks after each call
  (`checkKotlinError`) and throws a JS error. In the other direction `rdma_nativeInvoke*`
  return an error status that Kotlin turns into `RdmaException`.

The C++ layer is transport only and never appears in the stack. Live exception objects are
never held across the boundary: JSI values are bound to one runtime and are serialized at
the `catch` site.

## Tests

`exception-propagation.sh` (Android) bakes the crash-test plugins (`itest:exceptions*`) with
`-PrdmaForceBakePlugins` and checks native → JS, the global handler, the double crossing and
`BundleLoadException`. The composition throw is verified as a crash (§ Compose:
[compose_protocol.md](compose_protocol.md#exceptions-in-composition)).
