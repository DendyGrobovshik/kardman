# Versioning

The versioning state of the kernel is written by the release pipeline into a **versions directory**.
The **changelog** is the single source of truth; everything else is derived from it. The directory
is generated (not committed to Git) and its location is configurable:

- the release CLI (`scripts/release.sh` / the `staticRelease` & `dynamicRelease` Gradle tasks) takes
  `-PversionsDir=<dir>` and writes there;
- the plugin Gradle plugin reads the same directory via the `rdmaVersionsDir` Gradle property
  (default `versions/` relative to the root project).

The in-repo integration test (`tests/rdma-integration-tests/e2e/plugin-lifecycle.sh`) runs entirely
against a scratch directory under `mktemp -d`, so each run starts from an empty state.

## Layout

```
<versionsDir>/changelog.json                 # append-only journal (source of truth)
<versionsDir>/<moduleId>/rdma_hashes.json    # derived per-module native-state snapshot
<versionsDir>/<moduleId>/rdma_floor.json     # derived per-node floor map (§5.3)
<versionsDir>/<moduleId>/rdma_sources.json   # source snapshot for R polyfills (§5.1)
```

## `changelog.json`

An append-only JSON journal — one entry per kernel release. A single global monotonic kernel version
`version` is written directly into each entry, plus a `time` timestamp as a safety net. `h` is the
native version: the counter value baked into the app at the last `static-release`.

```json
{
  "h": 2,
  "entries": [
    {
      "version": 1,
      "time": "2026-09-25T10:00:00Z",
      "module": "internal",
      "changes": [
        { "fqn": "com.example.kernel.foo", "kind": "add",    "hash": "…" },
        { "fqn": "com.example.kernel.bar", "kind": "modify", "hash": "…" },
        { "fqn": "com.example.kernel.baz", "kind": "remove" }
      ]
    }
  ]
}
```

- `kind` is one of `add` / `modify` / `remove`. `remove` has no `hash` (the declaration is gone).
- A `remove` **tombstones** the FQN forever: re-adding it is a build error, so the per-FQN history
  stays monotonic and an old plugin can never pick up a new symbol with the same name but different
  semantics.

## Derived state

- **Manifest** (state at version `V`) and **native state** (state at `H`) are *not stored* — they
  are folded from the changelog entries (`version ≤ V` / `version ≤ H`). `add`/`modify` set the
  hash, `remove` deletes the FQN.
- `versions/<moduleId>/rdma_hashes.json` is a derived snapshot of a module's native state, written
  by `static-release` for readability / backward compatibility. Do not hand-edit it.
- `versions/<moduleId>/rdma_floor.json` maps each polyfill node to its `floor` (§5.3); the plugin
  build reads it to compute `minHost`.
- `versions/<moduleId>/rdma_sources.json` snapshots every symbol's declaration source, so a later
  `remove` can materialize the `R` polyfill (§5.1).

## Removal gate (§7.1)

Removing a public `@RDMA` boundary symbol requires a prior `@Deprecated` period:
- for an `internal` module — **error** (the release is rejected);
- for a user module — **warning** (the release passes and an `R` polyfill is materialized from the
  source snapshot).

Private helpers and class members are exempt.

## Who reads / writes it

- **Only CI writes the changelog**, at merge time (never a developer locally). Git serializes
  through the merge queue, so there is no double bump.
- **`static-release`** bumps the counter and moves `H` (heavy, rare, app rebuild): it diffs the
  freshly emitted `rdma_analysis.json` against the module's native state and appends one entry.
- **`dynamic-release`** bumps the counter *without* moving `H`: it materializes the `F` polyfill
  (everything after `H`), then appends one entry. A plugin-only release does not touch the counter.
- The plugin build (`builtAgainstVersion()` in `rdma-plugin-gradle-plugin`) reads the changelog's
  `h` to know which kernel version the plugin is built against; if the changelog is absent (kernel
  never released) the build fails with an explicit error rather than silently assuming `0`.

## Notes

- The hash is computed over the **whole declaration** (signature + body), "as is" (no
  normalization). Reformatting or whitespace-only changes therefore count as a change.
- Granularity is **member-level**: each class method/property is hashed separately, and the class
  header (annotations, modifiers, name, type parameters, supertypes, primary constructor) is
  hashed as its own structural unit.
