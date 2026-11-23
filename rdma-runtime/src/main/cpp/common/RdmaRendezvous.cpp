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
#include "RdmaRendezvous.h"
#include "RdmaBlocks.h"

#include <chrono>
#include <deque>
#include <memory>
#include <mutex>
#include <condition_variable>
#include <queue>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <pthread.h>
#include <unistd.h>

#ifndef NDEBUG
#include <cassert>
#endif

#include "RdmaRuntime.h"
#include "RdmaLog.h"
#include "RdmaPlatform.h"

#define LOG_TAG "RdmaRendezvous"

namespace facebook {
namespace rdma {

std::atomic<int64_t> g_uiTid{0};
std::atomic<int64_t> g_jsTid{0};

namespace {

struct UiRequest {
    UiTask task;
    RdmaResult result;
    bool done = false;
};

struct JsRequest {
    JsTask task;
    bool done = false;
    bool blocking = false;
    bool hasError = false;
    RdmaExceptionInfo error;
};

// Single mutex + condition variable guarding both queues. Both threads use the
// same mutex; while waiting for a response each thread reentrantly services the
// opposite queue, which is what makes nested UI->JS->UI->JS composition work.
std::mutex g_mutex;
std::condition_variable g_cv;

// Experimental spin-wait (docs/guide/threads_and_performance.md#spin-wait): before sleeping
// on `g_cv`, a waiting thread spins up to `g_spinUs` microseconds for a change, so a reply
// that comes quickly costs no sleep and no wake-up. 0 = off (the default): the waits and
// notifications behave exactly as without it.
std::atomic<int> g_spinUs{0};
std::atomic<uint64_t> g_seq{0};    // bumped under g_mutex by every change a waiter can wait for
std::atomic<int> g_sleepers{0};    // threads blocked on g_cv (changed under g_mutex)

inline void cpuRelax() {
#if defined(__aarch64__) || defined(__arm__)
    asm volatile("yield" ::: "memory");
#elif defined(__x86_64__) || defined(__i386__)
    __builtin_ia32_pause();
#endif
}

// Called with g_mutex held after changing a queue or a request: records the change for
// spinning waiters and wakes the sleeping ones. With spin-wait on, the wake (a futex call)
// is skipped when nobody sleeps.
void notifyLocked() {
    g_seq.fetch_add(1, std::memory_order_release);
    if (g_spinUs.load(std::memory_order_relaxed) == 0 || g_sleepers.load(std::memory_order_relaxed) > 0) {
        g_cv.notify_all();
    }
}

// With spin-wait on: releases `lk`, spins until something changes, `limit` or the spin
// budget, and re-locks. True when something changed (the caller re-checks its condition).
// Every change is made and counted under g_mutex, so an unchanged counter under the re-taken
// lock means it is safe to sleep: a later change sees the sleeper and wakes it.
bool spinForChange(std::unique_lock<std::mutex>& lk,
                   std::chrono::steady_clock::time_point limit = std::chrono::steady_clock::time_point::max()) {
    const int us = g_spinUs.load(std::memory_order_relaxed);
    if (us <= 0) return false;
    const uint64_t seen = g_seq.load(std::memory_order_acquire);
    const auto end = std::min(std::chrono::steady_clock::now() + std::chrono::microseconds(us), limit);
    lk.unlock();
    bool changed = false;
    for (unsigned i = 0;; i++) {
        if (g_seq.load(std::memory_order_acquire) != seen) {
            changed = true;
            break;
        }
        cpuRelax();
        if ((i & 63) == 63 && std::chrono::steady_clock::now() >= end) break;
    }
    lk.lock();
    return changed || g_seq.load(std::memory_order_relaxed) != seen;
}

// Waits on g_cv for a change (lk held), spinning first when spin-wait is on.
void waitLocked(std::unique_lock<std::mutex>& lk) {
    if (spinForChange(lk)) return;
    g_sleepers.fetch_add(1, std::memory_order_relaxed);
    g_cv.wait(lk);
    g_sleepers.fetch_sub(1, std::memory_order_relaxed);
}

// JS -> UI (compose ops). No priority needed: every UI-bound op is a synchronous
// compose call that the UI thread must run in order.
std::deque<std::shared_ptr<UiRequest>> g_jsToUi;

// UI -> JS. Split into high (compose/init: RunContent, RunScopeBlock, eval) and
// low (async: callbacks/lambdas) so that async parsing never stalls composition.
std::deque<std::shared_ptr<JsRequest>> g_uiToJsHigh;
std::deque<std::shared_ptr<JsRequest>> g_uiToJsLow;

// Timer state (Hermes-thread only). setTimeout/clearTimeout are JS host functions
// and therefore run on the Hermes thread, so no locking is needed: a timer can only
// be inserted while the event loop is executing, and the loop re-reads the nearest
// deadline on the next iteration (no lost-wakeup race).
struct TimerEntry {
    int64_t handle;
    std::chrono::steady_clock::time_point deadline;
    std::shared_ptr<jsi::Function> fn;
};
// Min-heap by deadline (earliest on top); ties broken by handle for determinism.
struct TimerEntryLater {
    bool operator()(const TimerEntry& a, const TimerEntry& b) const {
        if (a.deadline != b.deadline) return a.deadline > b.deadline;
        return a.handle > b.handle;
    }
};
std::priority_queue<TimerEntry, std::vector<TimerEntry>, TimerEntryLater> g_timers;
// Lazily-cancelled handles: clearTimeout marks them; they are skipped (and erased)
// when their deadline is reached. Cancelled entries linger until their deadline,
// which is negligible at delay/withTimeout scale.
std::unordered_set<int64_t> g_cancelledTimers;
int64_t g_nextTimerHandle = 1;

std::atomic<bool> g_runtimeReady{false};
std::atomic<int> g_evalPending{0};
std::atomic<bool> g_stopped{false};

// Count of active blocking rdmaCallJs calls on the UI thread (rdmaCallJs can be
// nested: a widget content lambda composes nativeInvokeScopeBlock inside the
// outer content composition). rdmaCallUi is only valid while this is > 0: a
// blocking JS->UI call made while the UI thread is not at the boundary (e.g.
// from a deferred coroutine) would never be serviced and would deadlock forever.
std::atomic<int> g_uiInRendezvous{0};

HostContext g_host = nullptr;
std::thread g_jsThread;

// Uncaught-exception delivery (docs/design/exception_propagation.md §6).
std::atomic<UncaughtExceptionHandler> g_uncaughtHandler{nullptr};

bool popJsRequestLocked(std::shared_ptr<JsRequest>& out) {
    if (!g_uiToJsHigh.empty()) {
        out = g_uiToJsHigh.front();
        g_uiToJsHigh.pop_front();
        return true;
    }
    if (!g_uiToJsLow.empty()) {
        out = g_uiToJsLow.front();
        g_uiToJsLow.pop_front();
        return true;
    }
    return false;
}

// Pops only high-priority (compose/init) requests. Used by the reentrant pump
// inside rdmaCallUi: while the JS thread is blocked waiting for the UI thread, it
// must still service nested scope blocks (high) but must NOT run async callbacks
// (low) — running them mid-composition would write Compose state into the active
// composition snapshot (or a stale global snapshot) and lose the recomposition.
bool popJsRequestHighLocked(std::shared_ptr<JsRequest>& out) {
    if (!g_uiToJsHigh.empty()) {
        out = g_uiToJsHigh.front();
        g_uiToJsHigh.pop_front();
        return true;
    }
    return false;
}

static void runAndCapture(JsRequest& req) {
    try {
        req.task();
    } catch (const jsi::JSError& e) {
        jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            req.error = fromJSError(*rt, e);
        } else {
            req.error.originalExceptionType = "Error";
            req.error.message = e.what();
        }
        req.hasError = true;
    } catch (const std::exception& e) {
        req.error.originalExceptionType = "std::exception";
        req.error.message = e.what();
        req.hasError = true;
    } catch (...) {
        req.error.originalExceptionType = "unknown";
        req.error.message = "unknown C++ exception";
        req.hasError = true;
    }
}

void executeJsTask(JsRequest& req) {
#ifndef NDEBUG
    assert(g_jsTid.load(std::memory_order_relaxed) != 0 &&
           rdmaGetTid() == g_jsTid.load(std::memory_order_relaxed));
#endif
    // Bound local refs created by JSI host functions: they are not JNI call
    // boundaries, so on the long-lived Hermes thread local refs would otherwise
    // accumulate until the local reference table overflows.
#if defined(__ANDROID__)
    JNIEnv* env = nullptr;
    JavaVM* jvm = static_cast<JavaVM*>(g_host);
    const bool hasFrame = jvm && jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK &&
                          env->PushLocalFrame(512) == 0;
    runAndCapture(req);
    if (hasFrame && env) env->PopLocalFrame(nullptr);
#else
    runAndCapture(req);
#endif
}

void executeUiTask(UiRequest& req) {
#ifndef NDEBUG
    assert(g_uiTid.load(std::memory_order_relaxed) != 0 &&
           rdmaGetTid() == g_uiTid.load(std::memory_order_relaxed));
#endif
    req.result = req.task();
    rdmaPlatformAfterUiTask();
}

} // namespace

RdmaResult rdmaCallUi(UiTask task) {
    const bool inRendezvous = g_uiInRendezvous.load(std::memory_order_acquire) > 0;
#ifndef NDEBUG
    assert(inRendezvous);
#endif
    if (!inRendezvous) {
        LOGW("rdmaCallUi called while the UI thread is not in a rendezvous; "
             "returning undefined to avoid a deadlock");
        return RdmaResult::undefined();
    }

    auto req = std::make_shared<UiRequest>();
    req->task = std::move(task);

    std::unique_lock<std::mutex> lk(g_mutex);
    g_jsToUi.push_back(req);
    notifyLocked();
    while (!req->done) {
        std::shared_ptr<JsRequest> jsReq;
        if (popJsRequestHighLocked(jsReq)) {
            lk.unlock();
            executeJsTask(*jsReq);
            bool reentrantError = jsReq->hasError;
            RdmaExceptionInfo errInfo = std::move(jsReq->error);
            lk.lock();
            jsReq->done = true;
            notifyLocked();
            if (reentrantError) {
                lk.unlock();
                jsi::Runtime* rt = getRdmaRuntime();
                if (rt) throwJsException(*rt, errInfo);
                return RdmaResult::undefined();
            }
        } else {
            waitLocked(lk);
        }
    }
    return std::move(req->result);
}

RdmaExceptionInfo rdmaCallJs(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    req->blocking = true;

    // Increment before enqueueing so a JS task cannot observe the counter as
    // zero while the UI thread is about to service it (closes the push/notify
    // race). rdmaCallJs is reentrant (nested scope blocks), hence a counter.
    g_uiInRendezvous.fetch_add(1, std::memory_order_release);
    std::unique_lock<std::mutex> lk(g_mutex);
    g_uiToJsHigh.push_back(req);
    notifyLocked();
    while (!req->done) {
        if (!g_jsToUi.empty()) {
            auto uiReq = g_jsToUi.front();
            g_jsToUi.pop_front();
            lk.unlock();
            executeUiTask(*uiReq);
            lk.lock();
            uiReq->done = true;
            notifyLocked();
        } else {
            waitLocked(lk);
        }
    }
    g_uiInRendezvous.fetch_sub(1, std::memory_order_release);
    return std::move(req->error);
}

namespace {
// Shared by a background caller and its queued task; whoever finishes last frees it.
struct BlockingJsCall {
    std::mutex m;
    std::condition_variable cv;
    bool started = false;
    bool done = false;
    bool abandoned = false;
};
} // namespace

bool rdmaOnUiThread() { return rdmaGetTid() == g_uiTid.load(std::memory_order_acquire); }

RdmaExceptionInfo rdmaCallJsBlocking(JsTask body, JsTask discard, const std::string& what) {
    const int64_t tid = rdmaGetTid();
    if (tid == g_jsTid.load(std::memory_order_acquire)) {
        body();
        return {};
    }
    if (tid == g_uiTid.load(std::memory_order_acquire)) {
        return rdmaCallJs(std::move(body));
    }

    auto call = std::make_shared<BlockingJsCall>();
    rdmaPostJs([call, body = std::move(body), discard = std::move(discard)] {
        {
            std::lock_guard<std::mutex> lk(call->m);
            if (call->abandoned) {
                discard();
                return;
            }
            call->started = true;
        }
        body();
        std::lock_guard<std::mutex> lk(call->m);
        call->done = true;
        if (call->abandoned) discard();
        call->cv.notify_all();
    });

    std::unique_lock<std::mutex> lk(call->m);
    const auto isDone = [&] { return call->done; };
    if (!call->cv.wait_for(lk, std::chrono::milliseconds(kRdmaJsWaitWarnMs), isDone)) {
        LOGW("%s: thread %lld has waited %lld ms for the Hermes thread (%s)", what.c_str(),
             (long long)tid, (long long)kRdmaJsWaitWarnMs,
             call->started ? "running; likely blocked on something this thread holds" : "queued behind other work");
        if (!call->cv.wait_for(lk, std::chrono::milliseconds(kRdmaJsWaitTimeoutMs - kRdmaJsWaitWarnMs), isDone)) {
            const bool started = call->started;
            call->abandoned = true;
            RdmaExceptionInfo info;
            info.originalExceptionType = "RdmaTimeoutException";
            info.message = what + ": thread " + std::to_string(tid) + " timed out after " +
                std::to_string(kRdmaJsWaitTimeoutMs) + " ms waiting for the Hermes thread (" +
                (started ? "the call is running on the Hermes thread — likely waiting for something this thread "
                           "holds, e.g. a lock held across the call"
                         : "the call is still queued: the Hermes thread is busy") +
                "; see docs/guide/threads_and_performance.md)";
            return info;
        }
    }
    return {};
}

void rdmaPostJs(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsLow.push_back(req);
        notifyLocked();
    }
}

