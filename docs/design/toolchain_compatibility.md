# Design: toolchain compatibility

Kardman loads code into an app without updating the app. There are two independent
ways a plugin can stop working, and they need different machinery. This document
covers the second one — the **toolchain** (compiler/runtime) — and how we keep
compiled bundles compatible across upgrades. The first one, the **kernel API**, is
covered in [compatibility.md](compatibility.md).

## 1. Two compatibility axes

| | Kernel axis | Toolchain axis |
|---|---|---|
| What breaks | plugin *source* no longer matches the kernel API | compiled *bundle* no longer loads into the app's runtime |
| Version id | `H` — kernel version (bumped on kernel change) | `G` — toolchain generation |
| Plugin field | `builtAgainstKernel` | `builtAgainstToolchain` |
| Recorded in | `changelog.json` | `toolchain.json` |
| Changes on | kernel API change | Kotlin / Compose / Hermes change |

Both `H` and `G` are baked into the app at `release-static`.

The toolchain is: the **Kotlin compiler** (our fork), the **Compose runtime**, and the
**Hermes engine**. Any of them changing can alter the binary layout of a compiled
bundle even when the source code is unchanged. A shim that emulates the old layout is
impractical (it would mean reimplementing the compiler's codegen), so instead we
**recompile bundles per generation**.

A generation is a content hash of the toolchain:

```
G = sha256(kotlin fork commit, compose-runtime version, hermes commit)
```

The same toolchain yields the same `G`, so bundles of the same `G` are interchangeable.

## 2. How a plugin is resolved

A plugin release carries `(version, builtAgainstKernel, builtAgainstToolchain, minHost)`.

On re-sync the store resolves the **newest** release where `minHost ≤ H` **and**
`builtAgainstToolchain == G`. `G` is an exact binary match, not a floor like `minHost`.

## 3. `toolchain.json`

Append-only journal, one entry per generation:

```json
{
  "generations": [
    {
      "g": "<sha256>",
      "alias": "2.4.10 / 1.11.1 / 0.76.9",
      "kotlin": { "version": "2.4.10-rdma", "branch": "full_stable_js_export_table-2.4.10", "commit": "…" },
      "composeRuntime": "1.11.1",
      "hermes": { "version": "0.76.9", "commit": "a1154cb…", "bytecodeVersion": 99 },
      "time": "…",
      "status": "active"
    }
  ]
}
```

The Hermes component is the **commit** (it determines both the bytecode version and the
JSI C++ API, which can drift independently); `bytecodeVersion` is derived for diagnostics.

## 4. `rdma.toml` — support policy

Edited by the project operator, validated by CI. Declares what is currently supported:

```toml
[kernel]
activeWindow = { lastN = 20 }        # which kernel versions are still supported

[toolchain]
supportedGenerations = ["<g1>", "<g2>"]  # live generations
```

It also holds the framework config that today lives in the `#RDMA` section of
`gradle.properties`.

## 5. Generation lifecycle

A **host** is an installed app with baked `(H, G)`. A host is **supported** while its
`H` is inside `kernel.activeWindow`.

- A generation is **live** while at least one supported host reports it (re-sync
  telemetry).
- It becomes **retired** when the operator runs `retire-generation`. Telemetry only
  *suggests* retirement; the removal of support (and the later GC of its bundles) is a
  deliberate action, so nothing is ever dropped by accident.

## 6. Releases

| Command | Axis | Effect | App update |
|---|---|---|---|
| `dynamic-release` | kernel | bump kernel version, materialize `F`, `H` unchanged | no |
| `hotfix-release` | kernel | `dynamic-release` + gate: `minHost` must not rise | no |
| `static-release` | kernel | bump kernel version, move `H` | yes |
| `generation-release` | toolchain | new `G`, rebuild shared-runtime + all bundles | yes |
| `plugin-release` | plugin | `version++`, compiled per live generation | no |
| `retire-generation` | toolchain | take a generation out of support (operator) | no |

`hotfix-release` is a thin wrapper on `dynamic-release` with the `--hotfix` gate.

Every release produces a **rollout report** (HTML + JSON): a `(plugin × generation)`
matrix of `built-ok | blocked(<reason>) | retired` plus failure logs. When a hotfix
does not build for an old generation, the report says so, and the store keeps serving
that generation the **last successfully built** version — the plugin stays `current`
there, with `latestForGeneration=false` and no severity.

## 7. Building across generations

- **Static release** builds with one toolchain (the new/current `G`).
- **Dynamic release** (a plugin or a kernel polyfill) compiles the same source against
  **every live generation**, because each `G` has its own compiler, Compose and `hermesc`.
- Toolchains are resolved from a Maven repository (local `m2` first), versioned per
  generation (`…-rdma-g<g>`). The expensive ones are the Kotlin fork and Hermes (source
  builds); Compose is an ordinary dependency.

## 8. Migration runbook (upgrading a toolchain)

Ordered steps, run one toolchain at a time to keep failures bisectable:

1. **Kotlin** (longest): port the fork patch onto the new branch → publish under a new
   `G`; migrate our compiler plugins (`rdma-kernel-compiler-plugin`,
   `rdma-plugin-compiler-plugin`) to the new FIR/IR API.
2. **Compose**: bump the version; recompile `shared-runtime` + bundles; check
   `ComposeAllowlist` / `RdmaComposerProtocol` / `CAbiComposerProxyGenerator` still match.
3. **Hermes**: new commit; refresh the JSI headers; re-apply the static-JSI patch;
   recompile every `.hbc` (bytecode version).
4. Verify: unit + contract tests, the differential matrix (`native|polyfill` × `old|new`),
   screenshot tests, then `generation-release` + report.

| Toolchain | Effort | Why |
|---|---|---|
| Hermes | days | mechanical re-pin + recompile; risk is JSI C++ API drift |
| Compose | days | recompile + verify the Composer protocol symbols |
| Kotlin | weeks | port the fork patch + migrate compiler plugins (FIR API) |
