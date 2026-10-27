# Exception Propagation Across Runtimes

**Applies to:** Android (JNI) and iOS (C ABI)

## 1. Purpose

A plugin runs in Hermes (JS) and calls into the host (Kotlin), and the host calls back
into the plugin. Exceptions thrown on one side must cross the runtime boundary and be
observable on the other side, in both directions, without losing the stack trace or the
type of the original exception.

## 2. Core model

An exception is never passed across as a live object. At the boundary it is **reborn**:
a new exception of the same shape is thrown in the other runtime, carrying the original
as its `cause`. The stack therefore reads end-to-end through the standard `cause` chain:

```
IllegalStateException: ...              (host)
    at host.…
Caused by: TypeError: ...                (plugin)
    at plugin.…
    Caused by: IllegalArgumentException   (host)
        at host.…
```

## 3. Transport format

What crosses the boundary is a serializable description — strings only:

| Field | Meaning |
|---|---|
| `originalExceptionType` | fully-qualified name of the original exception class (e.g. `java.lang.IllegalArgumentException`, `kotlin.IllegalStateException`, `TypeError`) |
| `message` | the exception message |
| `stack` | the stack trace of the current runtime |
| `cause` | the nested description from the previous runtime (recursive) |

`suppressed` exceptions and arbitrary user data on the JS object are **not** carried.

## 4. Kotlin-side type

The host throws one type:

```
class RdmaException(
    val originalExceptionType: String,
    message: String,
    val remoteStack: String,
    cause: RdmaException?
) : RuntimeException(message, cause)
```

`remoteStack` is the serialized stack of the other runtime; `cause` is the previous
runtime's `RdmaException`. Together they represent the recursive `cause` field of §3,
one level at a time. The original class cannot be recreated in the other runtime, so its
name is preserved as `originalExceptionType` for diagnostics. Typed `catch` across the
boundary is not possible.

## 5. Directions

### Host → plugin (JS throws)

The host invokes a plugin function (content, scope block, effect, callback). The JS
function throws; the bridge catches it and throws `RdmaException` on the host side, with
the JS trace as `remoteStack`/`cause`.

### Plugin → host (native throws)

The plugin calls a host function (a widget, `@RDMA` method, state access). The host
function throws a Kotlin exception; the bridge catches it (JNI `ExceptionCheck` /
Kotlin `try`-wrap on iOS), serializes it, and throws a JS `Error` whose `__rdmaCause`
carries the host exception and whose `stack` prints the whole chain.

Either direction can nest: an exception may cross the boundary multiple times
(host → plugin → host) and the `cause` chain grows each time.

### 5.1 Call channels

| Channel | How it is invoked | Exception delivery |
|---|---|---|
| content / scope block / effect body / dispose | synchronous host→plugin call | thrown back to the host caller (`RdmaException`) |
| callback / lambda (event, side effect) | fire-and-forget, no live caller frame | uncaught-exception path (§6) |
| bundle eval | asynchronous host load | stored, then thrown from `nativeIsReady()` (§7) |

> **Compose constraint on `content` (and `scope block`).** A `content` throw is a
> synchronous host→plugin call, but `nativeInvokeContent` drives a nested Compose
> composition. If the plugin's `@Composable` body throws, its restart-group is left
> open and Compose crashes with `ComposeRuntimeError: "Start/end imbalance"` — it is
> **not** catchable by a host-side `try/catch` around `nativeInvokeContent` (the
> `RdmaException` is caught, but the Composer is already corrupted). Consequently the
> S1a scenario is verified as a crash-test: the app dies with
> `FATAL EXCEPTION: RdmaException`, and the crash's `Caused by:` chain proves the
> double crossing (host → JS → host). A real plugin must not throw through `content`;
> errors are propagated through UI state instead.

## 6. Uncaught exceptions (one global handler + emergency log)

Fire-and-forget callbacks have no host frame to return to, so an uncaught exception is
delivered to a single host handler:

1. **Global handler** — the host integrator sets one handler for all plugins, receiving
   the `pluginId` and the `RdmaException`.
2. **Emergency fallback** — if no handler is registered, the full chained stack is logged
   (`LOGW`); do **not** crash. There is no built-in default handler and no `lastError`
   store: the host must install a global handler to observe these exceptions.

The host owns policy (level 1), the framework guarantees nothing is ever silently lost
(level 2 emergency log). To attribute a failure to a plugin, `registerBlock`/`registerContent`
record a `pluginId` together with the block.

There is no per-plugin handler: the plugin cannot register its own uncaught-exception
handler. All uncaught exceptions from every plugin are delivered to the single global
handler (with the offending `pluginId`), or logged if none is installed.

## 7. Bundle loading

Loading a bundle is asynchronous (`eval` is enqueued on the Hermes thread). A compile or
load error is therefore **stored** by the runtime and **thrown from `nativeIsReady()`**
as a `BundleLoadException`, once, through the same propagation mechanism. A host-side
`BundleLoader` wrapper performs the load and surfaces the exception synchronously to its
caller.

## 8. Garbage-collection guarantees

Live exception objects are never held across the boundary; only the strings of §3 cross:

- Hermes `jsi::Value`/`Object`/`Function` are GC roots bound to one runtime and are not
  copyable — they are serialized in place at the `catch` site.
- JNI `Throwable` is a local reference — read into strings, then released.
- Kotlin/Native exceptions are caught and serialized on the Kotlin side (an exception
  must never propagate through a C frame).

The exception description (`RdmaExceptionInfo`) is a plain C++ struct of `std::string`s.

## 9. Platform notes

- **Android (JNI):** host exceptions arrive as pending JNI exceptions (checked via
  `ExceptionCheck`, thrown via `ThrowNew`); JS exceptions arrive as `jsi::JSError`.
- **iOS (C ABI):** exceptions cannot cross a C frame. Kotlin shim functions catch their
  own exceptions and record them (thread-local); C++ reads them and throws a JS `Error`.
  In the reverse direction the `rdma_nativeInvoke*` entry points return an error that
  Kotlin turns into `RdmaException`.

The intermediate C++ layer is transport only — it is not a runtime and never appears in
the stack.

## 10. Implementation status (work in progress)

- **Done (Android, verified on emulator):** S2 (native→JS), H2 (global handler),
  double crossing (host→JS→host cause chain via `fromJSError` reading `__rdmaCause`),
  A2 (BundleLoadException from `nativeIsReady`). Per-plugin handler removed; a single
  global handler + emergency `LOGW` remain.
- **S1a (sync rethrow from `content`)**: verified only as a crash-test — see §5.1 note.
- **iOS (verified on iPhone 17 simulator):** S2, H2 (global handler via
  `rdma_set_uncaught_handler` → `rdma_dispatchUncaught`), and A2 (corrupt `.hbc` →
  `BundleLoadException` from `nativeIsReady`) all work. The `content` throw
  (S1a/double crossing) also surfaces as an `RdmaException` crash on iOS, matching
  Android.
- **Codegen fixes applied along the way** (in `rdma-kernel-compiler-plugin`):
  `CAbiCppGenerator` proxies now include `RdmaComposeCAbi.h` (so `checkKotlinError` is
  declared); `CAbiKotlinGenerator` emits nullable `COpaquePointer?` return types for
  object/list/function returns (the `rdmaCaptureError` error default is `null`).
- **Other sync channels** (`scope block`, `effect body`, `dispose`) are the same code
  path as `content` (`rdmaCallJs` → `throwIfError` on Android / `storeCaughtError` on
  iOS), so they are structurally covered by the S1a content test rather than exercised
  by a dedicated e2e scenario.
