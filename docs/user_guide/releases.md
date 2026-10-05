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
# Releases & operations

How to run releases and operate the store. The versioning model behind these
commands (changelog, `H`, polyfills, the P1–P4 state machine) lives in
[compatibility.md](../design/compatibility.md); this page only covers the
user-facing commands and endpoints.

## Release commands

CI is the single writer of the changelog. The release order is always
`build → checks → tests → write`, and the changelog write is the commit point —
an error before it writes nothing.

| Command | What it does |
|---|---|
| `static-release` | bump the counter and move `H` (app rebuild) |
| `dynamic-release` | bump the counter without moving `H`; materialize the `F` polyfill |
| `hotfix-release` | like `dynamic-release`, but **rejects a `minHost` bump** (a hotfix is a bugfix with an unchanged API) |

Run via:

```bash
scripts/release.sh <command> <analysis.json> <versionsDir> [polyfillOutDir] [internalModules] [telemetryFile]
```

(the same args the `:rdma-kernel-compiler-plugin` release tasks accept), or via
the `release` GitHub Actions workflow. When a `telemetryFile` (a JSON array of
host versions) is provided, a release that raises `minHost` prints a coverage
warning showing the reach % and the responsible non-emulatable symbol.

## Store endpoints

The store (`:rdma-store`) exposes:

- `POST /resync` — on every re-sync (screen open + periodically) the client sends
  `(id, version, builtAgainst, hostVersion)` and receives
  `{state, severity, status, targetVersion, bundleHash, polyfills}`.
- `GET /health` — liveness/readiness.

State and severity follow the P1–P4 machine and the severity table in
[compatibility.md](../design/compatibility.md) (§7.2, §7.4).

## Open concerns

Unresolved operational questions (also listed in
[compatibility.md](../design/compatibility.md) §12): offline/store-outage
fallback, re-sync backoff at scale, bundle signing, and preserving plugin
`remember`/state across a live version update.
