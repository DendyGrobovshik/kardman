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
#include <jsi/jsi.h>
#include <cstdint>
#include <string>
#include "RdmaCAbiRegistry.h"
#include "RdmaProxyCore.h"
#include "RdmaRendezvous.h"
#include <functional>

// Runtime-provided C ABI (implemented in the Kotlin/Native runtime shim and
// registered via rdma_registerFunction). These inline wrappers call through the
// registry so call sites stay unchanged.
inline void rdma_disposeStableRef(void* handle) {
    static auto fn = (void (*)(void*))rdma_lookupFunction("rdma_disposeStableRef");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*))rdma_lookupFunction("rdma_disposeStableRef");
    fn(handle);
}
// List access (a List<T> crosses as a StableRef handle).
inline int32_t rdma_listSize(void* listHandle) {
    static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_listSize");
    if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_listSize");
    return fn(listHandle);
}
inline void* rdma_listGet(void* listHandle, int32_t index) {
    static auto fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_listGet");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_listGet");
    return fn(listHandle, index);
}
inline void* rdma_listSet(void* listHandle, int32_t index, void* valueHandle) {
    static auto fn = (void* (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listSet");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listSet");
    return fn(listHandle, index, valueHandle);
}
inline void rdma_listAdd(void* listHandle, void* valueHandle) {
    static auto fn = (void (*)(void*, void*))rdma_lookupFunction("rdma_listAdd");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*, void*))rdma_lookupFunction("rdma_listAdd");
    fn(listHandle, valueHandle);
}
inline void* rdma_listRemoveAt(void* listHandle, int32_t index) {
    static auto fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_listRemoveAt");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_listRemoveAt");
    return fn(listHandle, index);
}
inline void rdma_listClear(void* listHandle) {
    static auto fn = (void (*)(void*))rdma_lookupFunction("rdma_listClear");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*))rdma_lookupFunction("rdma_listClear");
    fn(listHandle);
}
inline void rdma_listAddAt(void* listHandle, int32_t index, void* valueHandle) {
    static auto fn = (void (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAt");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAt");
    fn(listHandle, index, valueHandle);
}
inline bool rdma_listRemoveValue(void* listHandle, void* valueHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveValue");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveValue");
    return fn(listHandle, valueHandle);
}
inline void rdma_listRemoveRange(void* listHandle, int32_t from, int32_t to) {
    static auto fn = (void (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listRemoveRange");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listRemoveRange");
    fn(listHandle, from, to);
}
inline bool rdma_listContains(void* listHandle, void* valueHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listContains");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listContains");
    return fn(listHandle, valueHandle);
}
inline int32_t rdma_listIndexOf(void* listHandle, void* valueHandle) {
    static auto fn = (int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listIndexOf");
    if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listIndexOf");
    return fn(listHandle, valueHandle);
}
inline int32_t rdma_listLastIndexOf(void* listHandle, void* valueHandle) {
    static auto fn = (int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listLastIndexOf");
    if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listLastIndexOf");
    return fn(listHandle, valueHandle);
}
inline bool rdma_listIsEmpty(void* listHandle) {
    static auto fn = (bool (*)(void*))rdma_lookupFunction("rdma_listIsEmpty");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*))rdma_lookupFunction("rdma_listIsEmpty");
    return fn(listHandle);
}
inline void* rdma_emptyList() {
    static auto fn = (void* (*)())rdma_lookupFunction("rdma_emptyList");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)())rdma_lookupFunction("rdma_emptyList");
    return fn();
}
// Creates a Kotlin List<Any?> from an array of handles (for materializing JS
// arrays into Kotlin Lists). Caller transfers ownership of the returned handle.
// CONSUMES the element handles (reads and disposes each one). The Kotlin side
// reads the handle array as `CPointer<LongVar>`; `void* const*`
// and `int64_t*` are representation-identical here.
inline void* rdma_listCreate(void* const* handles, int32_t count) {
    static auto fn = (void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_listCreate");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_listCreate");
    return fn(handles, count);
}
inline void* rdma_listSubList(void* listHandle, int32_t from, int32_t to) {
    static auto fn = (void* (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listSubList");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listSubList");
    return fn(listHandle, from, to);
}
inline const char* rdma_listToString(void* listHandle) {
    static auto fn = (const char* (*)(void*))rdma_lookupFunction("rdma_listToString");
    if (__builtin_expect(fn == nullptr, 0)) fn = (const char* (*)(void*))rdma_lookupFunction("rdma_listToString");
    return fn(listHandle);
}
inline bool rdma_listEquals(void* a, void* b) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listEquals");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listEquals");
    return fn(a, b);
}
inline int32_t rdma_listHashCode(void* listHandle) {
    static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_listHashCode");
    if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_listHashCode");
    return fn(listHandle);
}
inline bool rdma_listAddAll(void* listHandle, void* otherHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listAddAll");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listAddAll");
    return fn(listHandle, otherHandle);
}
inline bool rdma_listAddAllAt(void* listHandle, int32_t index, void* otherHandle) {
    static auto fn = (bool (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAllAt");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAllAt");
    return fn(listHandle, index, otherHandle);
}
inline bool rdma_listRemoveAll(void* listHandle, void* otherHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveAll");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveAll");
    return fn(listHandle, otherHandle);
}
inline bool rdma_listRetainAll(void* listHandle, void* otherHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRetainAll");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listRetainAll");
    return fn(listHandle, otherHandle);
}
inline bool rdma_listContainsAll(void* listHandle, void* otherHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listContainsAll");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_listContainsAll");
    return fn(listHandle, otherHandle);
}
// Creates a Kotlin Array<Any?> from an array of handles (for DisposableEffect keys).
inline void* rdma_arrayCreate(void* const* handles, int32_t count) {
    static auto fn = (void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_arrayCreate");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_arrayCreate");
    return fn(handles, count);
}
// Map access (a Map<K,V> crosses as a StableRef handle).
inline int32_t rdma_mapSize(void* mapHandle) {
    static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_mapSize");
    if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_mapSize");
    return fn(mapHandle);
}
inline void* rdma_mapGet(void* mapHandle, void* keyHandle) {
    static auto fn = (void* (*)(void*, void*))rdma_lookupFunction("rdma_mapGet");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, void*))rdma_lookupFunction("rdma_mapGet");
    return fn(mapHandle, keyHandle);
}
inline void* rdma_mapPut(void* mapHandle, void* keyHandle, void* valueHandle) {
    static auto fn = (void* (*)(void*, void*, void*))rdma_lookupFunction("rdma_mapPut");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, void*, void*))rdma_lookupFunction("rdma_mapPut");
    return fn(mapHandle, keyHandle, valueHandle);
}
inline bool rdma_mapRemove(void* mapHandle, void* keyHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemove");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemove");
    return fn(mapHandle, keyHandle);
}
// Kotlin `MutableMap.remove(key)` returns the removed value (`V?`), not a boolean.
inline void* rdma_mapRemoveEntry(void* mapHandle, void* keyHandle) {
    static auto fn = (void* (*)(void*, void*))rdma_lookupFunction("rdma_mapRemoveEntry");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, void*))rdma_lookupFunction("rdma_mapRemoveEntry");
    return fn(mapHandle, keyHandle);
}
inline void rdma_mapClear(void* mapHandle) {
    static auto fn = (void (*)(void*))rdma_lookupFunction("rdma_mapClear");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*))rdma_lookupFunction("rdma_mapClear");
    fn(mapHandle);
}
inline bool rdma_mapContainsKey(void* mapHandle, void* keyHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapContainsKey");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapContainsKey");
    return fn(mapHandle, keyHandle);
}
inline bool rdma_mapContainsValue(void* mapHandle, void* valueHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapContainsValue");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapContainsValue");
    return fn(mapHandle, valueHandle);
}
inline bool rdma_mapIsEmpty(void* mapHandle) {
    static auto fn = (bool (*)(void*))rdma_lookupFunction("rdma_mapIsEmpty");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*))rdma_lookupFunction("rdma_mapIsEmpty");
    return fn(mapHandle);
}
inline void* rdma_mapKeyAt(void* mapHandle, int32_t index) {
    static auto fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapKeyAt");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapKeyAt");
    return fn(mapHandle, index);
}
inline void* rdma_mapValueAt(void* mapHandle, int32_t index) {
    static auto fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapValueAt");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapValueAt");
    return fn(mapHandle, index);
}
inline bool rdma_mapRemoveValue(void* mapHandle, void* valueHandle) {
    static auto fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemoveValue");
    if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemoveValue");
    return fn(mapHandle, valueHandle);
}
inline void* rdma_mapKeysToList(void* mapHandle) {
    static const auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_mapKeysToList");
    return fn(mapHandle);
}
inline void* rdma_mapValuesToList(void* mapHandle) {
    static const auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_mapValuesToList");
    return fn(mapHandle);
}
inline bool rdma_mapRemoveKV(void* mapHandle, void* keyHandle, void* valueHandle) {
    static const auto fn = (bool (*)(void*, void*, void*))rdma_lookupFunction("rdma_mapRemoveKV");
    return fn(mapHandle, keyHandle, valueHandle);
}
// Releases the C strings handed to C++ on the calling thread (see CAbiShim.kt
// `rdmaStringToCStr`): called after every top-level JS task / UI task.
inline void rdma_clearStringArena() {
    static auto fn = (void (*)())rdma_lookupFunction("rdma_clearStringArena");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_clearStringArena");
    if (fn) fn();
}
inline void* rdma_emptyMap() {
    static auto fn = (void* (*)())rdma_lookupFunction("rdma_emptyMap");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)())rdma_lookupFunction("rdma_emptyMap");
    return fn();
}
// Creates a Kotlin Map<Any?, Any?> from parallel key/value handle arrays (for
// materializing a JS map). Caller transfers ownership of the returned handle.
// CONSUMES the key/value handles (reads and disposes each one).
inline void* rdma_mapCreate(void* const* keys, void* const* values, int32_t count) {
    static auto fn = (void* (*)(void* const*, void* const*, int32_t))rdma_lookupFunction("rdma_mapCreate");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void* const*, void* const*, int32_t))rdma_lookupFunction("rdma_mapCreate");
    return fn(keys, values, count);
}

