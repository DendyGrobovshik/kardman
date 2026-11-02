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

// Runtime-provided C ABI (implemented in the Kotlin/Native runtime shim and
// registered via rdma_registerFunction). These inline wrappers call through the
// registry so call sites stay unchanged.
inline void rdma_disposeStableRef(void* handle) {
    ((void (*)(void*))rdma_lookupFunction("rdma_disposeStableRef"))(handle);
}
// List access (a List<T> crosses as a StableRef handle).
inline int32_t rdma_listSize(void* listHandle) {
    return ((int32_t (*)(void*))rdma_lookupFunction("rdma_listSize"))(listHandle);
}
inline void* rdma_listGet(void* listHandle, int32_t index) {
    return ((void* (*)(void*, int32_t))rdma_lookupFunction("rdma_listGet"))(listHandle, index);
}
inline void rdma_listSet(void* listHandle, int32_t index, void* valueHandle) {
    ((void (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listSet"))(listHandle, index, valueHandle);
}
inline void rdma_listAdd(void* listHandle, void* valueHandle) {
    ((void (*)(void*, void*))rdma_lookupFunction("rdma_listAdd"))(listHandle, valueHandle);
}
inline void rdma_listRemoveAt(void* listHandle, int32_t index) {
    ((void (*)(void*, int32_t))rdma_lookupFunction("rdma_listRemoveAt"))(listHandle, index);
}
inline void rdma_listClear(void* listHandle) {
    ((void (*)(void*))rdma_lookupFunction("rdma_listClear"))(listHandle);
}
inline void rdma_listAddAt(void* listHandle, int32_t index, void* valueHandle) {
    ((void (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAt"))(listHandle, index, valueHandle);
}
inline bool rdma_listRemoveValue(void* listHandle, void* valueHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveValue"))(listHandle, valueHandle);
}
inline void rdma_listRemoveRange(void* listHandle, int32_t from, int32_t to) {
    ((void (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listRemoveRange"))(listHandle, from, to);
}
inline bool rdma_listContains(void* listHandle, void* valueHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listContains"))(listHandle, valueHandle);
}
inline int32_t rdma_listIndexOf(void* listHandle, void* valueHandle) {
    return ((int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listIndexOf"))(listHandle, valueHandle);
}
inline int32_t rdma_listLastIndexOf(void* listHandle, void* valueHandle) {
    return ((int32_t (*)(void*, void*))rdma_lookupFunction("rdma_listLastIndexOf"))(listHandle, valueHandle);
}
inline bool rdma_listIsEmpty(void* listHandle) {
    return ((bool (*)(void*))rdma_lookupFunction("rdma_listIsEmpty"))(listHandle);
}
inline void* rdma_emptyList() {
    return ((void* (*)())rdma_lookupFunction("rdma_emptyList"))();
}
// Creates a Kotlin List<Any?> from an array of handles (for materializing JS
// arrays into Kotlin Lists). Caller transfers ownership of the returned handle.
// The Kotlin side reads the handle array as `CPointer<LongVar>`; `void* const*`
// and `int64_t*` are representation-identical here.
inline void* rdma_listCreate(void* const* handles, int32_t count) {
    return ((void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_listCreate"))(handles, count);
}
inline void* rdma_listSubList(void* listHandle, int32_t from, int32_t to) {
    return ((void* (*)(void*, int32_t, int32_t))rdma_lookupFunction("rdma_listSubList"))(listHandle, from, to);
}
inline const char* rdma_listToString(void* listHandle) {
    return ((const char* (*)(void*))rdma_lookupFunction("rdma_listToString"))(listHandle);
}
inline bool rdma_listEquals(void* a, void* b) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listEquals"))(a, b);
}
inline int32_t rdma_listHashCode(void* listHandle) {
    return ((int32_t (*)(void*))rdma_lookupFunction("rdma_listHashCode"))(listHandle);
}
inline bool rdma_listAddAll(void* listHandle, void* otherHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listAddAll"))(listHandle, otherHandle);
}
inline bool rdma_listAddAllAt(void* listHandle, int32_t index, void* otherHandle) {
    return ((bool (*)(void*, int32_t, void*))rdma_lookupFunction("rdma_listAddAllAt"))(listHandle, index, otherHandle);
}
inline bool rdma_listRemoveAll(void* listHandle, void* otherHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listRemoveAll"))(listHandle, otherHandle);
}
inline bool rdma_listRetainAll(void* listHandle, void* otherHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_listRetainAll"))(listHandle, otherHandle);
}
// Creates a Kotlin Array<Any?> from an array of handles (for DisposableEffect keys).
inline void* rdma_arrayCreate(void* const* handles, int32_t count) {
    return ((void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_arrayCreate"))(handles, count);
}
// Map access (a Map<K,V> crosses as a StableRef handle).
inline int32_t rdma_mapSize(void* mapHandle) {
    return ((int32_t (*)(void*))rdma_lookupFunction("rdma_mapSize"))(mapHandle);
}
inline void* rdma_mapGet(void* mapHandle, void* keyHandle) {
    return ((void* (*)(void*, void*))rdma_lookupFunction("rdma_mapGet"))(mapHandle, keyHandle);
}
inline void rdma_mapPut(void* mapHandle, void* keyHandle, void* valueHandle) {
    ((void (*)(void*, void*, void*))rdma_lookupFunction("rdma_mapPut"))(mapHandle, keyHandle, valueHandle);
}
inline bool rdma_mapRemove(void* mapHandle, void* keyHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemove"))(mapHandle, keyHandle);
}
inline void rdma_mapClear(void* mapHandle) {
    ((void (*)(void*))rdma_lookupFunction("rdma_mapClear"))(mapHandle);
}
inline bool rdma_mapContainsKey(void* mapHandle, void* keyHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_mapContainsKey"))(mapHandle, keyHandle);
}
inline bool rdma_mapIsEmpty(void* mapHandle) {
    return ((bool (*)(void*))rdma_lookupFunction("rdma_mapIsEmpty"))(mapHandle);
}
inline void* rdma_mapKeyAt(void* mapHandle, int32_t index) {
    return ((void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapKeyAt"))(mapHandle, index);
}
inline void* rdma_mapValueAt(void* mapHandle, int32_t index) {
    return ((void* (*)(void*, int32_t))rdma_lookupFunction("rdma_mapValueAt"))(mapHandle, index);
}
inline bool rdma_mapRemoveValue(void* mapHandle, void* valueHandle) {
    return ((bool (*)(void*, void*))rdma_lookupFunction("rdma_mapRemoveValue"))(mapHandle, valueHandle);
}
inline void* rdma_emptyMap() {
    return ((void* (*)())rdma_lookupFunction("rdma_emptyMap"))();
}
// Creates a Kotlin Map<Any?, Any?> from parallel key/value handle arrays (for
// materializing a JS map). Caller transfers ownership of the returned handle.
inline void* rdma_mapCreate(void* const* keys, void* const* values, int32_t count) {
    return ((void* (*)(void* const*, void* const*, int32_t))rdma_lookupFunction("rdma_mapCreate"))(keys, values, count);
}

namespace facebook {
namespace rdma {

// C-ABI variant of RdmaObjectNativeState: owns a StableRef handle (void*) and
// disposes it when the JSI object is collected. The generated per-class
// NativeState subclasses this instead of the JNI RdmaObjectNativeState.
class RdmaObjectNativeStateCAbi : public jsi::NativeState {
public:
    explicit RdmaObjectNativeStateCAbi(void* handle) : handle_(handle) {}
    ~RdmaObjectNativeStateCAbi() override {
        if (handle_) rdma_disposeStableRef(handle_);
    }
    void* handle() const { return handle_; }

private:
    void* handle_;
};

// A JSI host object wrapping a Kotlin List handle (a `List<T>` crosses as a
// StableRef). Exposes `get`/`sizeImpl` and defines a `size` getter, mirroring the
// JNI `ListNativeState`/`populateListHandle`. Implemented in RdmaComposeCAbi.cpp.
class ListCAbiHost : public jsi::HostObject {
public:
    ListCAbiHost(void* listHandle, const std::string& elementType);
    ~ListCAbiHost() override;
    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override;
    void* listHandle() const { return listHandle_; }
    const std::string& elementType() const { return elementType_; }

private:
    void* listHandle_;
    std::string elementType_;
};

// Converts a JSI value into a Kotlin value handle for list materialization. Uses
// the element type to box primitives correctly; @RDMA objects and nested lists
// reuse their native handles. Returns nullptr for null/undefined. Implemented in
// RdmaComposeCAbi.cpp.
void* jsiToHandle(jsi::Runtime& rt, const jsi::Value& v, const std::string& elementType);

// Morphs a plugin-created Kotlin/JS `ArrayList` into a proxy list backed by the
// C ABI handle. Implemented in RdmaComposeCAbi.cpp.
void morphListCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& elementType);

// A JSI host object wrapping a Kotlin Map handle (a `Map<K,V>` crosses as a
// StableRef). Exposes the full MutableMap surface + `keys`/`values`/`entries`
// live views. Implemented in RdmaComposeCAbi.cpp.
class MapCAbiHost : public jsi::HostObject {
public:
    MapCAbiHost(void* mapHandle, const std::string& keyType, const std::string& valueType);
    ~MapCAbiHost() override;
    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override;
    void* mapHandle() const { return mapHandle_; }
    const std::string& keyType() const { return keyType_; }
    const std::string& valueType() const { return valueType_; }

private:
    void* mapHandle_;
    std::string keyType_;
    std::string valueType_;
};

// Materializes a JS map (plugin-created Kotlin/JS Map or JS object) into a Kotlin
// Map handle and morphs the JS object into a proxy map. Implemented in
// RdmaComposeCAbi.cpp.
void* materializeMap(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

// Morphs a plugin-created Kotlin/JS `Map` into a proxy map backed by the C ABI
// handle. Implemented in RdmaComposeCAbi.cpp.
void morphMapCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& keyType, const std::string& valueType);

// Materializes a JS map into a native Map handle without kind classification or
// morphing. Shared by materializeMap (generated) and jsiToHandle's nested-map
// branch. Implemented in RdmaComposeCAbi.cpp.
void* buildMapHandleFromJs(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

// Live view over a native Map (`keys`/`values`). `mode` 0 = keys, 1 = values.
// The view holds a non-owning reference to the map handle (the owning MapCAbiHost
// outlives any view it hands out). Implemented in RdmaComposeCAbi.cpp.
class MapViewHost : public jsi::HostObject {
public:
    MapViewHost(void* mapHandle, int mode, const std::string& elementType, const std::string& unused);
    ~MapViewHost() override;
    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override;

private:
    void* mapHandle_;
    int mode_;
    std::string elementType_;
};

// Live `entries` view over a native Map. Implemented in RdmaComposeCAbi.cpp.
class MapEntrySetHost : public jsi::HostObject {
public:
    MapEntrySetHost(void* mapHandle, const std::string& keyType, const std::string& valueType);
    ~MapEntrySetHost() override;
    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override;

private:
    void* mapHandle_;
    std::string keyType_;
    std::string valueType_;
};

} // namespace rdma
} // namespace facebook
