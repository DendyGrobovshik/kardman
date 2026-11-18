# Design: exception propagation across runtimes

How it works: [internals/exceptions.md](../internals/exceptions.md). Invariant:
[runtime_rework.md §2.8](runtime_rework.md#28-exception-invariant).

## Problem

The plugin calls the kernel and the kernel calls the plugin. An exception thrown on one side
must be observable on the other, in both directions, without losing the type or the stack.

## Decisions

- **Reborn, not passed.** An exception crosses as a description of strings (type name,
  message, stack, recursive cause) and a new exception is thrown on the other side with the
  original as its `cause`. Live exception objects cannot cross: JSI values are bound to one
  runtime, JNI throwables are local references, and a Kotlin/Native exception must never
  unwind through a C frame. The `cause` chain reads end to end in standard stack traces.
- **One kernel-side type, `RdmaException`.** The original class cannot be recreated in the
  other runtime, so its name is kept as `originalExceptionType`; typed `catch` across the
  boundary is given up for a uniform, lossless chain.
- **Fire-and-forget errors go to one global handler** set by the app, with the plugin id;
  without a handler the chain is logged and the app keeps running. A per-plugin handler was
  removed: policy belongs to the host, and a plugin must not be able to swallow its own
  failures.
- **Bundle load errors are stored and thrown once from `nativeIsReady()`**, since loading is
  asynchronous and has no caller frame.
- **A throw inside plugin composition is not recoverable.** Compose leaves the group open
  ("Start/end imbalance") and a host `try/catch` cannot repair the composer; the error is
  propagated as a crash with the full chain. Plugins must report errors through state.

## Rejected

Carrying `suppressed` exceptions and arbitrary fields of JS errors: not representable on
both sides without a schema, and not needed for diagnostics.
