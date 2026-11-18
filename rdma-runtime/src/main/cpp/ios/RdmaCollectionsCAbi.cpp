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

// C ABI list/map proxies on the shared-prototype object model
// (docs/design/runtime_rework.md §3.4–3.6), plus the C ABI implementation of
// `rdmaPlatformProtoMethods`. Parity with ListHandle.cpp / MapHandle.cpp (JNI): the
// same prototypes, names and semantics; only the leaf operations differ.
//
// Handle ownership (CAbiShim.kt): shim functions BORROW their input handles and
// return NEW handles. Inputs created here are disposed here — inside the operation,
// because a state-backed write may run later on the main queue.

#define LOG_TAG "RdmaCollectionsCAbi"

#include "RdmaComposeCAbi.h"
#include "RdmaNativeStateCAbi.h"
#include "RdmaProxyCore.h"
#include "RdmaJsLong.h"
#include "RdmaLog.h"

#include <memory>
#include <string>
#include <vector>

namespace facebook {
namespace rdma {

namespace {

bool isListDesc(const std::string& d) { return d.rfind("list:", 0) == 0; }
bool isMapDesc(const std::string& d) { return d.rfind("map:", 0) == 0; }

std::pair<std::string, std::string> parseMapBody(const std::string& s) {
    if (s.rfind("list:", 0) == 0) {
        auto inner = parseMapBody(s.substr(5));
        return {"list:" + inner.first, inner.second};
    }
    if (s.rfind("map:", 0) == 0) {
        auto k = parseMapBody(s.substr(4));
        auto v = parseMapBody(k.second);
        return {"map:" + k.first + ":" + v.first, v.second};
    }
    size_t idx = s.find(':');
    if (idx == std::string::npos) return {s, ""};
    return {s.substr(0, idx), s.substr(idx + 1)};
}

void dispose(void* h) {
    if (h) rdma_disposeStableRef(h);
}

std::shared_ptr<ListStateCAbi> listOf(jsi::Runtime& r, const jsi::Value& t) {
    auto st = rdmaStateAs<ListStateCAbi>(r, t, RdmaStateKind::List);
    if (!st) throw jsi::JSError(r, "receiver is not a native list proxy");
    return st;
}

std::shared_ptr<MapStateCAbi> mapOf(jsi::Runtime& r, const jsi::Value& t) {
    auto st = rdmaStateAs<MapStateCAbi>(r, t, RdmaStateKind::Map);
    if (!st) throw jsi::JSError(r, "receiver is not a native map proxy");
    return st;
}

std::shared_ptr<MapViewStateCAbi> viewOf(jsi::Runtime& r, const jsi::Value& t) {
    auto st = rdmaStateAs<MapViewStateCAbi>(r, t, RdmaStateKind::MapView);
    if (!st) throw jsi::JSError(r, "receiver is not a native map view");
    return st;
}

// Runs `op` under the state contract and converts its result on the JS thread.
jsi::Value run(jsi::Runtime& r, bool stateBacked, bool write, std::function<RdmaResult()> op) {
    RdmaResult res = rdmaDispatchStateOpCAbi(stateBacked, write, std::move(op));
    return rdmaResultToJsiCAbi(r, res);
}

RdmaResult errorOr(RdmaResult ok) {
    RdmaResult captured;
    if (captureCAbiError(captured)) return captured;
    return ok;
}

// Reads every element of a Kotlin list into fresh handles (one state dispatch for
// the whole read), then wraps them on the JS thread.
jsi::Array snapshotList(jsi::Runtime& r, void* listHandle, bool stateBacked, const std::string& desc) {
    std::vector<void*> handles;
    RdmaResult res = rdmaDispatchStateOpCAbi(stateBacked, false, [listHandle, &handles]() -> RdmaResult {
        int32_t n = rdma_listSize(listHandle);
        handles.reserve((size_t)n);
        for (int32_t i = 0; i < n; i++) handles.push_back(rdma_listGet(listHandle, i));
        return errorOr(RdmaResult::undefined());
    });
    if (res.kind == RdmaResultKind::Error) {
        for (void* h : handles) dispose(h);
        rdmaResultToJsiCAbi(r, res); // throws
    }
    jsi::Array out(r, handles.size());
    for (size_t i = 0; i < handles.size(); i++) out.setValueAtIndex(r, i, rdmaWrapTypedHandleCAbi(r, handles[i], desc));
    return out;
}

// Keys or values of a Kotlin map as a fresh list handle snapshot, then its elements.
jsi::Array snapshotMapPart(jsi::Runtime& r, void* mapHandle, bool stateBacked, bool keys, const std::string& desc) {
    void* list = nullptr;
    RdmaResult res = rdmaDispatchStateOpCAbi(stateBacked, false, [mapHandle, keys, &list]() -> RdmaResult {
        list = keys ? rdma_mapKeysToList(mapHandle) : rdma_mapValuesToList(mapHandle);
        return errorOr(RdmaResult::undefined());
    });
    if (res.kind == RdmaResultKind::Error) {
        dispose(list);
        rdmaResultToJsiCAbi(r, res); // throws
    }
    jsi::Array out = snapshotList(r, list, false, desc);
    dispose(list);
    return out;
}

// A temporary Kotlin list holding the elements of any JS collection argument (the
// native list behind a proxy is cloned; a plugin collection is copied through its
// public `toArray()` and is NOT adopted). Returns a NEW handle.
void* tempCollection(jsi::Runtime& r, const jsi::Value& v, const std::string& elemDesc) {
    if (!v.isObject()) rdmaThrowTypeError(r, "expected a collection argument");
    if (void* h = rdmaListHandleOf(r, v)) return rdma_cloneHandle(h);
    jsi::Object obj = v.getObject(r);
    jsi::Array elems(r, 0);
    if (obj.isArray(r)) {
        elems = obj.getArray(r);
    } else {
        jsi::Value toArray = obj.getProperty(r, "toArray");
        if (!toArray.isObject() || !toArray.getObject(r).isFunction(r)) rdmaThrowTypeError(r, "collection argument has no toArray()");
        elems = toArray.getObject(r).getFunction(r).callWithThis(r, obj, nullptr, 0).getObject(r).getArray(r);
    }
    std::vector<void*> handles;
    handles.reserve(elems.size(r));
    for (size_t i = 0; i < elems.size(r); i++) handles.push_back(jsiToHandle(r, elems.getValueAtIndex(r, i), elemDesc));
    return rdma_listCreate(handles.data(), (int32_t)handles.size()); // consumes the element handles
}

jsi::String cstrToJs(jsi::Runtime& r, const char* s, const char* fallback) {
    return jsi::String::createFromUtf8(r, s ? s : fallback);
}

std::vector<RdmaProtoMethod> listMethods() {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"get", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 1 || !a[0].isNumber()) throw jsi::JSError(r, "get: index expected");
        int32_t i = (int32_t)a[0].getNumber();
        void* h = st->handle;
        std::string d = st->elementType;
        return run(r, st->stateBacked, false, [h, i, d]() -> RdmaResult {
            void* e = rdma_listGet(h, i);
            return errorOr(e ? RdmaResult::hostValue(e, d) : RdmaResult::null());
        });
    }});
    m.push_back({"set", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 2 || !a[0].isNumber()) throw jsi::JSError(r, "set: index and value expected");
        rdmaCheckListMutable(r, t.getObject(r));
        int32_t i = (int32_t)a[0].getNumber();
        void* v = jsiToHandle(r, a[1], st->elementType);
        void* h = st->handle;
        std::string d = st->elementType;
        return run(r, st->stateBacked, true, [h, i, v, d]() -> RdmaResult {
            void* prev = rdma_listSet(h, i, v);
            dispose(v);
            return errorOr(prev ? RdmaResult::hostValue(prev, d) : RdmaResult::null());
        });
    }});
    m.push_back({"add", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        rdmaCheckListMutable(r, t.getObject(r));
        bool at = n >= 2 && a[0].isNumber() && !a[1].isUndefined();
        int32_t i = at ? (int32_t)a[0].getNumber() : -1;
        void* v = jsiToHandle(r, at ? a[1] : rdmaArg(a, n, 0), st->elementType);
        void* h = st->handle;
        jsi::Value res = run(r, st->stateBacked, true, [h, i, v]() -> RdmaResult {
            if (i >= 0) rdma_listAddAt(h, i, v);
            else rdma_listAdd(h, v);
            dispose(v);
            return errorOr(RdmaResult::undefined());
        });
        (void)res;
        return at ? jsi::Value::undefined() : jsi::Value(true);
    }});
    m.push_back({"addAll", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 1) throw jsi::JSError(r, "addAll: collection expected");
        rdmaCheckListMutable(r, t.getObject(r));
        void* other = tempCollection(r, a[n - 1], st->elementType);
        int32_t i = (n >= 2 && a[0].isNumber()) ? (int32_t)a[0].getNumber() : -1;
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h, i, other]() -> RdmaResult {
            bool changed = i >= 0 ? rdma_listAddAllAt(h, i, other) : rdma_listAddAll(h, other);
            dispose(other);
            return errorOr(RdmaResult::boolean(changed));
        });
    }});
    m.push_back({"removeAt", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 1 || !a[0].isNumber()) throw jsi::JSError(r, "removeAt: index expected");
        rdmaCheckListMutable(r, t.getObject(r));
        int32_t i = (int32_t)a[0].getNumber();
        void* h = st->handle;
        std::string d = st->elementType;
        return run(r, st->stateBacked, true, [h, i, d]() -> RdmaResult {
            void* removed = rdma_listRemoveAt(h, i);
            return errorOr(removed ? RdmaResult::hostValue(removed, d) : RdmaResult::null());
        });
    }});
    m.push_back({"remove", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        rdmaCheckListMutable(r, t.getObject(r));
        void* v = jsiToHandle(r, rdmaArg(a, n, 0), st->elementType);
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h, v]() -> RdmaResult {
            bool removed = rdma_listRemoveValue(h, v);
            dispose(v);
            return errorOr(RdmaResult::boolean(removed));
        });
    }});
    m.push_back({"removeRange", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 2) throw jsi::JSError(r, "removeRange: range expected");
        rdmaCheckListMutable(r, t.getObject(r));
        int32_t from = (int32_t)a[0].getNumber(), to = (int32_t)a[1].getNumber();
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h, from, to]() -> RdmaResult {
            rdma_listRemoveRange(h, from, to);
            return errorOr(RdmaResult::undefined());
        });
    }});
    auto bulk = [](bool (*op)(void*, void*), bool mutating) {
        return [op, mutating](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
            auto st = listOf(r, t);
            if (n < 1) return jsi::Value(false);
            if (mutating) rdmaCheckListMutable(r, t.getObject(r));
            void* other = tempCollection(r, a[0], st->elementType);
            void* h = st->handle;
            return run(r, st->stateBacked, mutating, [op, h, other]() -> RdmaResult {
                bool res = op(h, other);
                dispose(other);
                return errorOr(RdmaResult::boolean(res));
            });
        };
    };
    m.push_back({"removeAll", 1, bulk(rdma_listRemoveAll, true)});
    m.push_back({"retainAll", 1, bulk(rdma_listRetainAll, true)});
    m.push_back({"containsAll", 1, bulk(rdma_listContainsAll, false)});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        rdmaCheckListMutable(r, t.getObject(r));
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h]() -> RdmaResult {
            rdma_listClear(h);
            return errorOr(RdmaResult::undefined());
        });
    }});
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::number((double)rdma_listSize(h)));
        });
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::boolean(rdma_listIsEmpty(h)));
        });
    }});
    auto lookup = [](int kind) {
        return [kind](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
            auto st = listOf(r, t);
            void* v = jsiToHandle(r, rdmaArg(a, n, 0), st->elementType);
            void* h = st->handle;
            return run(r, st->stateBacked, false, [kind, h, v]() -> RdmaResult {
                RdmaResult out;
                if (kind == 0) out = RdmaResult::boolean(rdma_listContains(h, v));
                else if (kind == 1) out = RdmaResult::number((double)rdma_listIndexOf(h, v));
                else out = RdmaResult::number((double)rdma_listLastIndexOf(h, v));
                dispose(v);
                return errorOr(out);
            });
        };
    };
    m.push_back({"contains", 1, lookup(0)});
    m.push_back({"indexOf", 1, lookup(1)});
    m.push_back({"lastIndexOf", 1, lookup(2)});
    m.push_back({"subList", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 2) throw jsi::JSError(r, "subList: range expected");
        void* sub = rdma_listSubList(st->handle, (int32_t)a[0].getNumber(), (int32_t)a[1].getNumber());
        checkKotlinError(r);
        return rdmaCreateListHandleCAbi(r, sub, st->elementType, st->stateBacked);
    }});
    // One crossing for the whole list (docs/design/runtime_rework.md §3.6).
    m.push_back({"toArray", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        return snapshotList(r, st->handle, st->stateBacked, st->elementType);
    }});
    m.push_back({"iterator", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        return rdmaSnapshotIterator(r, RdmaIterKind::List, t.getObject(r), snapshotList(r, st->handle, st->stateBacked, st->elementType));
    }});
    m.push_back({"equals", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = listOf(r, t);
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        void* other = rdmaListHandleOf(r, a[0]);
        if (!other) return jsi::Value(rdmaInheritedEquals(r, RdmaProtoKind::List, t.getObject(r), a[0]));
        bool eq = rdma_listEquals(st->handle, other);
        checkKotlinError(r);
        return jsi::Value(eq);
    }});
    m.push_back({"hashCode", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        int32_t h = rdma_listHashCode(st->handle);
        checkKotlinError(r);
        return jsi::Value((double)h);
    }});
    m.push_back({"toString", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = listOf(r, t);
        const char* s = rdma_listToString(st->handle);
        checkKotlinError(r);
        return cstrToJs(r, s, "[]");
    }});
    return m;
}