namespace facebook {
namespace rdma {

// Associates a suspend-completion id with the return type descriptor of the
// suspend function being invoked. Called by the generated `RDMA._<name>Async`
// host function before it starts the kernel suspend body. The descriptor is a
// leaf FQN, `list:<elem>`, or `map:<key>:<value>`. Defined in RdmaComposeCAbi.cpp.
void rdmaRegisterSuspendResultType(int64_t completionId, const std::string& typeDesc);

// C-ABI variant of RdmaObjectNativeState: owns a StableRef handle (void*) and
// disposes it when the JSI object is collected. The generated per-class
// NativeState subclasses this instead of the JNI RdmaObjectNativeState.
class RdmaObjectNativeStateCAbi : public RdmaTaggedState {
public:
    explicit RdmaObjectNativeStateCAbi(void* handle) : RdmaTaggedState(RdmaStateKind::Object), handle_(handle) {}
    ~RdmaObjectNativeStateCAbi() override {
        if (handle_) rdma_disposeStableRef(handle_);
    }
    void* handle() const { return handle_; }

private:
    void* handle_;
};

// NativeState of a list proxy (kernel-created or adopted plugin list) over a Kotlin
// `List` held by a StableRef (docs/design/runtime_rework.md §3.4). `stateBacked`
// marks a kernel `SnapshotStateList` (`mutableStateListOf`): its operations obey the
// state thread contract (docs/design/type_descriptors.md#state-semantics).
struct ListStateCAbi : public RdmaTaggedState {
    void* handle;
    std::string elementType;
    bool stateBacked;
    ListStateCAbi(void* h, const std::string& elem, bool sb)
        : RdmaTaggedState(RdmaStateKind::List), handle(h), elementType(elem), stateBacked(sb) {}
    ~ListStateCAbi() override { if (handle) rdma_disposeStableRef(handle); }
};

// NativeState of a map proxy over a Kotlin `Map` held by a StableRef.
struct MapStateCAbi : public RdmaTaggedState {
    void* handle;
    std::string keyType;
    std::string valueType;
    bool stateBacked;
    MapStateCAbi(void* h, const std::string& k, const std::string& v, bool sb)
        : RdmaTaggedState(RdmaStateKind::Map), handle(h), keyType(k), valueType(v), stateBacked(sb) {}
    ~MapStateCAbi() override { if (handle) rdma_disposeStableRef(handle); }
};

// NativeState of a `keys` / `values` / `entries` view. Owns its own handle to the map.
struct MapViewStateCAbi : public RdmaTaggedState {
    enum Mode { Keys = 0, Values = 1, Entries = 2 };
    void* handle;
    Mode mode;
    std::string keyType;
    std::string valueType;
    bool stateBacked;
    MapViewStateCAbi(void* h, Mode m, const std::string& k, const std::string& v, bool sb)
        : RdmaTaggedState(RdmaStateKind::MapView), handle(h), mode(m), keyType(k), valueType(v), stateBacked(sb) {}
    ~MapViewStateCAbi() override { if (handle) rdma_disposeStableRef(handle); }
};

// The Kotlin list/map behind a proxy (a BORROWED handle owned by the proxy), or null.
inline void* rdmaListHandleOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto s = rdmaStateAs<ListStateCAbi>(rt, v, RdmaStateKind::List);
    return s ? s->handle : nullptr;
}
inline void* rdmaMapHandleOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto s = rdmaStateAs<MapStateCAbi>(rt, v, RdmaStateKind::Map);
    return s ? s->handle : nullptr;
}

