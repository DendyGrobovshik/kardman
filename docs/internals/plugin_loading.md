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
loaded later registers new content, and every `Component` place switches to it at once.

## Update points

A re-sync must not change running code. The client stages downloaded bundles with
`RdmaBridge.stageUpdate(path)`; `RdmaBridge.applyUpdates()` evaluates all staged files, in
order, as **one** task on the Hermes thread (`rdmaEvalFiles`), so no composition or callback
runs between them. The host calls it at a point of its choosing; a cold start evaluates the
newest bundles before the first frame. Polyfills are staged before the plugins that need them.

## Store and re-sync

The store (`:rdma-store`, `StoreHttpServer` + `RdmaStoreEngine`) keeps plugin releases and
bundles in the versions directory and answers:

- `POST /resync` — request: plugin id, the version the client has, host version `H` and
  generation `G` (the app's `RdmaHost.VERSION` / `GENERATION`); response: the plugin state and
  severity (P1–P4, [compatibility.md §7](../design/compatibility.md)), `status` (`UPDATE`,
  `CURRENT`, `UNAVAILABLE`, `REFUSED`), the target version, the plugin bundle hash and the hashes
  of the polyfills the host needs. The target is the newest compatible version whose polyfills
  the store has for this `(H, G)` (`polyfills.json`): a version needs an `F` of a module when the
  module changed after `H` up to the kernel the plugin was built against, and that `F` must be
  at least that recent; at-risk plugins also get the module's `R` polyfills. Every re-sync of a
  known version is recorded in `versions/telemetry.json` (the usage `R` retention reads);
- `GET /bundle/<hash>` — a bundle by content hash. With `Accept-Encoding: gzip` it is sent as
  the gzip copy made at release time (`<hash>.gz`, level 9 — on the stand's shared runtime
  about a third of the size) with `Content-Encoding: gzip`; the store never compresses on the
  fly. `HttpURLConnection` on Android and `NSURLSession` on iOS ask for it and decompress
  transparently; the client caches the decompressed bytecode, which Hermes maps directly.
  `GET /bundle/<hash>.sig` is the bundle's Ed25519 signature when the release signed it; the
  client checks the hash and, when the app has trusted keys (`RdmaHost.PUBLIC_KEYS`), the
  signature with `RdmaBridge.verifyBundle` before staging (Monocypher in the C++ runtime);
- `GET /health`.

The client side is app code, not a framework module yet: the demo kernel's `StoreClient`
(`installStore`, `resync`) posts to `/resync`, downloads missing bundles into a
hash-addressed cache directory, stages the polyfills and the plugin and applies them (in the
demo the user's Generate is the update point; the test stand applies right after a re-sync). The demo
dev server (`demo/server`) adds `/generate`, which compiles submitted code into a plugin
release.

## Old and new hosts

The store serves each host what its runtime understands: polyfills are keyed by the host's
`H`, and per-generation bundles by its `G`
([toolchain_compatibility.md](../design/toolchain_compatibility.md)). A plugin built against
a newer kernel runs on an older host through `F` polyfills; an older plugin runs on a newer
host unchanged, because the call form only grows
([plugin_codegen.md](plugin_codegen.md#call-form-v1)).