jsi::Object newView(jsi::Runtime& r, const std::shared_ptr<MapStateCAbi>& st, MapViewStateCAbi::Mode mode) {
    auto view = std::make_shared<MapViewStateCAbi>(rdma_cloneHandle(st->handle), mode, st->keyType, st->valueType, st->stateBacked);
    RdmaProtoKind kind = mode == MapViewStateCAbi::Keys ? RdmaProtoKind::KeysView
        : mode == MapViewStateCAbi::Values ? RdmaProtoKind::ValuesView : RdmaProtoKind::EntriesView;
    return rdmaNewProxy(r, kind, std::move(view));
}

// Entries of a plugin map as `[[k, v], …]` (guest bridge `RDMA.mapToEntries`).
jsi::Array pluginEntriesOf(jsi::Runtime& rt, const jsi::Object& obj) {
    jsi::Value rdma = rt.global().getProperty(rt, "RDMA");
    if (rdma.isObject()) {
        jsi::Value fn = rdma.getObject(rt).getProperty(rt, "mapToEntries");
        if (fn.isObject() && fn.getObject(rt).isFunction(rt)) {
            jsi::Value res = fn.getObject(rt).getFunction(rt).call(rt, jsi::Value(rt, obj));
            if (res.isObject() && res.getObject(rt).isArray(rt)) return res.getObject(rt).getArray(rt);
        }
    }
    throw jsi::JSError(rt, "map crossing the runtime boundary: the plugin bridge has no mapToEntries");
}

