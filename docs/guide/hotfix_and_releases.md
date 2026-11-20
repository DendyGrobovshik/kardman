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

CI is the only writer of the changelog. A release runs in this order, and nothing in
`versions/` changes before the last step:

1. **tests** — the hard gate;
2. **prepare** — every check runs; the polyfill sources and a release plan go to a work
   directory;
3. **build** — the polyfills are compiled for every live toolchain generation;
4. **contract tests** — your kernel's tests run on an Android emulator against the native code
   and with each polyfill loaded (see below);
5. **commit** — the bundles go into the store, then `polyfills.json` and the changelog are
   written. A failure before this step leaves everything as it was.

| Command | What it does |
|---|---|
| `static-release` | records the kernel version baked into the next app build (moves `H`); older supported apps get the changes as polyfills where they can |
| `dynamic-release` | records a kernel change for installed apps and builds its polyfills for every supported app version |
| `hotfix-release` | like `dynamic-release`, but rejects anything that would make a symbol need a newer host (a hotfix fixes a bug, it does not change the API) |

```bash
scripts/release.sh <command> <kernel_manifest_android.json[,kernel_manifest_ios.json]> <versionsDir> [workDir] [internalModules] [telemetryFile]
```

or the `release` GitHub Actions workflow (one release at a time; it commits the release state
back to the branch and uploads the store bundles). Pass the analyses of both platforms, and
the same ones on every release: a change that exists on one platform only is still a change.
With a `telemetryFile` (a JSON array of host versions in the field), a release that makes
symbols need a newer host prints how many installs it still reaches and which symbol is
responsible.

**Which apps get what.** Polyfills are built for every supported app version — every static
release from `kernel.oldestActiveKernel` on — once per distinct state of the module: app
versions that have the same native code share a bundle. A dynamic release is **all or
nothing**: if a change cannot reach one of those versions, nothing ships. A static release
ships to the new app build in any case and to older apps only where it can; the store keeps
serving those apps plugin versions that do not need the change.

The release reports all problems together — what cannot ship and why, which other changes do
not ship because of them, and what to do instead. Run with `DRY_RUN=1` (`scripts/release.sh`)
or the workflow's `dryRun` input to see the report — and what would ship — without releasing.

### Contract tests

Write tests for your kernel as usual, in the kernel module's `commonTest` (with
`kotlin("test")`), against its public API. They are also the **contract** of your kernel: a
release runs them on an Android emulator twice — against the native code, and with each
polyfill it is about to ship loaded, so the code runs in JS as it will on installed apps. If a
test passes natively but fails with a polyfill, the release is rejected:

```
-- native
  native: OK (12 tests)
-- with F for hosts 2,5
  F hosts 2,5: FAILED
    labelsAPrice: expected:<v3:2.0> but was:<v3:2>
Contract tests failed: the release is rejected and nothing was written.
```

That means the code behaves differently in JS (number formatting, `Long`, a missing API): change
the code, or write a manual polyfill.

- **Locally:** `./gradlew :androidApp:connectedDebugAndroidTest` runs them against the native code.
- **In a release:** set `RDMA_CONTRACT_APP=<your app's Gradle path>` (and
  `RDMA_CONTRACT_APP_ID` if its applicationId is not `org.example`), or the workflow's
  `contractApp` input; an emulator must be connected (the workflow starts one). Without it the
  step is skipped.
- Tests see only the kernel's public API (they are compiled into the app's tests), and run in
  JS only the functions a polyfill replaces; a class added after an app version is checked
  natively.

### Bundles and signing

Every bundle is stored under the hash of its content, with a gzip copy made at release time
(`<hash>` and `<hash>.gz` in `versions/bundles/`) that the store serves as is; `polyfills.json`
says which polyfill serves which app version and toolchain generation. Bundles are signed when
the release has a key:

```bash
./gradlew :rdma-kernel-compiler-plugin:generateSigningKey
```

prints a private key — keep it as the `RDMA_SIGNING_KEY` secret of CI — and a public key for
`rdma.toml`:

```toml
[signing]
publicKeys = ["<base64>"]
```

Apps built with keys check every downloaded bundle — its hash, and the signature by one of
the keys — before staging it, and drop it otherwise. To rotate, add the new key in a static
release, then start signing with it. The app's version `H`, its toolchain generation and the
keys are generated into `RdmaHost` (`VERSION`, `GENERATION`, `PUBLIC_KEYS`) by the app build;
use them when talking to the store.

`plugin-release` publishes the plugin bundles itself; a bundle can also be published by hand
with `./gradlew :rdma-kernel-compiler-plugin:publishBundle -PversionsDir=… -PbundleKey=<hash> -PbundleFile=…`.

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
