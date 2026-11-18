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
#include <jni.h>
#include <string>
#include <vector>

#include "RdmaProxyCore.h"

namespace facebook {
namespace rdma {

namespace detail {
inline void deleteGlobalRefOn(JavaVM* jvm, jobject ref) {
    if (ref == nullptr || jvm == nullptr) return;
    JNIEnv* env = nullptr;
    jint res = jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool attached = false;
    if (res == JNI_EDETACHED) {
        res = jvm->AttachCurrentThread(&env, nullptr);
        if (res == JNI_OK) attached = true;
    }
    if (env) env->DeleteGlobalRef(ref);
    if (attached) jvm->DetachCurrentThread();
}
} // namespace detail

// NativeState of a map proxy (kernel-created or adopted plugin map). Owns one global
// reference to the native `java.util.Map`.
struct MapNativeState : public RdmaTaggedState {
    JavaVM* jvm_;
    jobject globalMapRef_;
    std::string keyType_;
    std::string valueType_;
    // True when the native map is a kernel `SnapshotStateMap` created by
    // `RDMA.mutableStateMapOf` (docs/design/type_descriptors.md#state-semantics).
    bool stateBacked_ = false;

    MapNativeState(JavaVM* jvm, jobject globalRef, const std::string& keyType, const std::string& valueType)
        : RdmaTaggedState(RdmaStateKind::Map), jvm_(jvm), globalMapRef_(globalRef), keyType_(keyType), valueType_(valueType) {}

    ~MapNativeState() override { detail::deleteGlobalRefOn(jvm_, globalMapRef_); }
};

// NativeState of a `keys` / `values` / `entries` view. Holds its own global reference
// to the map, so a view outlives the map proxy it came from.
struct MapViewNativeState : public RdmaTaggedState {
    enum Mode { Keys = 0, Values = 1, Entries = 2 };
    JavaVM* jvm_;
    jobject globalMapRef_;
    Mode mode_;
    std::string keyType_;
    std::string valueType_;
    bool stateBacked_ = false;

    MapViewNativeState(JavaVM* jvm, jobject globalRef, Mode mode, const std::string& keyType, const std::string& valueType)
        : RdmaTaggedState(RdmaStateKind::MapView), jvm_(jvm), globalMapRef_(globalRef), mode_(mode), keyType_(keyType), valueType_(valueType) {}

    ~MapViewNativeState() override { detail::deleteGlobalRefOn(jvm_, globalMapRef_); }
};

inline std::shared_ptr<MapNativeState> rdmaMapStateOf(jsi::Runtime& rt, const jsi::Value& v) {
    return rdmaStateAs<MapNativeState>(rt, v, RdmaStateKind::Map);
}

inline std::shared_ptr<MapNativeState> rdmaMapStateOf(jsi::Runtime& rt, const jsi::Object& o) {
    return rdmaStateAs<MapNativeState>(rt, o, RdmaStateKind::Map);
}

// The native map behind a map proxy (a BORROWED global reference owned by the proxy),
// or null when `v` is not a map proxy.
inline jobject rdmaMapJObjectOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto s = rdmaMapStateOf(rt, v);
    return s ? s->globalMapRef_ : nullptr;
}

// Turns `obj` into a proxy over `globalMapRef` in place (identity preserved): used for
// plugin-created maps on their first crossing. Takes ownership of the global ref.
void populateMapHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked = false);

// Legacy entry kept for source compatibility; the prototype swap in
// `populateMapHandle` already installs every storage method.
void morphHashMap(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, const std::string& keyType, const std::string& valueType);

// A fresh map proxy over a kernel map (or a kernel `SnapshotStateMap` when
// `stateBacked`). Takes ownership of `globalMapRef`.
jsi::Object createMapHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked = false);

// The native map for a JS map value: the kernel map behind a proxy, or a fresh native
// `LinkedHashMap` materialized from a plugin-created Kotlin map (which is then adopted
// as a proxy in place). Nested lists/maps materialize recursively.
//
// Ownership: returns a NEW LOCAL reference owned by the caller. The proxy keeps its
// own global reference.
jobject materializeMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

} // namespace rdma
} // namespace facebook
