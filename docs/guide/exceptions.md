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

Exceptions cross between the plugin and the kernel in both directions, keeping the
original type name, message, stack and `cause` chain.

## Calls that return

When the plugin calls the kernel and the kernel throws, the plugin's call throws. When the
kernel calls the plugin — composing plugin content, calling an override of an `open`
method, calling a hotfixed function — and the plugin throws, the kernel's call throws
`RdmaException`.

An exception is not passed as a live object: a new one is thrown on the other side with
the original as its `cause`. A trace that crossed several times reads end to end:

```
RdmaException: boom                      (kernel)
    at kernel.…
Caused by: Error: boom                   (plugin)
    at plugin.…
    Caused by: IllegalArgumentException  (kernel)
        at kernel.…
```

`RdmaException` keeps the original class name in `originalExceptionType` and the other
runtime's stack in `remoteStack`. A typed `catch` across the boundary is not possible:
catch `RdmaException` (in the kernel) or `Throwable` (in the plugin) and look at the type
name.

`suspend` calls work the same: a kernel exception resumes the plugin's coroutine with it.

## Fire-and-forget: the global handler

Callbacks, lambdas, timers and continuations have no caller waiting for them, so their
exceptions go to **one global handler** that the app installs. It receives the id of the
plugin that failed and the `RdmaException`:

```kotlin
RdmaBridge.setUncaughtExceptionHandler { pluginId, e -> report(pluginId, e) }
```

If no handler is installed, the full chained stack is logged; the app does not crash.

A plugin cannot install its own handler.

## Exceptions in composition

Don't let an exception escape a plugin `@Composable` body. Compose cannot recover from a
composition that was interrupted half-way, and the app crashes with an `RdmaException`
whose `Caused by:` chain names the plugin error. Report errors through state instead.

## Loading a bundle

Loading a bundle is asynchronous. If a bundle fails to load, the error is thrown once from
`nativeIsReady()` as a `BundleLoadException`.

How it works inside: [exceptions.md](../internals/exceptions.md).
