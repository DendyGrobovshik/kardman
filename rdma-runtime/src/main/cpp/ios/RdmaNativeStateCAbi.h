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
// Creates a Kotlin Array<Any?> from an array of handles (for DisposableEffect keys).
inline void* rdma_arrayCreate(void* const* handles, int32_t count) {
    return ((void* (*)(void* const*, int32_t))rdma_lookupFunction("rdma_arrayCreate"))(handles, count);
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

} // namespace rdma
} // namespace facebook