std::vector<RdmaProtoMethod> mapMethods() {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"get", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = mapOf(r, t);
        void* k = jsiToHandle(r, rdmaArg(a, n, 0), st->keyType);
        void* h = st->handle;
        std::string d = st->valueType;
        return run(r, st->stateBacked, false, [h, k, d]() -> RdmaResult {
            void* v = rdma_mapGet(h, k);
            dispose(k);
            return errorOr(v ? RdmaResult::hostValue(v, d) : RdmaResult::null());
        });
    }});
    m.push_back({"set", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = mapOf(r, t);
        if (n < 2) throw jsi::JSError(r, "put: key and value expected");
        rdmaCheckMapMutable(r, t.getObject(r));
        void* k = jsiToHandle(r, a[0], st->keyType);
        void* v = jsiToHandle(r, a[1], st->valueType);
        void* h = st->handle;
        std::string d = st->valueType;
        return run(r, st->stateBacked, true, [h, k, v, d]() -> RdmaResult {
            void* prev = rdma_mapPut(h, k, v);
            dispose(k);
            dispose(v);
            return errorOr(prev ? RdmaResult::hostValue(prev, d) : RdmaResult::null());
        });
    }});
    m.push_back({"putAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = mapOf(r, t);
        if (n < 1 || !a[0].isObject()) throw jsi::JSError(r, "putAll: map expected");
        rdmaCheckMapMutable(r, t.getObject(r));
        void* src = rdmaMapHandleOf(r, a[0]);
        jsi::Object srcObj = a[0].getObject(r);
        void* owned = src ? rdma_cloneHandle(src) : buildMapHandleFromJs(r, srcObj, st->keyType, st->valueType);
        void* keys = rdma_mapKeysToList(owned);
        int32_t count = rdma_listSize(keys);
        void* h = st->handle;
        for (int32_t i = 0; i < count; i++) {
            void* k = rdma_listGet(keys, i);
            void* v = rdma_mapGet(owned, k);
            run(r, st->stateBacked, true, [h, k, v]() -> RdmaResult {
                void* prev = rdma_mapPut(h, k, v);
                dispose(prev);
                dispose(k);
                dispose(v);
                return errorOr(RdmaResult::undefined());
            });
        }
        dispose(keys);
        dispose(owned);
        return jsi::Value::undefined();
    }});
    m.push_back({"remove", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = mapOf(r, t);
        rdmaCheckMapMutable(r, t.getObject(r));
        void* k = jsiToHandle(r, rdmaArg(a, n, 0), st->keyType);
        void* h = st->handle;
        std::string d = st->valueType;
        return run(r, st->stateBacked, true, [h, k, d]() -> RdmaResult {
            void* removed = rdma_mapRemoveEntry(h, k);
            dispose(k);
            return errorOr(removed ? RdmaResult::hostValue(removed, d) : RdmaResult::null());
        });
    }});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = mapOf(r, t);
        rdmaCheckMapMutable(r, t.getObject(r));
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h]() -> RdmaResult {
            rdma_mapClear(h);
            return errorOr(RdmaResult::undefined());
        });
    }});
    auto test = [](bool key) {
        return [key](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
            auto st = mapOf(r, t);
            void* x = jsiToHandle(r, rdmaArg(a, n, 0), key ? st->keyType : st->valueType);
            void* h = st->handle;
            return run(r, st->stateBacked, false, [key, h, x]() -> RdmaResult {
                bool has = key ? rdma_mapContainsKey(h, x) : rdma_mapContainsValue(h, x);
                dispose(x);
                return errorOr(RdmaResult::boolean(has));
            });
        };
    };
    m.push_back({"containsKey", 1, test(true)});
    m.push_back({"containsValue", 1, test(false)});
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = mapOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::number((double)rdma_mapSize(h)));
        });
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = mapOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::boolean(rdma_mapIsEmpty(h)));
        });
    }});
    m.push_back({"keys", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        return newView(r, mapOf(r, t), MapViewStateCAbi::Keys);
    }});
    m.push_back({"values", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        return newView(r, mapOf(r, t), MapViewStateCAbi::Values);
    }});
    m.push_back({"entries", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        return newView(r, mapOf(r, t), MapViewStateCAbi::Entries);
    }});
    m.push_back({"checkIsMutable", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        rdmaCheckMapMutable(r, t.getObject(r));
        return jsi::Value::undefined();
    }});
    m.push_back({"equals", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        mapOf(r, t);
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        // Structural equality through the inherited Kotlin AbstractMap.equals (which
        // reads both maps through their public surface).
        return jsi::Value(rdmaInheritedEquals(r, RdmaProtoKind::Map, t.getObject(r), a[0]));
    }});
    m.push_back({"hashCode", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = mapOf(r, t);
        // Kotlin Map.hashCode = sum of (keyHash xor valueHash) over the entries.
        jsi::Object self = t.getObject(r);
        jsi::Object entries = self.getPropertyAsFunction(r, "entries").callWithThis(r, self, nullptr, 0).getObject(r);
        jsi::Object it = entries.getPropertyAsFunction(r, "iterator").callWithThis(r, entries, nullptr, 0).getObject(r);
        int32_t h = 0;
        jsi::Function hasNext = it.getPropertyAsFunction(r, "hasNext");
        jsi::Function next = it.getPropertyAsFunction(r, "next");
        while (hasNext.callWithThis(r, it, nullptr, 0).getBool()) {
            jsi::Object e = next.callWithThis(r, it, nullptr, 0).getObject(r);
            h += (int32_t)e.getPropertyAsFunction(r, "hashCode").callWithThis(r, e, nullptr, 0).getNumber();
        }
        (void)st;
        return jsi::Value((double)h);
    }});
    m.push_back({"toString", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = mapOf(r, t);
        jsi::Array flat(r, 0);
        jsi::Array keys = snapshotMapPart(r, st->handle, st->stateBacked, true, st->keyType);
        jsi::Array values = snapshotMapPart(r, st->handle, st->stateBacked, false, st->valueType);
        std::string out = "{";
        jsi::Function toStr = r.global().getPropertyAsFunction(r, "String");
        for (size_t i = 0; i < keys.size(r) && i < values.size(r); i++) {
            if (i) out += ", ";
            out += toStr.call(r, keys.getValueAtIndex(r, i)).getString(r).utf8(r);
            out += "=";
            out += toStr.call(r, values.getValueAtIndex(r, i)).getString(r).utf8(r);
        }
        out += "}";
        return jsi::String::createFromUtf8(r, out);
    }});
    return m;
}

