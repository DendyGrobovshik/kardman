# Design: versioning, polyfills and compatibility

## 0. About this document

Kardman lets you load code into an app **without updating the app itself**. The user writes a plugin and it appears in the app; the kernel (the code inside the app) changes over time. There are two risks we must cover:

- a **new plugin** must work on an **old** app (backward compatibility);
- an **old plugin** must work on a **new** app (forward compatibility).

This document describes how versions are structured, what a polyfill is (a JS replacement for native kernel code) and how the system guarantees both kinds of compatibility while requiring almost nothing from the plugin developer.

This is the **kernel** axis: it answers whether a plugin's *source* still matches the
kernel API. The **toolchain** axis — whether a *compiled* bundle still loads into an
app's runtime when Kotlin/Compose/Hermes change — is covered in
[toolchain_compatibility.md](toolchain_compatibility.md); the store and bundle format in
[store_and_sync.md](store_and_sync.md).

## 1. Terminology

- **Plugin** — code written by the user and loaded dynamically.
- **Live code** — any dynamic code that updates without an app update. Covers **plugin** and **polyfill**.
- **Kernel** — the foundation code inside the app. Consists of our modules (`internal`, standard functionality) and user modules (`user:<username>`). Contains both emulatable and non-emulatable code (the latter only for `internal`).
- **Native** — kernel code baked into the app and updated together with it.
- **Bundle** — a unit of live-code loading. Kinds: **plugin bundle**, **polyfill bundle**, **delta bundle**.
- **Polyfill** — a JS implementation of a kernel symbol, substituted instead of the native one. An **auto-polyfill** is generated automatically, a **manual polyfill** is written by a human.
- **Changelog** — an append-only JSON journal of kernel changes; the single source of truth.
- **Manifest** — the current state of a module (`symbol → hash`); derived from the changelog.
- **Native state** — the set of kernel symbols baked into the native at a specific version `H`; derived from the changelog.
- **Kernel version** — a single global monotonic number; **bumped only on a kernel change**.
- **H** — the native version: the counter value baked into the app at `release-static`.
- **Plugin version** — `version` (a monotonic number) + `builtAgainstKernel` (the kernel version the plugin was compiled against) + `builtAgainstToolchain` (the toolchain generation).
- **Store** — server/storage that serves bundles and from which the client does re-sync.

Manual annotations (the only ones the developer writes): `@RDMA`, `@Deprecated`, `@Polyfill`.

- **Generation (`G`)** — a content hash of the toolchain (Kotlin fork commit, Compose runtime version, Hermes commit), baked into the app. The binary-compatibility axis; an exact match (unlike the `minHost` floor). See [toolchain_compatibility.md](toolchain_compatibility.md).

## 2. Version model

### 2.1 Changelog

One shared file for the whole kernel, next to `build.gradle.kts`. Append-only JSON: one entry per kernel change/release. The version is written directly in the changelog, and the script appends a **timestamp** as a safety net.

```json
{ "version": 42, "time": "2026-09-25T10:00:00Z", "module": "internal",
  "changes": { "com.example.kernel.foo": { "kind": "modify", "hash": "…" },
               "com.example.kernel.bar": { "kind": "add",    "hash": "…" },
               "com.example.kernel.baz": { "kind": "remove" } } }
```

Each entry also records its release `type`: `static`, `dynamic` or `hotfix`. Static entries are
the versions apps are built with — the **host versions**; a static release with nothing new
still writes an entry (without changes), so every host version is known. The supported hosts
are the static versions from `kernel.oldestActiveKernel` on.

- **Only CI writes the changelog**, at merge time, never a developer locally. CI is the single writer: it allocates the next version number and appends the entry. Git serializes through the merge queue, so there is no double bump and no manual corruption; no conflict auto-resolution is needed — there is no conflict.
- The changelog describes the addition, change and removal of declarations, along with their hashes.
- **Tombstone**: a `remove` entry "buries" the FQN forever. Re-adding the same FQN is a **build error**. The per-FQN history is monotonic, so the `R` polyfill is always unambiguous and an old plugin can never pick up a new symbol with the same signature but different semantics.

