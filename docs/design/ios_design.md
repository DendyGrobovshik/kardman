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
# Design: iOS support

How it works: [internals/bridges.md](../internals/bridges.md),
[internals/kernel_codegen.md](../internals/kernel_codegen.md).

## Goal

Add Kotlin/Native on iOS as a second host next to the JVM on Android, with the plugin side —
the JS compilation, stubs, bundles, versioning — reused unchanged. Only the kernel host and
the leaf of the bridge may differ.

## Decisions

- **A function-pointer registry instead of exported C symbols.** A Kotlin/Native
  *framework* exports `@CName` functions only as Objective-C methods (verified with `nm`),
  `@SymbolName` is internal, and a `staticLib` variant crashed `CAdapterCodegen`. Kotlin
  registers C-compatible functions at startup (`staticCFunction`) and C++ calls them by
  address — the mechanism zipline and redwood use. The framework build stays the stock static
  framework.
- **The Composer never crosses.** It is not a C type, so it cannot be passed through a
  `staticCFunction`; the shim keeps the current composer in a global and every composer
  operation and widget entry reads it.
- **Widget entries as IR.** An entry must call the already-lowered widget with an explicit
  composer, which source code cannot express; an IR generator running after the Compose
  compiler fills the entry's body.
- **Glue compiled by the app, in one pass.** The kernel's iOS compilation generates the C ABI
  glue; the app's iOS compilation compiles it (in `capi-glue` mode for the widget entries),
  like the widget entries on Android. A two-pass scheme (glue compiled back into the kernel)
  needed a second build and could leave stale glue when a symbol was removed. Generating the
  whole glue as IR inside the kernel compilation remains possible if the glue ever needs
  `internal` access.
- **The vtable pointer in a runtime registry**, because generated source cannot reference an
  IR-injected field.
- **State writes posted to the main thread.** A Kotlin/Native `MutableState` written from the
  Hermes thread bypasses the snapshot observation and nothing recomposes.
- **Same packaging split as Android**: a reusable runtime (`rdma-runtime-ios`, with the C++
  core as `librdma_core.a` and `hermes.xcframework`) and a per-app `librdma_user.a`.

## Services in Swift

Host services may be implemented in Swift through the Objective-C protocol Kotlin/Native
exports for `KernelServiceProvider`. Swift Export (Beta) is a future drop-in for the same
interface.