// Key and value of a runtime entry object (made by rdmaMakeEntries), or false.
bool entryParts(jsi::Runtime& r, const jsi::Value& x, jsi::Value& key, jsi::Value& value) {
    if (!x.isObject()) return false;
    jsi::Object e = x.getObject(r);
    jsi::Value keyOf = e.getProperty(r, "$keyOf");
    if (!keyOf.isObject() || !keyOf.getObject(r).isFunction(r)) return false;
    key = keyOf.getObject(r).getFunction(r).callWithThis(r, e, nullptr, 0);
    value = e.getPropertyAsFunction(r, "$valueOf").callWithThis(r, e, nullptr, 0);
    return true;
}

std::vector<RdmaProtoMethod> viewMethods(MapViewStateCAbi::Mode mode) {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = viewOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::number((double)rdma_mapSize(h)));
        });
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = viewOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, false, [h]() -> RdmaResult {
            return errorOr(RdmaResult::boolean(rdma_mapIsEmpty(h)));
        });
    }});
    m.push_back({"iterator", 0, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = viewOf(r, t);
        if (mode == MapViewStateCAbi::Entries) {
            jsi::Array keys = snapshotMapPart(r, st->handle, st->stateBacked, true, st->keyType);
            jsi::Array values = snapshotMapPart(r, st->handle, st->stateBacked, false, st->valueType);
            size_t n = std::min(keys.size(r), values.size(r));
            jsi::Array flat(r, n * 2);
            for (size_t i = 0; i < n; i++) {
                flat.setValueAtIndex(r, i * 2, keys.getValueAtIndex(r, i));
                flat.setValueAtIndex(r, i * 2 + 1, values.getValueAtIndex(r, i));
            }
            // Entries write through a map proxy over the same native map.
            jsi::Object owner = rdmaCreateMapHandleCAbi(r, rdma_cloneHandle(st->handle), st->keyType, st->valueType, st->stateBacked);
            return rdmaSnapshotIterator(r, RdmaIterKind::Entries, t.getObject(r), rdmaMakeEntries(r, owner, flat));
        }
        bool keys = mode == MapViewStateCAbi::Keys;
        jsi::Array snap = snapshotMapPart(r, st->handle, st->stateBacked, keys, keys ? st->keyType : st->valueType);
        return rdmaSnapshotIterator(r, keys ? RdmaIterKind::Keys : RdmaIterKind::Values, t.getObject(r), std::move(snap));
    }});
    m.push_back({"contains", 1, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = viewOf(r, t);
        const jsi::Value& x = rdmaArg(a, n, 0);
        void* h = st->handle;
        if (mode != MapViewStateCAbi::Entries) {
            bool key = mode == MapViewStateCAbi::Keys;
            void* v = jsiToHandle(r, x, key ? st->keyType : st->valueType);
            return run(r, st->stateBacked, false, [key, h, v]() -> RdmaResult {
                bool has = key ? rdma_mapContainsKey(h, v) : rdma_mapContainsValue(h, v);
                dispose(v);
                return errorOr(RdmaResult::boolean(has));
            });
        }
        jsi::Value key, value;
        if (!entryParts(r, x, key, value)) return jsi::Value(false);
        // Present when the map maps the key to an equal value: compare on the JS side.
        jsi::Object owner = rdmaCreateMapHandleCAbi(r, rdma_cloneHandle(h), st->keyType, st->valueType, st->stateBacked);
        if (!owner.getPropertyAsFunction(r, "containsKey").callWithThis(r, owner, key).getBool()) return jsi::Value(false);
        jsi::Value cur = owner.getPropertyAsFunction(r, "get").callWithThis(r, owner, key);
        if (cur.isObject() && cur.getObject(r).hasProperty(r, "equals")) {
            jsi::Object co = cur.getObject(r);
            return co.getPropertyAsFunction(r, "equals").callWithThis(r, co, value);
        }
        return jsi::Value(jsi::Value::strictEquals(r, cur, value));
    }});
    m.push_back({"remove", 1, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        auto st = viewOf(r, t);
        const jsi::Value& x = rdmaArg(a, n, 0);
        void* h = st->handle;
        if (mode == MapViewStateCAbi::Entries) {
            jsi::Value key, value;
            if (!entryParts(r, x, key, value)) return jsi::Value(false);
            void* k = jsiToHandle(r, key, st->keyType);
            void* v = jsiToHandle(r, value, st->valueType);
            return run(r, st->stateBacked, true, [h, k, v]() -> RdmaResult {
                bool removed = rdma_mapRemoveKV(h, k, v);
                dispose(k);
                dispose(v);
                return errorOr(RdmaResult::boolean(removed));
            });
        }
        if (mode == MapViewStateCAbi::Keys) {
            void* k = jsiToHandle(r, x, st->keyType);
            return run(r, st->stateBacked, true, [h, k]() -> RdmaResult {
                bool had = rdma_mapContainsKey(h, k);
                void* removed = rdma_mapRemoveEntry(h, k);
                dispose(removed);
                dispose(k);
                return errorOr(RdmaResult::boolean(had));
            });
        }
        void* v = jsiToHandle(r, x, st->valueType);
        return run(r, st->stateBacked, true, [h, v]() -> RdmaResult {
            bool removed = rdma_mapRemoveValue(h, v);
            dispose(v);
            return errorOr(RdmaResult::boolean(removed));
        });
    }});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = viewOf(r, t);
        void* h = st->handle;
        return run(r, st->stateBacked, true, [h]() -> RdmaResult {
            rdma_mapClear(h);
            return errorOr(RdmaResult::undefined());
        });
    }});
    m.push_back({"containsAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        jsi::Object self = t.getObject(r);
        jsi::Function contains = self.getPropertyAsFunction(r, "contains");
        jsi::Object arg = a[0].getObject(r);
        jsi::Value toArray = arg.getProperty(r, "toArray");
        if (!toArray.isObject()) return jsi::Value(false);
        jsi::Array elems = toArray.getObject(r).getFunction(r).callWithThis(r, arg, nullptr, 0).getObject(r).getArray(r);
        for (size_t i = 0; i < elems.size(r); i++) {
            jsi::Value hit = contains.callWithThis(r, self, elems.getValueAtIndex(r, i));
            if (!hit.isBool() || !hit.getBool()) return jsi::Value(false);
        }
        return jsi::Value(true);
    }});
    auto unsupported = [](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
        throw jsi::JSError(r, "UnsupportedOperationException: a map view does not support adding elements");
    };
    m.push_back({"add", 1, unsupported});
    m.push_back({"addAll", 1, unsupported});
    m.push_back({"checkIsMutable", 0, [](jsi::Runtime&, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
        return jsi::Value::undefined();
    }});
    m.push_back({"toString", 0, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        auto st = viewOf(r, t);
        jsi::Function toStr = r.global().getPropertyAsFunction(r, "String");
        std::string out = "[";
        jsi::Array keys = snapshotMapPart(r, st->handle, st->stateBacked, true, st->keyType);
        jsi::Array values = snapshotMapPart(r, st->handle, st->stateBacked, false, st->valueType);
        size_t n = std::min(keys.size(r), values.size(r));
        for (size_t i = 0; i < n; i++) {
            if (i) out += ", ";
            if (mode != MapViewStateCAbi::Values) out += toStr.call(r, keys.getValueAtIndex(r, i)).getString(r).utf8(r);
            if (mode == MapViewStateCAbi::Entries) out += "=";
            if (mode != MapViewStateCAbi::Keys) out += toStr.call(r, values.getValueAtIndex(r, i)).getString(r).utf8(r);
        }
        out += "]";
        return jsi::String::createFromUtf8(r, out);
    }});
    return m;
}

