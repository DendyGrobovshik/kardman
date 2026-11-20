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
# Testing

## Unit tests (no device)

```bash
./scripts/test.sh
```

Runs the JVM tests of the compiler plugins (generator output, stubs, checkers), the release
tooling and `:tests:rdma-tests`. Every e2e script runs them first.

To look at generated output directly:

```bash
./gradlew :tests:stand:kernel:internal:compileAndroidMain
cat tests/stand/kernel/internal/build/generated/rdma/rdma_api.json
./gradlew :tests:stand:plugin:alice:counter:compileAndroidMain
cat tests/stand/plugin/alice/counter/build/generated/rdma/RdmaKernelStubs_*.kt
```

## The stand

`tests/stand/` is user-side code in the main build: kernel modules (`internal`,
`user:alice`, `user:itest`), plugins (`alice:counter`, `bob:services`, `itest:newplug`,
`itest:exceptions`, `itest:exceptions2`) and the two host apps. The plugins print markers
(`println`) that the e2e scripts assert on. Crash-test plugins carry `rdmaBakeInApp=false`
and are baked only where a script asks for them (`-PrdmaForceBakePlugins`).

## End-to-end tests

Scripts in `tests/rdma-integration-tests/e2e/`. Each static-releases the kernel, publishes
the framework, builds and launches the app on a booted Android emulator (`adb devices`) or
iOS simulator (booted automatically; `IOS_SIM_NAME=…` to choose), and asserts the markers.
Each is self-contained and idempotent.

| Script | Covers |
|---|---|
| `list-behavior.sh`, `ios-list-behavior.sh` | `List` bridge: mutation, nesting, both directions, stdlib, collections in returns and callbacks; lambdas as values, components, modifiers (applied, scope, skip, click), object keys (skipping with the same plugin object, an equal kernel object, a content lambda) |
| `map-behavior.sh`, `ios-map-behavior.sh` | `Map` bridge, views, entries |
| `state-behavior.sh`, `ios-state-behavior.sh` | typed state channels, snapshot collections |
| `suspend-behavior.sh`, `ios-suspend-behavior.sh` | `suspend`, timers, block lifetime after GC, hotfix from a background thread and its timeout, vtable from native |
| `default-params-behavior.sh`, `ios-default-params-behavior.sh` | trailing defaults |
| `exception-propagation.sh` | exceptions in both directions, global handler, bundle load errors (Android) |
| `plugin-lifecycle.sh` | store, releases, polyfills, the hotfix gate (Android, runs its own store) |
| `toolchain-generations.sh` | the generation axis |
| `contract-tests.sh` | the release contract gate: the kernel's `commonTest` on the emulator against the native code and each polyfill; a hotfix that behaves differently in JS is rejected (Android) |
| `plugin-compile-errors.sh` | compile-time rejections of the plugin JVM pass |
| `generate-preview.sh` | the demo: dev server, Generate → Preview without a restart |

```bash
./scripts/e2e.sh    # unit tests + a quick Android and iOS list run
```

Run the scripts above one by one for full coverage; run them all before merging a runtime
or generator change. Remember `./gradlew --stop` after re-publishing compiler plugins (the
Kotlin daemon caches them).

## Runtime logs

```bash
adb logcat -s RdmaBridge RdmaRuntime RdmaJni RdmaCompose RDMA
```

On iOS: the Xcode console or `xcrun simctl spawn booted log stream`.

## Adding a case

Add an `@RDMA` declaration to a stand kernel module, use it from a stand plugin with a
`println` marker, and assert the marker in the matching e2e script. No other wiring is
needed.
