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
# Loading plugins

How bundles get into the runtime: baked into the app at build time, or delivered by the
store at runtime. The target design of the store and the sync protocol (signed manifests,
content-addressed modules, the two-plane serving) is
[design/store_and_sync.md](../design/store_and_sync.md); this page describes what exists.

## Bundles

A bundle is Hermes bytecode (`.hbc`) compiled by `hermesc` from Kotlin/JS output. A plugin
needs, in load order:

1. the **shared** bundles of its toolchain generation (`:shared-runtime`: Kotlin stdlib,
   coroutines, Compose runtime), the same for every plugin of that generation;
2. **polyfills** for kernel symbols the host lacks or that were hotfixed (`F` bundles) or
   removed (`R` bundles) — see [hotfix_slots.md](hotfix_slots.md);
3. the **plugin** itself.

Evaluation is asynchronous: `RdmaBridge.nativeEvalAsset` / `nativeEvalFile` /
`nativeEvalBytes` queue an eval on the Hermes thread. A load error is stored and thrown
from `nativeIsReady()` as `BundleLoadException`.

## Baked plugins

The app Gradle plugin compiles every plugin module of the build (and the shared bundles) to
`.hbc`, puts them into the app (Android assets, iOS app bundle) and writes
`rdma-modules.json` — module names in topological order, shared dependencies first. The host
reads that file and evals the bundles in its order; hardcoding the list breaks after a
toolchain update (blank screen). A plugin module with `rdmaBakeInApp=false` is not baked
unless the build passes `-PrdmaForceBakePlugins` (the stand's crash-test plugins use this).

## Content

A plugin's `main()` runs at eval and registers its components (`registerComponent`). The
host renders them by name with `RdmaComposeHost.Component(...)`
([compose_protocol.md](compose_protocol.md#components)). A plugin
loaded later registers new content; the host observes `nativeContentVersion()` and
recomposes under `key(contentVersion)`.

## Store and re-sync

The store (`:rdma-store`, `StoreHttpServer` + `RdmaStoreEngine`) keeps plugin releases and
bundles in the versions directory and answers:

- `POST /resync` — request: plugin id, the version the client has, host version `H` and
  generation `G`; response: the plugin state and severity (P1–P4,
  [compatibility.md §7](../design/compatibility.md)), `status` (`UPDATE`, `CURRENT`,
  `UNAVAILABLE`, `REFUSED`), the target version, the plugin bundle hash and the polyfill
  bundle keys the host needs;
- `GET /bundle/<key>` — a bundle by content hash or polyfill key
  (`F:<module>:<H>`, `R:…`);
- `GET /health`.

The client side is app code, not a framework module yet: the demo kernel's `StoreClient`
(`installStore`, `resync`) posts to `/resync`, downloads missing bundles into a
hash-addressed cache directory and evals the plugin and the polyfills it needs. The demo
dev server (`demo/server`) adds `/generate`, which compiles submitted code into a plugin
release.

## Old and new hosts

The store serves each host what its runtime understands: polyfills are keyed by the host's
`H`, and per-generation bundles by its `G`
([toolchain_compatibility.md](../design/toolchain_compatibility.md)). A plugin built against
a newer kernel runs on an older host through `F` polyfills; an older plugin runs on a newer
host unchanged, because the call form only grows
([plugin_codegen.md](plugin_codegen.md#call-form-v1)).
