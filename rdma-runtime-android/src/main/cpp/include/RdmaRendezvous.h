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
#include <jni.h>
#include <functional>
#include <string>
#include <atomic>
#include <cstdint>
#include <sys/types.h>

namespace facebook {
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
    StateRef,   // jobject global ref to a MutableState (ownership transferred)
    ScopeRef,   // jobject global ref to a ScopeUpdateScope (ownership transferred)
    Empty,      // Compose `Companion.Empty`
    JsValueId,  // id into g_jsValues
};

struct RdmaResult {
    RdmaResultKind kind = RdmaResultKind::Undefined;
    bool b = false;
    double num = 0.0;
    std::string str;
    jobject ref = nullptr; // global ref; ownership transferred to the JS thread
    int64_t id = 0;

    static RdmaResult undefined() { return RdmaResult{}; }
    static RdmaResult null() { RdmaResult r; r.kind = RdmaResultKind::Null; return r; }
    static RdmaResult boolean(bool v) { RdmaResult r; r.kind = RdmaResultKind::Bool; r.b = v; return r; }
    static RdmaResult number(double v) { RdmaResult r; r.kind = RdmaResultKind::Number; r.num = v; return r; }
    static RdmaResult string(std::string v) { RdmaResult r; r.kind = RdmaResultKind::String; r.str = std::move(v); return r; }
    static RdmaResult stateRef(jobject v) { RdmaResult r; r.kind = RdmaResultKind::StateRef; r.ref = v; return r; }
    static RdmaResult scopeRef(jobject v) { RdmaResult r; r.kind = RdmaResultKind::ScopeRef; r.ref = v; return r; }
    static RdmaResult empty() { RdmaResult r; r.kind = RdmaResultKind::Empty; return r; }
    static RdmaResult jsValueId(int64_t v) { RdmaResult r; r.kind = RdmaResultKind::JsValueId; r.id = v; return r; }
};

// A unit of work executed on the UI thread (a compose op). Returns an RdmaResult.
using UiTask = std::function<RdmaResult()>;

// A unit of work executed on the JS thread (content/scope/callback/lambda/eval).
using JsTask = std::function<void()>;

// Blocking (reentrant) call into the UI thread, made from the JS thread. While
// waiting, the caller services the UI->JS queue (reentrant scope blocks).
RdmaResult rdmaCallUi(UiTask task);

// Blocking (reentrant) call into the JS thread, made from the UI thread. While
// waiting, the caller services the JS->UI queue (reentrant compose ops).
void rdmaCallJs(JsTask task);

// Non-blocking post of a low-priority (async) task to the JS thread. Used for
// callbacks and service-lambda invocations that must not stall composition.
void rdmaPostJs(JsTask task);

// Non-blocking post of a high-priority (compose/init) task to the JS thread.
void rdmaPostJsHigh(JsTask task);

// Start the Hermes thread (async). Must be called on the UI thread after all
// JNI caches are initialized. The thread creates the runtime + bridge and then
// services the queue.
void rdmaStart(JavaVM* jvm);

// Async enqueue of a JS asset eval on the Hermes thread.
void rdmaEvalAsset(const std::string& code);

// True once the runtime is ready and all enqueued evals have finished.
bool rdmaIsReady();

// Thread ids captured for debug asserts (see RdmaCompose.cpp / RdmaRuntime.cpp).
extern std::atomic<pid_t> g_uiTid;
extern std::atomic<pid_t> g_jsTid;

} // namespace rdma
} // namespace facebook
