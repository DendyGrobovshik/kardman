# Operations (runtime / infrastructure)

This document covers the runtime and infrastructure concerns that sit outside the versioning
model itself. They are pointers — the versioning semantics are in `compatibility.md`.

## Release tooling (§9)

CI is the single writer of the changelog. The release order is always
`build → checks → tests → write`, and the changelog write is the commit point — an error before
it writes nothing.

- `static-release` — bump the counter and move `H` (app rebuild).
- `dynamic-release` — bump the counter without moving `H`; materialize the `F` polyfill.
- `hotfix-release` — like `dynamic-release`, but **rejects a `minHost` bump** (a hotfix is a
  bugfix with an unchanged API).

Run via `scripts/release.sh <command> <analysis.json> <versionsDir> [polyfillOutDir]
[internalModules] [telemetryFile]` (the same args the `:rdma-kernel-compiler-plugin` release
tasks accept), or via the `release` GitHub Actions workflow. When a `telemetryFile` (a JSON array
of host versions) is provided, a release that raises `minHost` prints a coverage warning showing
the reach % and the responsible non-emulatable symbol.

## Shared-state detection (§6.5)

`RdmaSharedState` detects whether a module has **shared mutable non-`@RDMA` state** — a mutable
private variable reachable from two or more `@RDMA` boundary symbols. With shared state the
polyfill must stay monolithic (it cannot be safely cut). `RdmaDeltaBundle` uses this to decide
between a monolithic bundle and content-addressed per-piece bundles (§8.3).

## Reconciliation & store (§8.2)

The store (`:rdma-store`) exposes `POST /resync` and `GET /health`. On every re-sync (screen
open + periodically) the client sends `(id, version, builtAgainst, hostVersion)` and receives
`{state, severity, status, targetVersion, bundleHash, polyfills}`. State and severity follow the
P1–P4 machine (§7.2) and §7.4; `R` polyfills are dropped per the retention policy (§7.3).

## Open operational concerns (§12)

- **Offline / store outage** — serve basic versions from a baked-in cache; behavior without
  network is TBD.
- **re-sync scale** — backoff / exponential delay against a "once a minute" storm.
- **Supply-chain / bundle signing** — trust in the store and authors.
- **Plugin state on update** — preserving `remember`/state during a live v5→v6 screen update.