int kindOf(jsi::Runtime& rt, const char* classifier, const jsi::Value& v) {
    jsi::Value rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    jsi::Value fn = rdma.getObject(rt).getProperty(rt, classifier);
    if (!fn.isObject() || !fn.getObject(rt).isFunction(rt)) return -1;
    jsi::Value res = fn.getObject(rt).getFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

} // namespace

std::vector<RdmaProtoMethod> rdmaPlatformProtoMethods(jsi::Runtime&, RdmaProtoKind kind) {
    // State-backed collections use the same functions: every operation consults the
    // proxy's `stateBacked` flag through rdmaDispatchStateOpCAbi.
    switch (kind) {
        case RdmaProtoKind::List:
        case RdmaProtoKind::StateList: return listMethods();
        case RdmaProtoKind::Map:
        case RdmaProtoKind::StateMap: return mapMethods();
        case RdmaProtoKind::KeysView: return viewMethods(MapViewStateCAbi::Keys);
        case RdmaProtoKind::ValuesView: return viewMethods(MapViewStateCAbi::Values);
        case RdmaProtoKind::EntriesView: return viewMethods(MapViewStateCAbi::Entries);
        default: return {};
    }
}

jsi::Value rdmaWrapTypedHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& type) {
    if (type == "kotlin.Unit") {
        dispose(handle);
        return jsi::Value::undefined();
    }
    if (!handle) return jsi::Value::null();
    if (isListDesc(type)) return rdmaCreateListHandleCAbi(rt, handle, type.substr(5));
    if (isMapDesc(type)) {
        auto kv = parseMapBody(type.substr(4));
        return rdmaCreateMapHandleCAbi(rt, handle, kv.first, kv.second);
    }
    return wrapAnyCAbi(rt, nullptr, handle);
}

