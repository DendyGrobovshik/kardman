# Design: store & sync

How the store serves bundles, how a client gets exactly what it needs, and what a
bundle physically is. The versioning model behind the store (kernel `H`, toolchain `G`,
the P1–P4 state machine) lives in [compatibility.md](compatibility.md) and
[toolchain_compatibility.md](toolchain_compatibility.md).

## 1. Bundle format

A **module** is one Hermes bytecode file (`.hbc`), addressed by its `sha256`. A
**manifest** is a small signed JSON that describes one plugin release as a graph of
modules:

```json
{
  "pluginId": "alice:counter",
  "version": 7,
  "builtAgainstKernel": 42,
  "builtAgainstToolchain": "<g>",
  "modules": [
    { "name": "kotlin-kotlin-stdlib", "hash": "sha256:…", "deps": [] },
    { "name": "androidx-compose-runtime-runtime", "hash": "sha256:…", "deps": ["kotlin-kotlin-stdlib"] },
    { "name": "demo-plugin-alice-counter", "hash": "sha256:…", "deps": ["androidx-compose-runtime-runtime"] }
  ]
}
```

Rules:

- The dependency edges come from the module's own compiled `define([...])`, so they are
  a pure function of the module's content — never host-specific.
- The manifest is **signed** (Ed25519). The app embeds the store's public key and
  verifies in native code **before** executing anything. `keyId` allows future rotation.
- Modules are **content-addressed and immutable**: a release adds new modules and a new
  manifest; nothing is mutated or deleted in place.
- Compression is **transport-only**: modules travel as `.hbc.gz` (gzip — zlib is present
  on both Android and iOS), but the on-disk cache stores uncompressed `.hbc` so the
  runtime can mmap them. Default level is gzip `-6`: on the ~7 MB shared-runtime it
  yields ~34% ratio (~66% savings). `-9` gains <0.1pp; zstd `-3` is slightly better
  (~32%) but is deferred to avoid a new `libzstd` dependency. Decompression speed is
  level-independent (~7 ms per 7 MB on a local Mac); final on-device timing and a
  full-size shared set are follow-ups.

The graph a host loads is derived, not stored:

```
shared(G)  ∪  plugin(plugin, version, G)  ∪  polyfills(H, G)
```

`shared(G)` are the modules compiled into `shared-runtime` for that generation
(Kotlin stdlib, coroutines, Compose runtime) — the same set for every plugin of the
same `G`. Polyfills are per-generation too, so their keys are `F:<module>:<H>:<G>` and
`R:<module>:<G>`.

## 2. Cache

The client cache is a flat `hash → .hbc` store on disk. "Is everything current" is
answered by content hash alone: if the store's manifest references only hashes the
client already has, nothing needs downloading. There is no separate cache version.

## 3. Sync protocol

One round trip. The client sends what it has and what it wants:

```
resync { pluginIds, version, hostVersion (H), hostGeneration (G), myHashes }
```

The store resolves the newest compatible release and returns, in the **same response**,
the signed manifest plus only the missing modules:

```
{ manifest, missing: [{hash, bytes}…], polyfills, rollout, severity }
```

- Freshness is TTL-based: on open, if the last sync is younger than `syncTtl`
  (config, e.g. 5 minutes), render from cache with no network. Otherwise re-sync.
- In-flight requests are coalesced: if a re-sync for a plugin is already in flight, no
  second one is sent. Re-sync is read-only and safe to retry.
- `CRITICAL` severity (security/critical bug) is applied within the TTL window; a push
  channel for instant criticals is a future option.

## 4. Deep-link flow

1. A deep link is an intent to open a page → it names one or more plugins.
2. If those plugins are fresh in cache (within TTL), render immediately.
3. Otherwise one re-sync fetches the manifest + missing modules in one round trip,
   which are written to cache and loaded in graph order.
4. On `CRITICAL`, block rendering until the update is applied.

## 5. Publishing without a pause

Bundles are immutable content-addressed files. Publishing is:

1. write the new modules (a `PUT` of new files, never an overwrite),
2. atomically flip the **rollout pointer** (the "latest version of plugin P, generation
   G, manifest M" record) that re-sync reads.

Readers always see a consistent old-or-new state; there is no pause.

## 6. Serving (two planes)

Bundles and re-sync have different shapes, so they are served separately:

- `/bundle/<hash>` → **static storage / CDN** (immutable, huge cache hit, scales trivially).
- `/resync` → a **lightweight service** (small JSON: resolve + diff + manifest).

The current single `StoreHttpServer` (plain `com.sun.net.httpserver`) is the dev
prototype; this two-plane split is the production target.

## 7. Future work

- **Observability / dashboard** — a view of "kernel version × generation × plugin →
  which release is served", for operators.
- **mmap loading** — today the runtime copies `.hbc` bytes into a string and evaluates
  via the JSI source path; switching to Hermes's bytecode-file API would remove that copy
  and speed up startup (needs verification against this Hermes version's API).
- **Bundle encryption** — signing is enough for authenticity; add envelope encryption
  only if a concrete confidentiality threat model appears.