The changelog is the source of truth. **Manifest** and **native state** are derived: the manifest at version `V` is the fold of all entries with `version ≤ V` (add/modify set the hash, remove deletes); the native state is the fold at version `H`. Hashes are full (over the whole declaration), so "last hash wins" is computed trivially.

### 2.2 Plugin version

The plugin has three numeric characteristics:

- **`version`** — a monotonic number per plugin, incremented on every plugin release. From it the store and the client know whether the plugin is stale and whether an urgent update is needed, e.g. in the case of a critical bug.
- **`builtAgainstKernel`** — the kernel version the plugin was compiled against. From it the store knows whether the plugin uses deprecated (`@Deprecated`) or removed API.
- **`minHost`** — the minimum host version the plugin runs on (see §5.3).

### 2.3 Plugin contract

> `[version, builtAgainstKernel, builtAgainstToolchain, minHost, module-deps]` + code.

`module-deps` — the fixed set of kernel modules the plugin sees (`internal` + its own `user:*`). `H` comes from the app itself.

## 3. Use cases

1. The user creates a plugin.
2. The user updates the plugin code.
3. The user deletes a plugin.
4. We change part of the kernel.
5. We destructively change the public part of the kernel (remove a method, class).
6. The user adds functionality to the kernel that can be emulated.
7. The user removes their functionality from the kernel that can be emulated.
8. The user requests functionality in the kernel that cannot be emulated.
9. The user releases a new version of the plugin that restores the previous behavior (regression fix).

### Comments