jsi::Object rdmaCreateListHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& elementType, bool stateBacked) {
    return rdmaNewProxy(rt, stateBacked ? RdmaProtoKind::StateList : RdmaProtoKind::List,
        std::make_shared<ListStateCAbi>(handle, elementType, stateBacked));
}

jsi::Object rdmaCreateMapHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    return rdmaNewProxy(rt, stateBacked ? RdmaProtoKind::StateMap : RdmaProtoKind::Map,
        std::make_shared<MapStateCAbi>(handle, keyType, valueType, stateBacked));
}

void morphListCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& elementType, bool stateBacked) {
    rdmaAdoptProxy(rt, obj, stateBacked ? RdmaProtoKind::StateList : RdmaProtoKind::List,
        std::make_shared<ListStateCAbi>(handle, elementType, stateBacked));
}

void morphMapCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    rdmaAdoptProxy(rt, obj, stateBacked ? RdmaProtoKind::StateMap : RdmaProtoKind::Map,
        std::make_shared<MapStateCAbi>(handle, keyType, valueType, stateBacked));
}

void* rdmaMaterializeListCAbi(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& elementType) {
    if (void* h = rdmaListHandleOf(rt, jsi::Value(rt, jsObj))) return h;
    int kind = kindOf(rt, "listKind", jsi::Value(rt, jsObj));
    if (kind == 2) {
        throw jsi::JSError(rt, "unsupported List implementation crossing the runtime boundary; only ArrayList and EmptyList are supported");
    }
    if (kind == 1) return rdma_emptyList();
    void* fresh = jsiToHandle(rt, jsi::Value(rt, jsObj), "list:" + elementType);
    if (!fresh || jsObj.isArray(rt)) return fresh; // a raw JS array is copied, never adopted
    // The proxy owns `fresh`; the caller borrows it.
    morphListCAbi(rt, jsObj, fresh, elementType);
    return fresh;
}

void* rdmaMaterializeMapCAbi(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType) {
    if (void* h = rdmaMapHandleOf(rt, jsi::Value(rt, jsObj))) return h;
    int kind = kindOf(rt, "mapKind", jsi::Value(rt, jsObj));
    if (kind == 3) {
        throw jsi::JSError(rt, "unsupported Map implementation crossing the runtime boundary; only LinkedHashMap, HashMap and EmptyMap are supported");
    }
    if (kind == 2) return rdma_emptyMap();
    void* fresh = buildMapHandleFromJs(rt, jsObj, keyType, valueType);
    if (fresh) morphMapCAbi(rt, jsObj, fresh, keyType, valueType);
    return fresh;
}

} // namespace rdma
} // namespace facebook
