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
#include "RdmaIdentity.h"

#include <memory>

namespace facebook {
namespace rdma {

namespace {
// One table per runtime. The objects are owned by that runtime; a table of a previous
// runtime is deliberately leaked (destroying JSI values after their runtime is undefined).
struct IdentityTable {
    jsi::Runtime* rt = nullptr;
    jsi::Object map;
    jsi::Function get;
    jsi::Function set;
    IdentityTable(jsi::Runtime& r, jsi::Object m, jsi::Function g, jsi::Function s)
        : rt(&r), map(std::move(m)), get(std::move(g)), set(std::move(s)) {}
};
IdentityTable* g_table = nullptr;
int64_t g_nextIdentity = 1;
} // namespace

void rdmaIdentityInstall(jsi::Runtime& rt) {
    if (g_table && g_table->rt == &rt) return;
    jsi::Object map = rt.global().getPropertyAsFunction(rt, "WeakMap").callAsConstructor(rt).asObject(rt);
    jsi::Function get = map.getPropertyAsFunction(rt, "get");
    jsi::Function set = map.getPropertyAsFunction(rt, "set");
    g_table = new IdentityTable(rt, std::move(map), std::move(get), std::move(set));
}

int64_t rdmaJsIdentity(jsi::Runtime& rt, const jsi::Object& obj) {
    rdmaIdentityInstall(rt);
    jsi::Value known = g_table->get.callWithThis(rt, g_table->map, jsi::Value(rt, obj));
    if (known.isNumber()) return (int64_t)known.getNumber();
    int64_t id = g_nextIdentity++;
    g_table->set.callWithThis(rt, g_table->map, jsi::Value(rt, obj), jsi::Value((double)id));
    return id;
}

} // namespace rdma
} // namespace facebook
