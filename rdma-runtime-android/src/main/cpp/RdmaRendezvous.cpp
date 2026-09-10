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

#include <deque>
#include <memory>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

#ifndef NDEBUG
#include <cassert>
#endif

#include "RdmaRuntime.h"

#define LOG_TAG "RdmaRendezvous"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

std::atomic<pid_t> g_uiTid{0};
std::atomic<pid_t> g_jsTid{0};

namespace {

struct UiRequest {
    UiTask task;
    RdmaResult result;
    bool done = false;
};

struct JsRequest {
    JsTask task;
    bool done = false;
};

// Single mutex + condition variable guarding both queues. Both threads use the
// same mutex; while waiting for a response each thread reentrantly services the
// opposite queue, which is what makes nested UI->JS->UI->JS composition work.
std::mutex g_mutex;
std::condition_variable g_cv;

// JS -> UI (compose ops). No priority needed: every UI-bound op is a synchronous
// compose call that the UI thread must run in order.
std::deque<std::shared_ptr<UiRequest>> g_jsToUi;

// UI -> JS. Split into high (compose/init: RunContent, RunScopeBlock, eval) and
// low (async: callbacks/lambdas) so that async parsing never stalls composition.
std::deque<std::shared_ptr<JsRequest>> g_uiToJsHigh;
std::deque<std::shared_ptr<JsRequest>> g_uiToJsLow;

std::atomic<bool> g_runtimeReady{false};
std::atomic<int> g_evalPending{0};
std::atomic<bool> g_stopped{false};

// Count of active blocking rdmaCallJs calls on the UI thread (rdmaCallJs can be
// nested: a widget content lambda composes nativeInvokeScopeBlock inside the
// outer content composition). rdmaCallUi is only valid while this is > 0: a
// blocking JS->UI call made while the UI thread is not at the boundary (e.g.
// from a deferred coroutine) would never be serviced and would deadlock forever.
std::atomic<int> g_uiInRendezvous{0};

JavaVM* g_jvm = nullptr;
std::thread g_jsThread;

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

void executeJsTask(const JsTask& task) {
#ifndef NDEBUG
    assert(g_jsTid.load(std::memory_order_relaxed) != 0 &&
           gettid() == g_jsTid.load(std::memory_order_relaxed));
#endif
    // Bound local refs created by JSI host functions: they are not JNI call
    // boundaries, so on the long-lived Hermes thread local refs would otherwise
    // accumulate until the local reference table overflows.
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK &&
        env->PushLocalFrame(512) == 0) {
        task();
        env->PopLocalFrame(nullptr);
        return;
    }
    task();
}

void executeUiTask(UiRequest& req) {
#ifndef NDEBUG
    assert(g_uiTid.load(std::memory_order_relaxed) != 0 &&
           gettid() == g_uiTid.load(std::memory_order_relaxed));
#endif
    req.result = req.task();
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

    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_jsToUi.push_back(req);
    }
    g_cv.notify_all();

    std::unique_lock<std::mutex> lk(g_mutex);
    while (!req->done) {
        std::shared_ptr<JsRequest> jsReq;
        if (popJsRequestHighLocked(jsReq)) {
            lk.unlock();
            executeJsTask(jsReq->task);
            lk.lock();
            jsReq->done = true;
            g_cv.notify_all();
        } else {
            g_cv.wait(lk);
        }
    }
    return std::move(req->result);
}

void rdmaCallJs(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);

    // Increment before enqueueing so a JS task cannot observe the counter as
    // zero while the UI thread is about to service it (closes the push/notify
    // race). rdmaCallJs is reentrant (nested scope blocks), hence a counter.
    g_uiInRendezvous.fetch_add(1, std::memory_order_release);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsHigh.push_back(req);
    }
    g_cv.notify_all();

    std::unique_lock<std::mutex> lk(g_mutex);
    while (!req->done) {
        if (!g_jsToUi.empty()) {
            auto uiReq = g_jsToUi.front();
            g_jsToUi.pop_front();
            lk.unlock();
            executeUiTask(*uiReq);
            lk.lock();
            uiReq->done = true;
            g_cv.notify_all();
        } else {
            g_cv.wait(lk);
        }
    }
    g_uiInRendezvous.fetch_sub(1, std::memory_order_release);
}

void rdmaPostJs(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsLow.push_back(req);
    }
    g_cv.notify_all();
}

void rdmaPostJsHigh(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsHigh.push_back(req);
    }
    g_cv.notify_all();
}

void rdmaPostJsSelf(JsTask task) {
    auto req = std::make_shared<JsRequest>();
    req->task = std::move(task);
    {
        std::unique_lock<std::mutex> lk(g_mutex);
        g_uiToJsLow.push_back(req);
    }
    g_cv.notify_all();
}

void rdmaEvalAsset(const std::string& code) {
    g_evalPending.fetch_add(1, std::memory_order_relaxed);
    rdmaPostJsHigh([code] {
        evalJavaScript(code);
        g_evalPending.fetch_sub(1, std::memory_order_relaxed);
    });
}

bool rdmaIsReady() {
    return g_runtimeReady.load(std::memory_order_acquire) &&
           g_evalPending.load(std::memory_order_relaxed) == 0;
}

static void setThreadName(const char* name) {
#if defined(__ANDROID__)
    pthread_setname_np(pthread_self(), name);
#endif
}

static void jsThreadMain() {
    g_jsTid.store(gettid(), std::memory_order_release);
    setThreadName("Hermes");

    // Attach the Hermes thread to the JVM. JNI caches were already populated on
    // the UI thread, so only cached jclass/jmethodID (and bootstrap java.lang.*
    // lookups) are used from here — app-class FindClass never happens on this thread.
    {
        JNIEnv* env = nullptr;
        if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            g_jvm->AttachCurrentThread(&env, nullptr);
        }
    }

    // Create the Hermes runtime + install the compose bridge (JSI only; JNI
    // caches were initialized on the UI thread before rdmaStart()).
    initRdmaRuntime(g_jvm);
    g_runtimeReady.store(true, std::memory_order_release);
    g_cv.notify_all();
    LOGI("Hermes thread ready (tid=%d)", (int)gettid());

    for (;;) {
        std::shared_ptr<JsRequest> req;
        {
            std::unique_lock<std::mutex> lk(g_mutex);
            g_cv.wait(lk, [&] {
                return !g_uiToJsHigh.empty() || !g_uiToJsLow.empty() || g_stopped.load();
            });
            if (g_stopped.load() && g_uiToJsHigh.empty() && g_uiToJsLow.empty()) {
                return;
            }
            popJsRequestLocked(req);
        }
        executeJsTask(req->task);
        {
            std::lock_guard<std::mutex> lk(g_mutex);
            req->done = true;
        }
        g_cv.notify_all();
    }
}

void rdmaStart(JavaVM* jvm) {
    g_jvm = jvm;
    g_uiTid.store(gettid(), std::memory_order_release);
    g_jsThread = std::thread(jsThreadMain);
}

} // namespace rdma
} // namespace facebook
