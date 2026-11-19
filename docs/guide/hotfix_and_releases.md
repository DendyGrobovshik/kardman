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
# Hotfixes and releases

Plugins and the kernel ship differently:

- **Plugins** are bundles served by the store; a new plugin version reaches installed apps
  without an app update.
- **The kernel** is native code inside the app. It changes with an app update (a *static*
  release) or, for some changes, through a **hotfix**: the changed functions are delivered
  as JS *polyfills* and run instead of the native ones (a *dynamic* release).

The versioning model behind this — the changelog, the native version `H`, polyfills,
plugin states — is [compatibility.md](../design/compatibility.md). This page covers what
you run and what to expect.

## Releasing the kernel

CI is the only writer of the changelog. Every release runs `build → checks → tests →
write`; the changelog write is the commit point, so an error before it writes nothing.

| Command | What it does |
|---|---|
| `static-release` | records the kernel version baked into the next app build (moves `H`) |
| `dynamic-release` | records a kernel change for installed apps and builds the `F` polyfills for it |
| `hotfix-release` | like `dynamic-release`, but rejects anything that would make a symbol need a newer host (a hotfix fixes a bug, it does not change the API) |

```bash
scripts/release.sh <command> <kernel_manifest_<platform>.json> <versionsDir> [polyfillOutDir] [internalModules] [telemetryFile]
```

or the `release` GitHub Actions workflow. With a `telemetryFile` (a JSON array of host
versions in the field), a release that makes symbols need a newer host prints how many installs
it still reaches and which symbol is responsible.

A dynamic release is **all or nothing**: it runs every check first and reports all problems
together — what cannot ship and why, which other changes do not ship because of them, and what
to do instead. Nothing is written when any check fails. Run with `DRY_RUN=1`
(`scripts/release.sh`) or the workflow's `dryRun` input to see the report — and what would
ship — without releasing; a static release then only lists the changes it would record.

Bundles go into the store with a gzip copy made at release time (`<key>` and `<key>.gz` in
`versions/bundles/`), which the store serves to clients as is. `plugin-release` publishes the
plugin bundles itself; a compiled polyfill is published with
`./gradlew :rdma-kernel-compiler-plugin:publishBundle -PversionsDir=… -PbundleKey=F:<module>:<H> -PbundleFile=…/polyfill.hbc`.

A plugin is always built against a released kernel: the plugin build reads the
changelog's `h` and fails if there is none.

## What a hotfix can change

A hotfixed function runs its new version **everywhere** — for plugins, for other
polyfills and for kernel code that calls it. Where that cannot be guaranteed yet, the
release refuses the hotfix instead of shipping a partial one. Today a changed symbol can
be hotfixed when it is a top-level `@RDMA` function that is

- not `suspend`,
- not `@Composable` (not a widget),
- without parameters or a result of a function type.

A dynamic release that changes anything else (a class, a widget, a `suspend` function, …)
is rejected with the symbol and the reason; ship it with an app update. *Adding* new
symbols is possible when they compile to JS: older apps get them as polyfills. A symbol
that calls a platform SDK or a JVM/Native-only library cannot run as a polyfill — write a
manual `@Polyfill` for it or ship it with an app update. New widgets cannot be polyfilled
yet.

A hotfix must not split kernel state between the native and the polyfill; see
[how_to_write_kernel.md](how_to_write_kernel.md#state).

Kernel code calling a hotfixed function runs the polyfill on the plugin thread; see
[threads_and_performance.md](threads_and_performance.md#hotfixes-and-locks) for what that
means for locks.

## Removing symbols

Removing a public `@RDMA` symbol needs a prior `@Deprecated` period, because older plugins
may still call it:

- in an `internal` module the release is rejected;
- in a user module it is a warning, and an `R` polyfill keeps the symbol working for old
  plugins.

A removed name can never be reused.

## Minimum host of a plugin

A plugin declares the oldest app version it supports with `rdmaMinHost` in its
`gradle.properties`; by default it is the oldest supported kernel (`kernel.oldestActiveKernel`
in `rdma.toml`, or the first released kernel). It never moves on its own: when the plugin uses a
kernel symbol that older apps cannot get (a widget or other native code added later), the plugin
build fails and names the symbols. Raise `rdmaMinHost` to drop those apps — they keep the last
plugin version that suits them — or stop using the symbols.

## When an update takes effect

The app decides when new code runs. A re-sync only downloads and **stages** bundles
(`RdmaBridge.stageUpdate`); they take effect at an **update point**: a cold start, or
`RdmaBridge.applyUpdates()`, which the app calls where the user does not watch plugin content
(between screens, back from the background). At that moment all staged bundles apply as one
step: every place showing an updated plugin is recreated with the new version (its `remember`
state starts over), and hotfixed kernel functions switch for every caller. Stage polyfills
before the plugins that need them.

## The store

The store (`:rdma-store`) serves plugin bundles and polyfills:

- `POST /resync` — the app sends what it has (plugin ids and versions, its `H` and
  generation `G`) and receives the plugin's state, the target version and the bundles it
  is missing;
- `GET /health` — liveness.

What the app does with the answer: [plugin_loading.md](../internals/plugin_loading.md).
Open operational questions (offline fallback, re-sync backoff, bundle signing, keeping
plugin state across a live update) are listed in
[compatibility.md §12](../design/compatibility.md).
