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
#include "RdmaTrace.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <map>
#include <mutex>
#include <set>
#include <unordered_map>
#include <vector>

#include "RdmaRendezvous.h"
#include "RdmaRuntime.h"

namespace facebook {
namespace rdma {

namespace {

using Clock = std::chrono::steady_clock;

struct FnStats {
    std::string name;
    uint64_t runs = 0;
    uint64_t first = 0;    // first run of its recompose scope
    uint64_t restarts = 0; // re-run by its own scope's invalidation
    uint64_t byParent = 0; // re-run because the caller ran it again
    int64_t totalNs = 0;
    int64_t selfNs = 0;
    std::map<std::string, uint64_t> restartCauses;
    std::map<int32_t, uint64_t> parents; // caller's key (0 = called from the kernel)
    std::map<std::string, uint64_t> changedParams;
};

struct Frame {
    int32_t key;
    Clock::time_point start;
    int64_t childNs;
};

std::atomic<bool> g_on{false};
std::mutex g_m;
Clock::time_point g_since;
std::unordered_map<int32_t, FnStats> g_fns;
// The plugin-side call stack (Hermes thread): parents and self time.
std::vector<Frame> g_stack;
// The kernel scope entered last and not yet matched to a plugin function. A plugin
// composable enters its scope (`startRestartGroup`, a rendezvous) right before its trace
// start, with no other scope event in between, so the two pair up exactly.
int64_t g_pendingEnter = 0;
std::vector<std::string> g_pendingCauses;
std::unordered_map<int64_t, std::vector<std::string>> g_invalidations; // scope -> causes
std::unordered_map<int64_t, int32_t> g_scopeKey;                        // scope -> function
std::unordered_map<int64_t, std::set<int32_t>> g_readers;               // value -> functions

constexpr size_t kMaxStack = 512;

void clearLocked() {
    g_fns.clear();
    g_stack.clear();
    g_pendingEnter = 0;
    g_pendingCauses.clear();
    g_invalidations.clear();
    g_scopeKey.clear();
    g_readers.clear();
    g_since = Clock::now();
}

// Plugin parameters whose bits in `$dirty` say "changed" (Compose's ParamState.Different;
// 3 bits per parameter after the force bit).
std::string changedParams(int32_t dirty) {
    if (dirty == -1) return "unknown";
    std::string out;
    for (int i = 0; i < 10; i++) {
        if (((dirty >> (1 + 3 * i)) & 7) == 2) {
            if (!out.empty()) out += ",";
            out += "#" + std::to_string(i);
        }
    }
    return out.empty() ? "none" : out;
}

std::string nameOf(int32_t key) {
    if (key == 0) return "(kernel)";
    auto it = g_fns.find(key);
    return it == g_fns.end() || it->second.name.empty() ? "key " + std::to_string(key) : it->second.name;
}

void traceStartLocked(int32_t key, int32_t dirty, FnStats& f) {
    f.runs++;
    const int64_t scope = g_pendingEnter;
    g_pendingEnter = 0;
    std::vector<std::string> causes;
    causes.swap(g_pendingCauses);
    bool first = false;
    if (scope != 0) {
        auto [it, inserted] = g_scopeKey.emplace(scope, key);
        if (inserted) first = true; else it->second = key;
    }
    if (first) {
        f.first++;
    } else if (!causes.empty() || (dirty != -1 && (dirty & 1))) {
        // Its own invalidation: restarted by its scope (the force bit), or run by its caller
        // while invalid (an invalidated child of a recomposing parent gets no force bit).
        f.restarts++;
        if (causes.empty()) f.restartCauses["(not reported)"]++;
        for (auto& c : causes) f.restartCauses[c]++;
    } else {
        f.byParent++;
        f.parents[g_stack.empty() ? 0 : g_stack.back().key]++;
        f.changedParams[changedParams(dirty)]++;
    }
    if (g_stack.size() >= kMaxStack) g_stack.clear(); // unbalanced (an exception skipped ends)
    g_stack.push_back(Frame{key, Clock::now(), 0});
}

void traceEndLocked() {
    if (g_stack.empty()) return; // turned on mid-run
    Frame fr = g_stack.back();
    g_stack.pop_back();
    const int64_t ns = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - fr.start).count();
    auto it = g_fns.find(fr.key);
    if (it != g_fns.end()) {
        it->second.totalNs += ns;
        it->second.selfNs += ns - fr.childNs;
    }
    if (!g_stack.empty()) g_stack.back().childNs += ns;
}

std::string ms(int64_t ns) {
    char buf[32];
    std::snprintf(buf, sizeof buf, "%.1fms", ns / 1e6);
    return buf;
}

template <typename K>
std::vector<std::pair<K, uint64_t>> byCount(const std::map<K, uint64_t>& m) {
    std::vector<std::pair<K, uint64_t>> v(m.begin(), m.end());
    std::stable_sort(v.begin(), v.end(), [](auto& a, auto& b) { return a.second > b.second; });
    return v;
}

void applyTracerJs(bool enabled) {
    rdmaPostJs([enabled] {
        jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        jsi::Value rdma = rt->global().getProperty(*rt, "RDMA");
        if (!rdma.isObject()) return;
        jsi::Object obj = rdma.getObject(*rt);
        // Read by bridges loaded later, which install the tracer themselves.
        obj.setProperty(*rt, "tracing", enabled);
        jsi::Value install = obj.getProperty(*rt, "installTracer");
        if (install.isObject() && install.getObject(*rt).isFunction(*rt)) {
            install.getObject(*rt).getFunction(*rt).call(*rt, enabled);
        }
    });
}

} // namespace

void rdmaTraceSetEnabled(bool enabled) {
    {
        std::lock_guard<std::mutex> lk(g_m);
        if (enabled && !g_on.load()) clearLocked();
        g_on.store(enabled);
    }
    applyTracerJs(enabled);
}

bool rdmaTraceEnabled() { return g_on.load(std::memory_order_relaxed); }

void rdmaTraceScopeEvent(int32_t kind, int64_t scope, int64_t value, const char* desc) {
    if (!g_on.load(std::memory_order_relaxed)) return;
    std::lock_guard<std::mutex> lk(g_m);
    switch (kind) {
        case kRdmaTraceEnter: {
            // A recomposed scope is entered twice: by the recomposer, then by its own
            // `startRestartGroup`. Keep the causes taken on the first.
            if (scope == g_pendingEnter) break;
            g_pendingEnter = scope;
            g_pendingCauses.clear();
            auto it = g_invalidations.find(scope);
            if (it != g_invalidations.end()) {
                g_pendingCauses.swap(it->second);
                g_invalidations.erase(it);
            }
            break;
        }
        case kRdmaTraceExit:
            if (g_pendingEnter == scope) g_pendingEnter = 0; // skipped, or a kernel scope
            break;
        case kRdmaTraceInvalidated: {
            std::string cause = desc ? desc : "?";
            auto readers = g_readers.find(value);
            if (value != 0 && readers != g_readers.end() && !readers->second.empty()) {
                cause += ", read in ";
                bool firstName = true;
                for (int32_t k : readers->second) {
                    if (!firstName) cause += ", ";
                    cause += nameOf(k);
                    firstName = false;
                }
            }
            auto& list = g_invalidations[scope];
            if (std::find(list.begin(), list.end(), cause) == list.end()) list.push_back(std::move(cause));
            break;
        }
        case kRdmaTraceRead: {
            auto it = g_scopeKey.find(scope);
            if (it != g_scopeKey.end()) g_readers[value].insert(it->second);
            break;
        }
        case kRdmaTraceDisposed:
            g_scopeKey.erase(scope);
            g_invalidations.erase(scope);
            break;
        default:
            break;
    }
}

std::string rdmaTraceReport() {
    std::lock_guard<std::mutex> lk(g_m);
    std::vector<const FnStats*> fns;
    uint64_t runs = 0;
    for (auto& [key, f] : g_fns) {
        fns.push_back(&f);
        runs += f.runs;
    }
    std::stable_sort(fns.begin(), fns.end(), [](auto* a, auto* b) {
        return a->runs != b->runs ? a->runs > b->runs : a->selfNs > b->selfNs;
    });
    const int64_t sinceNs =
        std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - g_since).count();
    std::string out = "Plugin composable runs: " + std::to_string(runs) + " in " + ms(sinceNs) +
                      (g_on.load() ? "" : " (tracing is off)") + "\n";
    for (const FnStats* f : fns) {
        out += f->name + ": runs=" + std::to_string(f->runs) + " first=" + std::to_string(f->first) +
               " restarts=" + std::to_string(f->restarts) + " byParent=" + std::to_string(f->byParent) +
               " self=" + ms(f->selfNs) + " total=" + ms(f->totalNs) + "\n";
        if (!f->restartCauses.empty()) {
            out += "  restarted by:";
            bool firstCause = true;
            for (auto& [cause, n] : byCount(f->restartCauses)) {
                out += (firstCause ? " " : "; ") + cause + " x" + std::to_string(n);
                firstCause = false;
            }
            out += "\n";
        }
        if (!f->parents.empty()) {
            out += "  called again by:";
            bool firstParent = true;
            for (auto& [parent, n] : byCount(f->parents)) {
                out += (firstParent ? " " : "; ") + nameOf(parent) + " x" + std::to_string(n);
                firstParent = false;
            }
            out += "\n  changed params:";
            bool firstParams = true;
            for (auto& [params, n] : byCount(f->changedParams)) {
                out += (firstParams ? " " : "; ") + params + " x" + std::to_string(n);
                firstParams = false;
            }
            out += "\n";
        }
    }
    return out;
}

void rdmaTraceInstallJs(jsi::Runtime& rt, jsi::Object& rdma) {
    auto start = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "traceStart"), 4,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (!g_on.load(std::memory_order_relaxed) || count < 2 || !args[0].isNumber()) {
                return jsi::Value::undefined();
            }
            const int32_t key = (int32_t)args[0].getNumber();
            const int32_t dirty = args[1].isNumber() ? (int32_t)args[1].getNumber() : -1;
            std::lock_guard<std::mutex> lk(g_m);
            FnStats& f = g_fns[key];
            if (f.name.empty()) {
                f.name = count > 3 && args[3].isString() ? args[3].getString(r).utf8(r) : "key " + std::to_string(key);
            }
            traceStartLocked(key, dirty, f);
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "traceStart", std::move(start));

    auto end = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "traceEnd"), 0,
        [](jsi::Runtime&, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
            if (!g_on.load(std::memory_order_relaxed)) return jsi::Value::undefined();
            std::lock_guard<std::mutex> lk(g_m);
            traceEndLocked();
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "traceEnd", std::move(end));
}

} // namespace rdma
} // namespace facebook
