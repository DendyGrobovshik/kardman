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

// NativeState of a list proxy (kernel-created or adopted plugin list). Owns one
// global reference to the native `java.util.List`.
struct ListNativeState : public RdmaTaggedState {
    JavaVM* jvm_;
    jobject globalListRef_;
    // Recursive element descriptor: a primitive/@RDMA FQN, `list:<elem>` or `map:<k>:<v>`.
    std::string elementType_;
    // True for a kernel `SnapshotStateList` created by `RDMA.mutableStateListOf`:
    // the proxy then uses the state-backed prototype, whose operations register the
    // composition read first (docs/design/type_descriptors.md#state-semantics).
    bool stateBacked_ = false;

    ListNativeState(JavaVM* jvm, jobject globalRef, const std::string& elemType)
        : RdmaTaggedState(RdmaStateKind::List), jvm_(jvm), globalListRef_(globalRef), elementType_(elemType) {}

    ~ListNativeState() override {
        if (globalListRef_ != nullptr && jvm_ != nullptr) {
            JNIEnv* env = nullptr;
            jint res = jvm_->GetEnv((void**)&env, JNI_VERSION_1_6);
            bool attached = false;
            if (res == JNI_EDETACHED) {
                res = jvm_->AttachCurrentThread(&env, nullptr);
                if (res == JNI_OK) attached = true;
            }
            if (env) {
                env->DeleteGlobalRef(globalListRef_);
            }
            if (attached) jvm_->DetachCurrentThread();
        }
    }
};

inline std::shared_ptr<ListNativeState> rdmaListStateOf(jsi::Runtime& rt, const jsi::Value& v) {
    return rdmaStateAs<ListNativeState>(rt, v, RdmaStateKind::List);
}

inline std::shared_ptr<ListNativeState> rdmaListStateOf(jsi::Runtime& rt, const jsi::Object& o) {
    return rdmaStateAs<ListNativeState>(rt, o, RdmaStateKind::List);
}

// The native list behind a list proxy (a BORROWED global reference owned by the
// proxy), or null when `v` is not a list proxy.
inline jobject rdmaListJObjectOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto s = rdmaListStateOf(rt, v);
    return s ? s->globalListRef_ : nullptr;
}

// Turns `obj` into a proxy over `globalListRef` in place (identity preserved): used
// for plugin-created lists on their first crossing. Takes ownership of the global ref.
void populateListHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalListRef, const std::string& elementType, bool stateBacked = false);

// Host functions of the list prototypes (plain or state-backed); consumed by the
// JNI implementation of `rdmaPlatformProtoMethods`.
std::vector<RdmaProtoMethod> rdmaJniListProtoMethods(bool stateBacked);

// Legacy entry kept for source compatibility of generated code; the prototype swap
// in `populateListHandle` already installs every storage method.
void morphArrayList(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, const std::string& elementType);

// A fresh list proxy over a kernel list (or a kernel `SnapshotStateList` when
// `stateBacked`). Takes ownership of `globalListRef`.
jsi::Object createListHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalListRef, const std::string& elementType, bool stateBacked = false);

// The native list for a JS list value: the kernel list behind a proxy, or a fresh
// native `ArrayList` materialized from a plugin-created Kotlin list (which is then
// adopted as a proxy in place). Nested lists/maps materialize recursively.
//
// Ownership: returns a NEW LOCAL reference owned by the caller (null for `null`).
// The proxy keeps its own global reference.
jobject materializeArray(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& elementType);

// Descriptor-driven element conversion shared by the list and map proxies.
// JS -> JVM: returns a NEW LOCAL reference (null for null/undefined); raises a
// cross-runtime type error when the value does not fit the descriptor.
jobject rdmaJsToJniTyped(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Value& v, const std::string& desc);
// JVM -> JS: CONSUMES the local reference `local` (released on every path).
jsi::Value rdmaJniToJsTyped(jsi::Runtime& rt, JavaVM* jvm, jobject local, const std::string& desc);

// A temporary native `java.util.ArrayList` (local reference) holding the elements of
// any JS-side collection argument (`addAll`, `containsAll`, …): the native list behind
// a proxy, or a copy of a plugin collection's `toArray()` — the argument itself is NOT
// adopted (no side effect on the caller's collection).
jobject rdmaTempJniCollection(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Value& v, const std::string& elemDesc);

} // namespace rdma
} // namespace facebook