- **1, 2, 3, 6, 7, 9** — without an app update (`release-dynamic`).
- **6** — an optimization: the user moves heavy logic (e.g. parsing) into the kernel. Until the next `release-static` this is a polyfill, then it is baked into the native; baking is invisible to the user.
- **4** — if emulatable, no app update (hotfix): bump the counter, polyfill, `H` does not move. **A hotfix must not bump `minHost`** (see §9). If non-emulatable — only `release-static`.
- **5, 7** — removal of public API. Removing an `@RDMA` symbol (ours or a user's) must go through `@Deprecated`: first the symbol is marked `@Deprecated` (transparent in code — the plugin developer sees a warning and migrates; `replaceWith`/LLM help), and only when nobody uses it is it removed (`changelog remove`). Removal without `@Deprecated`: for `internal` — **error** (release rejected); for a user module — **warning**: the release passes, and we back them up by serving `R` polyfills so old plugins don't break on new hosts.
- **8** — non-emulatable functionality is added **only by us**; the user must justify it; then → case 4 → `release-static`.
- **9** — there is no "rollback" as a backward move: we release a new version with the previous code, `version` grows forward, clients update via re-sync (§8.2). This is safer than moving the "current" version backward.
- **Typical flow**: a simple user writes code in the plugin — it appears in the app by itself. An advanced user writes only business logic in the plugin and moves heavy parts into the kernel. Rarely — non-emulatable functionality is needed, and we add it on justification.
- "Doesn't require an app update ≠ doesn't require a version bump": `release-dynamic` bumps the counter but does not move `H`.

## 4. Code organization

- Creating a plugin creates a new module.
- Plugin code does **not** use functionality of other plugins.
- Plugin code may use common kernel functionality.
- Plugin code may use functionality from its "own" kernel modules (`user:*`; their owner is responsible for their compatibility).
- The kernel has our modules with standard functionality (`internal`).
- The user may create their own kernel module; it may be baked into the native at the next `release-static`.
- In a **user kernel module** arbitrary system functions cannot be used (they won't compile to JS); for parsing/heavy computations the available features are enough.
- The user is responsible for their own kernel module.
- Code that must be available from a plugin is marked `@RDMA`.
- **FQN collisions between modules are not a problem**: compile-time resolution goes by import (the plugin compiler knows which module's symbol is meant), and at runtime each module is already wrapped in its own namespace. Additionally, a plugin sees only `internal` + its own `user:*`, so a real overlap is almost impossible.

## 5. Compatibility

Principle: **compatibility is determined by the API used, and the range is a consequence of its lifetime intervals.**

### 5.1 Two sides — and what `F` and `R` are

There are two sets of polyfills, and it is important not to confuse them:

- **`F(module, H, G)`** — "what was *added or changed* in the kernel after version `H`". These are polyfills that let a **new plugin** work on an **old** app: the symbol appeared after `H`, so it isn't in the old native — we provide a JS version.
- **`R(module, G)`** — "what was removed from the kernel *without a completed migration*". A safety net: normally removal is preceded by a `@Deprecated` period, so there are no old users; `R` catches only those who didn't migrate in time (or removed without deprecation). That is, these are polyfills that let an old plugin work on a new app where some functionality was cut out of the kernel.

`G` in the keys: a polyfill is compiled to Hermes bytecode, so it is bound to the
generation's `hermesc`/bytecode version (see [toolchain_compatibility.md](toolchain_compatibility.md)).
Both sets are pre-built and live in the store; the app downloads what it needs by `H`, `G` and `module-deps` (details in §8.1).

### 5.2 Emulatability — three levels

1. **Auto-emulatable** — the materializer generates JS itself → auto-polyfill.
2. **Manually-emulatable** — the auto-materializer can't, but a human rewrites the semantics through `@RDMA` primitives → manual polyfill.
3. **Non-emulatable (fundamentally native)** — there is no JS version (widget, Compose base protocol, JNI capability) → a polyfill is impossible → only `@Deprecated` → honest removal.

Everything that can be emulated, automatically or by hand, is emulated. Nothing is special by
kind: a `@Composable` widget built from `@RDMA` primitives, a class, a function all follow the same
rule. **Non-emulatable** means the code does not compile to JS (it calls a platform SDK or a
JVM/Native-only library) **and** a manual polyfill is not realistic.

The kernel compiler marks a declaration non-emulatable when it calls code of another module that
does not exist in the plugin runtime (anything outside the Kotlin stdlib, `kotlinx.coroutines`,
the Compose runtime and other modules' `@RDMA` symbols) and has no manual polyfill. The
materializer's JS compile stays the final judge: a call the check misses fails the dynamic release,
it never ships. The compiler cannot tell level 2 from level 3; a human makes the distinction by
writing a manual polyfill.

*Current limit:* the materializer cannot build widget polyfills yet, so a `@Composable` symbol is
still treated as non-emulatable (it anchors the `floor`). This is an implementation gap, not part
of the model.

### 5.3 `floor(S)` and `minHost`

**`floor(S)`** = the minimum host version on which symbol `S` is available:

- `S` non-emulatable → `floor(S)` = the version of its introduction (static release);
- `S` emulatable → `floor(S)` = the maximum `floor` of non-emulatable symbols in its transitive closure (or 0).

**`minHost(plugin) = max(floor(S))`** over the symbols used. `floor` is computed by the kernel compiler from the IR + the usage graph and put into the manifest. Only **non-emulatable** symbols set the lower bound — an emulatable symbol can be polyfilled back to its non-emulatable anchors.

**`floor` in the manifest and its recalculation.** Each symbol in the manifest carries a `floor` (0 — no restrictions, or N). It is recalculated recursively: as soon as a non-emulatable anchor appears or changes in the transitive closure, the `floor` of all dependent symbols updates.

**`minHost` moves only by hand.** The plugin developer declares it (`rdmaMinHost`); by default it
is the oldest host the plugin can meet — the support floor `kernel.oldestActiveKernel`, or the first
released kernel when there is none. The computed `max(floor(S))` is a **check**, never a value
that is written: when a plugin uses a symbol whose `floor` is above the declared `minHost`, the
plugin build fails, naming the symbols, and the developer either raises `minHost` explicitly or
stops using them. The audience never narrows silently. Hosts below a raised `minHost` stay on the
last version that suits them (it keeps working, the new functionality just doesn't reach them) —
a valid scenario, not an error. Forcing an app update is not the framework's job; an app that
wants it can build it on top.

### 5.4 Backward compatibility (new plugin on an old host `H`)

- The plugin uses an **emulatable** `S`, introduced after `H` → the store sends `F(module, H, G)` (the polyfill for `S`).
- The plugin uses a **non-emulatable** `S` with `floor(S) > H` → `minHost > H` → **refusal** (on such an old host the new plugin version cannot be used).

### 5.5 Forward compatibility (old plugin on a new host)

- The normal scenario is `@Deprecated` → migration → removal, so an old plugin on a new host does *not* reference the removed symbol.
- `R` kicks in only if the symbol was removed before migration (for `internal` — impossible, error; for a user module — a possible mistake): then the old plugin receives an `R` polyfill, and we back the user up.
- A non-emulatable `S` can be removed only by: honest removal after migration (no users left) or a manual polyfill (→ emulatable, in `R`). We **monitor and prevent** such situations: before removing a non-emulatable symbol we force the plugin authors to update (see §7.4), and we remove only when we are sure nobody is harmed.
- A removed FQN is **tombstoned** (§2.1): it never reappears, so an old plugin cannot get "a new symbol with the same name but different semantics".

### 5.6 Parameter-level compatibility (default parameters)

The changelog's compatibility signal is a whole-declaration hash, which cannot tell a
*compatible* parameter-list change from a *breaking* one. The release pipeline therefore
runs a structural **signature check** on every `@RDMA` callable (function, method,
constructor), diffing the ordered parameter list `(name, type, hasDefault)` against the
committed baseline:

| Change | Verdict |
|---|---|
| identical list (body / default *value* changed) | ✅ compatible — normal `modify` → `F` / bake |
| appended one or more **trailing defaulted** parameters | ✅ compatible — old callers pass fewer arguments; the runtime fills the default |
| reorder / remove / rename / retype a parameter | ❌ release rejected |
| insert a parameter before the end | ❌ release rejected |
| add a **required** (non-defaulted) parameter | ❌ release rejected |

The only *compatible* signature change is appending trailing defaulted parameters, which is
why defaulted parameters are constrained to be trailing (§ "Default parameter values" in
[types.md](../guide/types.md)). A defaulted parameter added this way is filled on the **receiving**
side — the native bridge fills it via a bitmask, the dynamic polyfill via a prologue that
checks `arguments.length` — so both an old plugin (forward) and a new plugin that does not
use the new parameter (backward) keep working.

## 6. Polyfills

### 6.1 What goes into a polyfill (dirty subgraph)

A polyfill can't be "just one function": the function calls other private functions, which call yet others. So when a symbol changes, the polyfill gets **the symbol itself + everything it calls along the chain, up to the @RDMA boundary**:

- **non-`@RDMA` (private) helpers** are pulled inside the polyfill — otherwise the polyfill wouldn't have enough code;
- **at other `@RDMA` symbols** the closure is cut — they are public, and either already in the native or polyfilled by someone else.

This "symbol + its private closure, cut at the public boundaries" is called the **dirty subgraph**. It is computed from the usage graph (who calls whom) plus the hash diff (what changed).

```
┌─────────────────────────────────────────────────────┐
│                     Goes into the polyfill          │
│                      ┌─────────────────┐            │
│                      │ @RDMA fun foo() │            │
│                      └────────┬────────┘            │
│                ┌──────────────┘        │            │
│                ▼                       │            │
│       ┌─────────────────┐              │            │
│       │private fun boo()│              │            │
│       └────────┬────────┘              │            │
│                ▼                       │            │
│       ┌─────────────────┐              │            │
│       │private fun doo()│              │            │
│       └────────┬────────┘              │            │
└────────────────┼───────────────────────┼────────────┘
┌────────────────┼───────────────────────┼────────────┐
│                │                       ▼            │
│                │            ┌─────────────────┐     │
│                │            │ @RDMA fun goo() │     │
│                ▼            └─────────────────┘     │
│       ┌─────────────────┐                           │
│       │ @RDMA fun moo() │                           │
│       └─────────────────┘                           │
│                     Not in the polyfill             │
└─────────────────────────────────────────────────────┘
```

In the diagram `foo` pulls the private `boo` and `doo` into the polyfill; the public `moo` and `goo` stay outside — they are available as ordinary `@RDMA` symbols.

### 6.2 Auto-polyfill

Generated by `release-dynamic` for auto-emulatable symbols. Symbols the materializer cannot compile to JS produce a **build error** (a signal: a manual polyfill or the deprecation path is needed).

### 6.3 Manual polyfill

```kotlin
@Polyfill(for = "com.example.kernel.foo")
fun foo_polyfill(...): ... = { /* emulatable implementation via @RDMA primitives */ }
```

- Naming convention: the `_polyfill` suffix.
- A `@Polyfill` declaration is **not part** of the public `@RDMA` surface (excluded from the manifest, plugins don't see it). The materializer picks it up only when the bound symbol needs a JS version.
- A `@Polyfill` function must be **non-public** (`internal` or `private`): the kernel public-API rule rejects public non-`@RDMA` declarations.
- Written by the **module owner**.
- **Tests are mandatory** and are the **symbol's contract**: the native, the auto-polyfill and the manual polyfill must all pass the **same** suite. The native refined its semantics → a test appeared → it runs automatically for the manual polyfill too, otherwise the release fails (see §10). That's how a manual polyfill doesn't drift.

### 6.4 How a polyfill works vs native

The source Kotlin contains a function call; it compiles to a function call in JS. What actually gets invoked — native via JSI/JNI or the polyfill in JS — is determined **at bundle load time**. Mechanically this is just putting a function on a JS object; the caller doesn't care. **The polyfill takes priority over the native** (this is exactly a hotfix); the priority is remembered at load time, with no per-call dynamic checks.

A polyfill replaces a kernel symbol **everywhere**: for plugin code, which calls it through `RDMA.*`, and for native kernel code, which reaches it through hotfix slots (`RDMA.installSlot`). JS is executed only by the Hermes thread, so a native caller on another thread waits for it ([runtime_rework.md §6.4](runtime_rework.md)). A change that some call sites could not run yet — today `suspend` functions, functions with function-typed parameters or results, widgets, class members and properties — is refused as a hotfix and needs a static release ([runtime_rework.md §6.8](runtime_rework.md)).

### 6.5 Shared-state detection (deferred optimization)

To safely cut a polyfill into delta pieces, the materializer must know whether the module has **shared mutable non-`@RDMA` state** (one variable reachable from two or more `@RDMA` symbols): no — we cut into pieces; yes — we keep it monolithic. **Marked as an optimization that is not implemented yet**: without it the polyfill bundle is monolithic and correct. This optimization may be used to shrink the downloaded polyfill when only a little logic changed.

### 6.6 State across runtimes

`@RDMA` is the only boundary between runtimes — for calls and for state alike.

- **Shared state lives behind `@RDMA`**: an `@RDMA` class instance, or a variable read and
  written only through `@RDMA` functions (`getX`/`setX`). It has one home, and both runtimes reach
  it through the boundary. This is the pattern kernel authors are taught.
- **Everything else is runtime-local**: non-`@RDMA` top-level variables, `object` and `companion`
  state, `lazy` values. Like `ThreadLocal`, but per runtime: each runtime that runs code touching
  such a variable has its own copy, initialized by its own initializer, with no synchronization
  between copies. A JS copy appears only when code touching the variable lands in a polyfill's
  dirty subgraph (§6.1).
- **Split detection.** If a polyfill for host `H` touches a runtime-local variable that code
  staying native on `H` also touches, the variable splits. The release fails, naming the variable
  and the code on each side, unless the variable (or its `object`) is annotated `@RuntimeLocal`.
  The annotation changes no semantics: it records that the developer accepts the split (caches,
  diagnostics counters). Exempt: a `val` of a primitive, `String` or enum type whose declaration
  is unchanged — both copies hold the same value. A `val` → `var` change or a new value changes the
  declaration, so it is never missed.
- **No retrofit.** The fix for a split must already be in the native at `H`: an `@RDMA` accessor
  added now is itself polyfilled on `H` and reads the JS copy. Put shared mutable state behind
  `@RDMA` from the start; otherwise the change needs a static release.
- **Reset on swap.** When every user of a variable moves into a polyfill, the JS copy starts from
  the initializer; a value accumulated in the native is not carried over.
- **Instance fields are not runtime-local**: an object lives in one runtime. A polyfill of a method
  of an existing native class cannot reach its non-`@RDMA` fields.

### 6.7 Behaviour, not structure

A dynamic release changes **behaviour**: functions, methods, computed properties without a field.
It cannot change the **structure** of an object the native at `H` creates — the native layout and
construction are compiled in. So these are non-emulatable and need a static release:

- a changed constructor or `init` block, a changed field initializer;
- a method change that reads or writes a non-`@RDMA` field of an existing native class (§6.6).

Classes added after `H` are not affected: they live entirely in JS. To keep construction logic
hotfixable, create objects through an `@RDMA` factory function: it is an ordinary top-level
function with a slot, and the constructor stays thin.

*Open:* a new field on an existing native class (non-emulatable vs. a framework side table with a
constant initializer) and a per-class layout fingerprint in the manifest, which lets the release
tell structure changes from behaviour changes reliably. Until then class changes are refused as a
hotfix altogether (§6.4).

## 7. API lifecycle and plugin currency

### 7.1 Symbol lifecycle (S1–S5)

| Scenario | Emulatable | Non-emulatable |
| --- | --- | --- |
| **S1. Introduction** | changelog `add` → `F`, `minHost` doesn't grow | only `release-static`; the user raises `minHost` |
| **S2. Change** | changelog `modify` → `F`, the hotfix reaches everyone | only `release-static` (or a manual polyfill) |
| **S3. Deprecation** | `@Deprecated`, a mandatory step before removal | same |
| **S4. Removal** | changelog `remove` after migration; `R` only on violation (warning for user, error for internal) | only after S3; honest removal without `R` |
| **S5. Removal from R** | per the retention policy (see §7.3) | no equivalent |

After S4 the FQN is **tombstoned** and never reappears.

### 7.2 Plugin state machine (P1–P4)

The store computes the state on every re-sync from `builtAgainstKernel` + the changelog:

```
             deprecate            remove (→ R)              drop R
   Current ─────────▶ Aging ─────────▶ At risk ─────────▶ Broken

      ▲                 ▲                  ▲               ▲
      └─────────────────┴──────────────────┴───────────────┘
      (plugin updated — returns to Current from any state)
```

Degradation (to the right) is driven by the **kernel**; recovery (up) by a **plugin update**. The plugin's state is the worst state among the symbols it uses.

### 7.3 Retention policy (removal from `R`)

`R` exists only as a safety net against violations (removal without `@Deprecated` in a user module), so it is small. A symbol is removed from `R` when **both**: (a) **≥ 90 days** have passed since removal, **and** (b) live plugins using it (per re-sync) are **< 0.01%**. `F(module, H, G)` is kept for the supported window of app versions.

### 7.4 Update mandatoriness

At re-sync the store attaches a **severity** to the update:

| State / case | Severity | Store action |
| --- | --- | --- |
| **Current** | — | optional updates |
| **Aging** (`@Deprecated`) | recommended | warning + LLM migration |
| **At risk** (symbol removed without migration, lives in `R`) | mandatory | notification, but we serve the bundle (symbol still in `R`) |
| **Broken** (`R` removed) | mandatory (hard) | refusal: mandatory update |
| **Critical** (security/critical bug) | critical | **immediate hard refusal** of the old version, forced via re-sync, bypassing grace |

Soft mandatory: while the symbol is in `R`, the client works and receives a notification; hard refusal only after removal from `R` or on critical.

## 8. Delivery

### 8.1 Artifacts (all pre-built, static store)

- **`F(module, H, G)`** — the module's symbols added or changed after `H`, in current versions,
  built for toolchain generation `G`. Built for every supported host, **once per distinct native
  state** of the module: hosts whose native code of the module is the same share one bundle.
- **`R(module, G)`** — removed symbols (re-provide); one bundle per release that removed symbols,
  all of them served.
- **plugin bundle** — the code of a specific plugin.

Every bundle is **content-addressed** — stored under the hash of its content, immutable, so
caches and CDNs never serve a stale one — with a gzip copy made at release time and, when the
release has a signing key, an Ed25519 signature of the hash. `polyfills.json` says which bundle
serves which `(module, H, G)` and which `R` bundles a module has; it is written at the release's
commit point, after every bundle it names is in the store. Apps carry the trusted public keys
and check the hash and the signature of every downloaded bundle before staging it.

### 8.2 Reconciliation protocol (re-sync)

The client keeps `(id → version)` of loaded plugins and reconciles: **on screen open** and **periodically** (e.g. once a minute) it sends the store `(id, version, builtAgainstKernel, hostVersion, hostGeneration)`. For each `id` the store resolves the **newest version with `minHost ≤ H` and
`builtAgainstToolchain == G` whose polyfills it has**: a version needs `F(module, H, G)` for
each module it uses that changed after `H` up to the kernel the version was built against, and
that `F` must be at least that recent (`polyfills.json`). A version whose polyfill is missing —
not built for this host or generation — is skipped, so a host never gets a plugin it cannot run. Then:

- no such version → the plugin is **unavailable** on this host;
- the newest compatible is newer than the current → the store replies "here's the right version + hash + polyfills + severity";
- the newest compatible equals the current → "current".

The client downloads the new, cleans up the old. Critical updates are forced right here, immediately.

This is also the telemetry of "live plugins" for §7.3. Invariant: **any client at any moment is in one of {current, aging, at risk, broken}, and re-sync always drives it toward current** — so "hasn't been in for a long time" is not a special case, just the tail of the same state machine.

### 8.3 delta bundle (optimization)

Without the optimization the polyfill bundle is monolithic and the client downloads it whole. A **delta bundle** cuts the polyfill into content-addressed pieces; the client downloads only what's missing. Applicable only to modules without shared state (§6.5).

### 8.4 Regression fix (without "rollback")

"Rollback" = releasing a new version (forward) whose code matches the old one. `version` grows monotonically; "current" never moves backward; clients update via re-sync. No downgrade and no special channel.

### 8.5 Update points

Nothing downloaded at re-sync changes running code by itself. New plugin versions and polyfills
are **staged** and applied together at an **update point**:

- **cold start** — the newest bundles are evaluated before the first frame;
- **`RdmaBridge.applyUpdates()`** — called by the host where the user does not watch plugin
  content (between screens, back from the background).

At an update point every staged bundle is evaluated as one step on the plugin thread, so nothing
runs against a half-applied update. Every place showing a component of an updated plugin is
recreated with the new version (its `remember` state starts over), so two versions of one plugin
never run side by side — they would otherwise each keep their own copy of the plugin's state
(§6.6). Updated kernel functions switch for every caller at the same moment, including calls from
components already on screen — a deliberate simplification instead of a state machine. The host
stages polyfills before the plugins that need them.

## 9. Releases

- **`release-dynamic`** — a dynamic release (no app update). It determines what changed by hash diff:
  - only the plugin → a new plugin bundle (`version`++), the kernel version is **not touched**;
  - only the kernel → bump the kernel version + changelog + polyfill materialization;
  - plugin + kernel → both atomically (one bump for the kernel part).
- **`release-static`** — bake the accumulated changes into the native: bump the counter + move `H` (a heavy, rare operation; app rebuild).

**Release order** (protection against a half-done state): **tests → prepare → build → contract
tests → commit**. *Prepare* runs every check and writes the polyfill sources and the release
plan to a work directory; *build* compiles each polyfill for every live generation (a generation
whose toolchain is not available is `blocked` and keeps its previous polyfills); *contract
tests* (§10) run the kernel's tests against the native code and each polyfill; *commit*
publishes the bundles, then writes `polyfills.json` and the changelog. The changelog write is
the commit point; any error before it writes nothing.

A **static release** of a module also brings its changes to the older supported hosts as
polyfills, per native-state group, where they can be delivered; a group that cannot receive
them gets no new `F`, and §8.2 keeps serving it the plugin versions that do not need it.

**`release-dynamic` is all or nothing.** Every check runs first — signatures (§5.6),
hotfixability (§6.4), emulatability (§5.2), structure changes (§6.7), state splits (§6.6),
removals (§7.1) — and the problems are reported together: what cannot ship and why, which other
changes do not ship because of them (a release never ships part of a change), and the options (fix
it, write a manual polyfill, or a static release). `--dry-run` runs the checks and writes nothing.

**Who writes the changelog**: only CI, at merge time. The kernel version is bumped **on a kernel change**; one release = one bump = a set of changes; tests are a hard gate.

**Hotfix and `minHost`.** A hotfix (bugfix, API unchanged) **must not bump the `floor`** of any
symbol: if a release is declared a hotfix but a floor grew — that's an error. A plugin's `minHost`
is never raised by a release at all (§5.3).

**Coverage warning.** `release-dynamic` computes the coverage % (from the distribution of host versions in re-sync telemetry) and shows an infographic before publishing: "this release will reach X% of clients; `minHost` rose to N because of symbol Y (non-emulatable, introduced at N)". The developer consciously confirms.

## 10. Tests

Tests for `@RDMA` functionality are a **hard gate** on `release-dynamic`/`release-static`. They are the **symbol's contract** and verify that the **public API hasn't changed** (signatures and semantics at the boundary).

- **Contract across all implementations**: the same test suite must be passed by the native, the
  auto-polyfill and the manual polyfill. The kernel's `commonTest` runs as the app's instrumented
  tests on an emulator: once against the native code, then once per prepared `F` with it loaded
  (kernel calls of the polyfilled functions run in JS through their slots). A failure rejects
  the release. Classes added after a host are checked natively only.
- **Differential runs**: a matrix `(native | polyfill) × (old kernel | new) × (plugin versions)` to verify no regressions when versions update and new polyfills appear.
- **Screenshot tests** for UI — behavior across combinations is compared visually.

## 11. Tooling / User guide

Separate documents: a guide for users (including LLM), examples, performance, which scripts to run, how to track versions; IDE highlighting of "what's available in the range", including highlighting of symbols with `floor > 0` ("using this symbol raises `minHost` to N").

## 12. Operations (outside versioning)

The questions below concern the runtime/infrastructure and are only pointers here;
the user-facing release commands and store endpoints are in
[hotfix_and_releases.md](../guide/hotfix_and_releases.md):

- **Offline / store outage**: a fallback (basic versions baked into the cache), behavior without network.
- **re-sync scale**: backoff/exponential delay against a "once a minute" storm.
- **Supply-chain / bundle signing**: trust in the store and authors.
- **Plugin state on update**: preserving `remember`/state during v5→v6 on a live screen.

---

## From the plugin developer's point of view

All the complexity above is the internals of the system. The developer doesn't see it.

- Writes ordinary Kotlin, using `@RDMA` symbols of the kernel.
- Writes tests for their own symbols.
- Runs `release-dynamic`.

Everything else — versions, polyfills, compatibility, migration via `@Deprecated` — the system does itself. From the developer we only need: remove symbols via `@Deprecated`, and ask us (with justification) for non-emulatable functionality.