void rdmaPostJsHigh(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsHigh.push_back(req);
        notifyLocked();
    }
}

void rdmaPostJsSelf(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsLow.push_back(req);
        notifyLocked();
    }
}

int64_t rdmaScheduleTimer(int64_t ms, std::shared_ptr<jsi::Function> fn) {
    const int64_t handle = g_nextTimerHandle++;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    g_timers.push(TimerEntry{handle, deadline, std::move(fn)});
    return handle;
}

void rdmaCancelTimer(int64_t handle) {
    g_cancelledTimers.insert(handle);
}

std::optional<std::chrono::steady_clock::time_point> rdmaNextTimerDeadline() {
    // Drop cancelled entries sitting on top so they don't force a pointless wakeup.
    while (!g_timers.empty() && g_cancelledTimers.count(g_timers.top().handle)) {
        g_cancelledTimers.erase(g_timers.top().handle);
        g_timers.pop();
    }
    if (g_timers.empty()) return std::nullopt;
    return g_timers.top().deadline;
}

std::vector<std::shared_ptr<jsi::Function>> rdmaPopDueTimers() {
    std::vector<std::shared_ptr<jsi::Function>> due;
    const auto now = std::chrono::steady_clock::now();
    while (!g_timers.empty() && g_timers.top().deadline <= now) {
        TimerEntry entry = g_timers.top();
        g_timers.pop();
        if (g_cancelledTimers.erase(entry.handle)) {
            continue; // cancelled: drop
        }
        due.push_back(std::move(entry.fn));
    }
    return due;
}