// Converts a JSI value into a Kotlin value handle by descriptor. Nested lists/maps
// and @RDMA objects reuse their native handles (cloned). Returns a NEW handle the
// caller owns (null for null/undefined). Implemented in RdmaComposeCAbi.cpp.
void* jsiToHandle(jsi::Runtime& rt, const jsi::Value& v, const std::string& elementType);

// Wraps a Kotlin value handle into a JS value by descriptor. CONSUMES `handle`.
// Implemented in RdmaCollectionsCAbi.cpp.
jsi::Value rdmaWrapTypedHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& type);

// Fresh proxies over kernel collections. Take ownership of `handle`.
// Implemented in RdmaCollectionsCAbi.cpp.
jsi::Object rdmaCreateListHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& elementType, bool stateBacked = false);
jsi::Object rdmaCreateMapHandleCAbi(jsi::Runtime& rt, void* handle, const std::string& keyType, const std::string& valueType, bool stateBacked = false);

// Adopts a plugin-created Kotlin/JS list/map as a proxy over `handle` in place
// (identity preserved). Takes ownership of `handle`. Implemented in RdmaCollectionsCAbi.cpp.
void morphListCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& elementType, bool stateBacked = false);
void morphMapCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& keyType, const std::string& valueType, bool stateBacked = false);

// The Kotlin list/map for a JS collection value: the handle behind a proxy, or a fresh
// Kotlin collection materialized from a plugin collection (then adopted as a proxy).
// Returns a BORROWED handle owned by the proxy, except for `emptyList`/`emptyMap` and
// raw JS arrays (fresh handles). Implemented in RdmaCollectionsCAbi.cpp.
void* rdmaMaterializeListCAbi(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& elementType);
void* rdmaMaterializeMapCAbi(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

// Materializes a JS map into a NEW native Map handle without classification or
// adoption. Implemented in RdmaComposeCAbi.cpp.
void* buildMapHandleFromJs(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

// State-thread dispatch of a state-backed collection op (see RdmaComposeCAbi.cpp).
RdmaResult rdmaDispatchStateOpCAbi(bool stateBacked, bool write, std::function<RdmaResult()> op);

} // namespace rdma
} // namespace facebook
