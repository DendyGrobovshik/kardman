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
# Versioning

The mechanics of the kernel's version state. The model — `H`, polyfills, plugin states,
compatibility rules — is [compatibility.md](../design/compatibility.md); generations are
[toolchain_compatibility.md](../design/toolchain_compatibility.md). User commands:
[hotfix_and_releases.md](../guide/hotfix_and_releases.md).

## Inputs: two files per kernel module

| File | Written by | Read by | Content |
|---|---|---|---|
| `rdma_api.json` | kernel compiler | plugin compiler | the module's `@RDMA` surface — what plugins compile against |
| `kernel_manifest_<platform>.json` (`android`, `ios`) | kernel compiler (`RdmaSymbolIndexer`) | release CLI | per declaration: hash, signature, source, `hotfixBlocker` |

Despite its name, `kernel_manifest_<platform>.json` is the *analysis of one build*, the
input of a release; the "manifest" of `compatibility.md` is a fold of the changelog (below).

## The versions directory

The release pipeline writes the version state into a directory set by `-PversionsDir`
(release tasks) and `rdmaVersionsDir` (plugin builds, default `versions/`). It is generated,
never committed.

```
<versionsDir>/changelog.json                 # append-only history (source of truth)
<versionsDir>/toolchain.json                 # toolchain generations
<versionsDir>/<moduleId>/rdma_hashes.json    # derived: native state of the module
<versionsDir>/<moduleId>/rdma_signatures.json# derived: signatures, for the compatibility gate
<versionsDir>/<moduleId>/rdma_floor.json     # derived: per-node floor (compatibility §5.3)
<versionsDir>/<moduleId>/rdma_sources.json   # declaration sources, to materialize R polyfills
<versionsDir>/plugins/<pluginId>.json        # plugin releases
<versionsDir>/bundles/                       # plugin and polyfill bundles
```

## `changelog.json`

One entry per kernel release, with a global monotonic `version` and a `time`; `h` is the
native version baked at the last `static-release`:

```json
{
  "h": 2,
  "entries": [
    { "version": 1, "time": "2026-09-25T10:00:00Z", "module": "internal",
      "changes": [
        { "fqn": "com.example.kernel.foo", "kind": "add",    "hash": "…" },
        { "fqn": "com.example.kernel.bar", "kind": "modify", "hash": "…" },
        { "fqn": "com.example.kernel.baz", "kind": "remove" } ] }
  ]
}
```

- `kind` is `add` / `modify` / `remove`; `remove` has no hash and **tombstones** the FQN —
  re-adding it is a build error, so an old plugin never picks up a new symbol with the same
  name.
- The **manifest** (state at version `V`) and the **native state** (state at `H`) are not
  stored: they are folded from the entries (`add`/`modify` set the hash, `remove` deletes).
- Hashes cover the whole declaration (signature + body) as written; whitespace changes
  count. Granularity is per member; a class header is its own unit.

## Release CLI

`RdmaReleaseCli` (`scripts/release.sh`, or the `staticRelease`/`dynamicRelease` Gradle
tasks of `:rdma-kernel-compiler-plugin`). Order: build → checks → tests → write; the
changelog write is the commit point.

- **`static-release`** — diffs `kernel_manifest_<platform>.json` against the native state,
  appends one entry and moves `h`.
- **`dynamic-release`** — appends one entry without moving `h` and materializes the `F`
  polyfill (everything changed after `H`, with its private closure). Rejected when it
  modifies a symbol that cannot be hotfixed yet ([hotfix_slots.md](hotfix_slots.md#release-gate)).
- **`hotfix-release`** — a dynamic release that must not raise `minHost`.
- **`generation-release`** — records a toolchain generation in `toolchain.json`.

Gates:

- **Signatures** — reordering, removing, renaming, retyping or inserting a parameter is
  rejected; appending a defaulted parameter is allowed (default values are not compared).
- **Removal** — removing a public `@RDMA` symbol without a prior `@Deprecated` period is an
  error in an `internal` module and a warning in a user module (an `R` polyfill is
  materialized from `rdma_sources.json`). Private helpers and class members are exempt.

Only CI writes the changelog, at merge time.

## Plugin builds

`builtAgainstVersion()` in the plugin Gradle plugin reads the changelog's `h`; without a
changelog the build fails instead of assuming `0`. `plugin.json` records it with the used
symbols, and the store derives the plugin's `minHost` from `rdma_floor.json`.