void rdmaEvalAsset(const std::string& code) {
    g_evalPending.fetch_add(1, std::memory_order_relaxed);
    rdmaPostJsHigh([code] {
        evalJavaScript(code);
        g_evalPending.fetch_sub(1, std::memory_order_relaxed);
    });
}

void rdmaEvalBuffer(std::shared_ptr<const facebook::jsi::Buffer> buffer, const std::string& sourceURL) {
    g_evalPending.fetch_add(1, std::memory_order_relaxed);
    rdmaPostJsHigh([buffer, sourceURL] {
        evalJavaScript(buffer, sourceURL);
        g_evalPending.fetch_sub(1, std::memory_order_relaxed);
    });
}

void rdmaEvalFile(const std::string& path) {
    auto buffer = mmapFileBuffer(path);
    if (!buffer) {
        LOGW("rdmaEvalFile: cannot mmap %s", path.c_str());
        return;
    }
    rdmaEvalBuffer(buffer, path);
}

void rdmaEvalFiles(const std::vector<std::string>& paths) {
    std::vector<std::pair<std::shared_ptr<const facebook::jsi::Buffer>, std::string>> buffers;
    for (const auto& path : paths) {
        auto buffer = mmapFileBuffer(path);
        if (!buffer) {
            LOGW("rdmaEvalFiles: cannot mmap %s", path.c_str());
            continue;
        }
        buffers.emplace_back(std::move(buffer), path);
    }
    if (buffers.empty()) return;
    // One task: nothing else (a composition, a callback) runs between the bundles, so no
    // caller ever sees a half-applied update.
    g_evalPending.fetch_add(1, std::memory_order_relaxed);
    rdmaPostJsHigh([buffers] {
        for (const auto& [buffer, url] : buffers) evalJavaScript(buffer, url);
        g_evalPending.fetch_sub(1, std::memory_order_relaxed);
    });
}

