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
| `hotfix-release` | like `dynamic-release`, but rejects anything that would raise the minimum host version (a hotfix fixes a bug, it does not change the API) |

```bash
scripts/release.sh <command> <kernel_manifest_<platform>.json> <versionsDir> [polyfillOutDir] [internalModules] [telemetryFile]
```

or the `release` GitHub Actions workflow. With a `telemetryFile` (a JSON array of host
versions in the field), a release that raises the minimum host prints how many installs it
still reaches and which symbol is responsible.

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
symbols is always possible: older apps get them as polyfills.

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
