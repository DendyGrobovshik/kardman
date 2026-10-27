/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once
#include <functional>
#include <string>
#include <atomic>
#include <cstdint>
#include <memory>

#include "RdmaPlatform.h"
#include "RdmaException.h"

namespace facebook {
namespace jsi {
class Buffer;
}
namespace rdma {

// Result of a UI-thread compose op, conveyed back to the JS thread. Because
// `jsi::Value`/`jsi::Object` are bound to the Hermes runtime (owned by the JS
// thread), the UI thread cannot produce them directly. Instead it returns a
// serializable descriptor that the JS thread turns into a `jsi::Value`.
enum class RdmaResultKind {
    Undefined,
    Null,
    Bool,
    Number,
    String,
    StateRef,   // host handle to a MutableState (ownership transferred)
    ScopeRef,   // host handle to a ScopeUpdateScope (ownership transferred)
    Empty,      // Compose `Companion.Empty`
    JsValueId,  // id into g_jsValues
    Error,      // the UI op threw a host exception (carried in `error`)
};

struct RdmaResult {
    RdmaResultKind kind = RdmaResultKind::Undefined;
    bool b = false;
    double num = 0.0;
    std::string str;
    void* ref = nullptr; // host handle (jobject on Android, StableRef on Apple); ownership transferred to the JS thread
    int64_t id = 0;
    RdmaExceptionInfo error; // for Error kind

    static RdmaResult undefined() { return RdmaResult{}; }
    static RdmaResult null() { RdmaResult r; r.kind = RdmaResultKind::Null; return r; }
    static RdmaResult boolean(bool v) { RdmaResult r; r.kind = RdmaResultKind::Bool; r.b = v; return r; }
    static RdmaResult number(double v) { RdmaResult r; r.kind = RdmaResultKind::Number; r.num = v; return r; }
    static RdmaResult string(std::string v) { RdmaResult r; r.kind = RdmaResultKind::String; r.str = std::move(v); return r; }
    static RdmaResult stateRef(void* v) { RdmaResult r; r.kind = RdmaResultKind::StateRef; r.ref = v; return r; }
    static RdmaResult scopeRef(void* v) { RdmaResult r; r.kind = RdmaResultKind::ScopeRef; r.ref = v; return r; }
    static RdmaResult empty() { RdmaResult r; r.kind = RdmaResultKind::Empty; return r; }
    static RdmaResult jsValueId(int64_t v) { RdmaResult r; r.kind = RdmaResultKind::JsValueId; r.id = v; return r; }
    static RdmaResult fromError(RdmaExceptionInfo e) { RdmaResult r; r.kind = RdmaResultKind::Error; r.error = std::move(e); return r; }};

// A unit of work executed on the UI thread (a compose op). Returns an RdmaResult.
using UiTask = std::function<RdmaResult()>;

// A unit of work executed on the JS thread (content/scope/callback/lambda/eval).
using JsTask = std::function<void()>;

// Blocking (reentrant) call into the UI thread, made from the JS thread. While
// waiting, the caller services the UI->JS queue (reentrant scope blocks).
RdmaResult rdmaCallUi(UiTask task);

// Blocking (reentrant) call into the JS thread, made from the UI thread. While
// waiting, the caller services the JS->UI queue (reentrant compose ops). Returns
// a non-empty description if the JS task threw (see docs/design/exception_propagation.md).
RdmaExceptionInfo rdmaCallJs(JsTask task);

// Non-blocking post of a low-priority (async) task to the JS thread. Used for
// callbacks and service-lambda invocations that must not stall composition.
void rdmaPostJs(JsTask task);

// Non-blocking post of a high-priority (compose/init) task to the JS thread.
void rdmaPostJsHigh(JsTask task);

// Non-blocking self-post of a low-priority (async) task, made from the JS
// thread itself. Used to schedule coroutine bodies (Dispatchers.Main) after the
// current compose/init work completes, so they never stall the blocking
// UI->JS rendezvous.
void rdmaPostJsSelf(JsTask task);

// Start the Hermes thread (async). Must be called on the UI thread after all
// platform caches are initialized. The thread creates the runtime + bridge and
// then services the queue.
void rdmaStart(HostContext ctx);

// Async enqueue of a JS asset eval on the Hermes thread.
void rdmaEvalAsset(const std::string& code);

// Async enqueue of a bytecode buffer eval on the Hermes thread. The buffer (e.g. an mmap of a
// file) is retained by the queued task and then by Hermes during lazy bytecode loading.
void rdmaEvalBuffer(std::shared_ptr<const facebook::jsi::Buffer> buffer, const std::string& sourceURL);

// Async enqueue of a file-backed bytecode eval: mmaps the file (zero-copy) and evals it.
// No-op with a log line if the file cannot be mapped.
void rdmaEvalFile(const std::string& path);

// True once the runtime is ready and all enqueued evals have finished.
bool rdmaIsReady();

// Uncaught-exception delivery (see docs/design/exception_propagation.md §6).
// `handler` receives the serialized exception and the owning pluginId; while no
// handler is set the exception is only logged (emergency fallback).
using UncaughtExceptionHandler = void (*)(const RdmaExceptionInfo& info, const std::string& pluginId);
void rdmaSetUncaughtExceptionHandler(UncaughtExceptionHandler handler);
void rdmaUncaughtException(const RdmaExceptionInfo& info, const std::string& pluginId);

// Thread ids captured for debug asserts (see RdmaCompose.cpp / RdmaRuntime.cpp).
extern std::atomic<int64_t> g_uiTid;
extern std::atomic<int64_t> g_jsTid;

} // namespace rdma
} // namespace facebook