bool rdmaIsReady() {
    return g_runtimeReady.load(std::memory_order_acquire) &&
           g_evalPending.load(std::memory_order_relaxed) == 0;
}

void rdmaSetUncaughtExceptionHandler(UncaughtExceptionHandler handler) {
    g_uncaughtHandler.store(handler, std::memory_order_release);
}

void rdmaUncaughtException(const RdmaExceptionInfo& info, const std::string& pluginId) {
    // Global handler.
    UncaughtExceptionHandler h = g_uncaughtHandler.load(std::memory_order_acquire);
    if (h) {
        h(info, pluginId);
        return;
    }
    // No handler registered — log the full chained stack as an emergency
    // fallback (log only, never crash). There is no default handler and no
    // lastError store: the host must install a global handler to observe these.
    LOGW("Uncaught plugin exception [%s] (%s): %s\n%s",
         pluginId.c_str(), info.originalExceptionType.c_str(), info.message.c_str(), info.stack.c_str());
}

static void setThreadName(const char* name) {
#if defined(__ANDROID__)
    pthread_setname_np(pthread_self(), name);
#endif
}

static void jsThreadMain() {
    g_jsTid.store(rdmaGetTid(), std::memory_order_release);
    setThreadName("Hermes");

    // Attach the Hermes thread to the host runtime (JVM attach on Android, no-op
    // elsewhere). Platform caches were already populated on the UI thread, so only
    // cached class/method handles (and bootstrap java.lang.* lookups) are used from
    // here — app-class lookup never happens on this thread.
    rdmaHostAttachThread(g_host);

    // Create the Hermes runtime + install the compose bridge (JSI only; platform
    // caches were initialized on the UI thread before rdmaStart()).
    initRdmaRuntime(g_host);
    g_runtimeReady.store(true, std::memory_order_release);
    g_cv.notify_all();
    LOGI("Hermes thread ready (tid=%d)", (int)rdmaGetTid());

    for (;;) {
        std::shared_ptr<JsRequest> req;
        {
            std::unique_lock<std::mutex> lk(g_mutex);
            // Wait until a queued task arrives or the nearest timer deadline
            // passes. Timers are only inserted from the Hermes thread while it
            // is executing (never while it sleeps here), so re-reading the
            // deadline each iteration has no lost-wakeup race.
            auto deadline = rdmaNextTimerDeadline();
            auto predicate = [&] {
                return !g_uiToJsHigh.empty() || !g_uiToJsLow.empty() || g_stopped.load();
            };
            while (!predicate()) {
                if (spinForChange(lk, deadline ? *deadline : std::chrono::steady_clock::time_point::max())) continue;
                if (deadline && std::chrono::steady_clock::now() >= *deadline) break;
                g_sleepers.fetch_add(1, std::memory_order_relaxed);
                if (deadline) {
                    g_cv.wait_until(lk, *deadline);
                } else {
                    g_cv.wait(lk);
                }
                g_sleepers.fetch_sub(1, std::memory_order_relaxed);
                if (deadline && std::chrono::steady_clock::now() >= *deadline) break;
            }
            if (g_stopped.load() && g_uiToJsHigh.empty() && g_uiToJsLow.empty()) {
                return;
            }
            // Fire due timers first: append their callbacks to the low queue so
            // they run after already-queued work and never preempt composition.
            auto dueFns = rdmaPopDueTimers();
            for (auto& fn : dueFns) {
                auto timerReq = std::make_shared<JsRequest>();
                timerReq->task = [fn = std::move(fn)] {
                    jsi::Runtime* rt = getRdmaRuntime();
                    if (rt) fn->call(*rt, nullptr, 0);
                };
                g_uiToJsLow.push_back(timerReq);
            }
            if (!popJsRequestLocked(req)) {
                // Spurious wakeup or only cancelled timers: nothing to run.
                continue;
            }
        }
        executeJsTask(*req);
        // Blocks registered during this top-level task that no kernel holder
        // retained can never be retained later (docs/design/runtime_rework.md §4).
        rdmaBlockSweepUnretained();
        rdmaPlatformAfterJsTask();
        if (req->hasError && !req->blocking) {
            rdmaUncaughtException(req->error, "");
        }
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            req->done = true;
            notifyLocked();
        }
    }
}

void rdmaSetSpinWaitMicros(int micros) {
    g_spinUs.store(micros < 0 ? 0 : micros, std::memory_order_relaxed);
    LOGI("spin-wait: %d us", micros < 0 ? 0 : micros);
}

void rdmaStart(HostContext ctx) {
    g_host = ctx;
    g_uiTid.store(rdmaGetTid(), std::memory_order_release);
    g_jsThread = std::thread(jsThreadMain);
}

} // namespace rdma
} // namespace facebook
