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
#include "RdmaBlocks.h"
#include "RdmaRendezvous.h"

#include <mutex>
#include <unordered_map>

namespace facebook {
namespace rdma {

namespace {

struct Entry {
    std::shared_ptr<jsi::Function> fn; // touched on the JS thread only
    std::string pluginId;
    std::vector<std::string> paramDescs;
    int holders = 0;
    bool explicitOwned = false;
};

std::mutex g_mutex;
std::unordered_map<int64_t, Entry> g_blocks;
int64_t g_nextId = 1;
std::vector<int64_t> g_unretained; // registered during the current JS task (JS thread)

const char* kIdProp = "__rdmaBlockId";

// Destroys the entry on the JS thread when it still has no holders.
void destroyIfUnheld(int64_t id) {
    std::shared_ptr<jsi::Function> dying;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_blocks.find(id);
        if (it == g_blocks.end() || it->second.holders > 0 || it->second.explicitOwned) return;
        dying = std::move(it->second.fn);
        g_blocks.erase(it);
    }
    // `dying` is released here, on the JS thread.
}

} // namespace

int64_t rdmaBlockRegisterPlugin(jsi::Runtime& rt, const jsi::Function& fn, const std::string& pluginId) {
    // A function registered before keeps its id while the block is alive.
    jsi::Value known = fn.getProperty(rt, kIdProp);
    if (known.isNumber()) {
        int64_t id = (int64_t)known.getNumber();
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_blocks.find(id);
        if (it != g_blocks.end() && it->second.fn && jsi::Function::strictEquals(rt, *it->second.fn, fn)) {
            if (it->second.holders == 0) g_unretained.push_back(id);
            return id;
        }
    }
    int64_t id;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        id = g_nextId++;
        Entry e;
        e.fn = std::make_shared<jsi::Function>(jsi::Value(rt, fn).getObject(rt).getFunction(rt));
        e.pluginId = pluginId;
        g_blocks.emplace(id, std::move(e));
        g_unretained.push_back(id);
    }
    fn.setProperty(rt, kIdProp, jsi::Value((double)id));
    return id;
}

int64_t rdmaBlockRegisterExplicit(std::shared_ptr<jsi::Function> fn, const std::string& pluginId) {
    std::lock_guard<std::mutex> lk(g_mutex);
    int64_t id = g_nextId++;
    Entry e;
    e.fn = std::move(fn);
    e.pluginId = pluginId;
    e.explicitOwned = true;
    g_blocks.emplace(id, std::move(e));
    return id;
}

std::shared_ptr<jsi::Function> rdmaBlockGet(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_blocks.find(id);
    return it == g_blocks.end() ? nullptr : it->second.fn;
}

std::string rdmaBlockPluginId(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_blocks.find(id);
    return it == g_blocks.end() ? "" : it->second.pluginId;
}

void rdmaBlockErase(int64_t id) {
    std::shared_ptr<jsi::Function> dying;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_blocks.find(id);
        if (it == g_blocks.end()) return;
        dying = std::move(it->second.fn);
        g_blocks.erase(it);
    }
}

void rdmaBlockRetain(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_blocks.find(id);
    if (it != g_blocks.end()) it->second.holders++;
}

void rdmaBlockRelease(int64_t id) {
    bool last = false;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        auto it = g_blocks.find(id);
        if (it == g_blocks.end() || it->second.holders <= 0) return;
        last = --it->second.holders == 0 && !it->second.explicitOwned;
    }
    // FIFO with the invocations of this block posted before the release.
    if (last) rdmaPostJs([id] { destroyIfUnheld(id); });
}

void rdmaBlockSetParamDescs(int64_t id, std::vector<std::string> descs) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_blocks.find(id);
    if (it != g_blocks.end()) it->second.paramDescs = std::move(descs);
}

void rdmaBlockSetParamDescs(int64_t id, const std::string& joined) {
    std::vector<std::string> descs;
    if (!joined.empty()) {
        size_t start = 0;
        while (true) {
            size_t comma = joined.find(',', start);
            descs.push_back(joined.substr(start, comma == std::string::npos ? std::string::npos : comma - start));
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
    }
    rdmaBlockSetParamDescs(id, std::move(descs));
}

std::vector<std::string> rdmaBlockParamDescs(int64_t id) {
    std::lock_guard<std::mutex> lk(g_mutex);
    auto it = g_blocks.find(id);
    return it == g_blocks.end() ? std::vector<std::string>{} : it->second.paramDescs;
}

void rdmaBlockSweepUnretained() {
    std::vector<int64_t> ids;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        ids.swap(g_unretained);
    }
    for (int64_t id : ids) destroyIfUnheld(id);
}

size_t rdmaBlockCount() {
    std::lock_guard<std::mutex> lk(g_mutex);
    return g_blocks.size();
}

} // namespace rdma
} // namespace facebook
