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

namespace facebook {
namespace rdma {

struct MapNativeState : public jsi::NativeState {
    JavaVM* jvm_;
    jobject globalMapRef_;
    std::string keyType_;
    std::string valueType_;
    // Mangled name of the original `checkIsMutable` method, discovered when a
    // plugin-created `HashMap` is morphed. Empty when the proxy wraps a
    // kernel-created map (the native map is the source of truth for mutability).
    std::string checkIsMutable_;

    MapNativeState(JavaVM* jvm, jobject globalRef, const std::string& keyType, const std::string& valueType)
        : jvm_(jvm), globalMapRef_(globalRef), keyType_(keyType), valueType_(valueType) {}

    ~MapNativeState() {
        if (globalMapRef_ != nullptr && jvm_ != nullptr) {
            JNIEnv* env = nullptr;
            jint res = jvm_->GetEnv((void**)&env, JNI_VERSION_1_6);
            bool attached = false;
            if (res == JNI_EDETACHED) {
                res = jvm_->AttachCurrentThread(&env, nullptr);
                if (res == JNI_OK) attached = true;
            }
            if (env) {
                env->DeleteGlobalRef(globalMapRef_);
            }
            if (attached) jvm_->DetachCurrentThread();
        }
    }
};

// Setup NativeState + the full plain `Map`/`MutableMap` API as HostFunctions +
// the `size` getter. `keyType`/`valueType` are recursive descriptors (a primitive
// or @RDMA FQN, `list:<elem>`, or `map:<key>:<value>`).
void populateMapHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalMapRef, const std::string& keyType, const std::string& valueType);

// Discover the storage methods of a Kotlin/JS `HashMap`/`LinkedHashMap` on its
// prototype and replace them on `obj` with wrappers that forward to the native
// map. Called only for plugin-created maps, after `populateMapHandle`.
void morphHashMap(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, const std::string& keyType, const std::string& valueType);

// Create a fresh MapHandle JSI object wrapping a JVM Map.
jsi::Object createMapHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalMapRef, const std::string& keyType, const std::string& valueType);

// Materialize a JS map → JVM HashMap/LinkedHashMap + mutate the JS object into a
// permanent MapHandle. Recursively materializes nested lists/maps using the
// key/value descriptors.
jobject materializeMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType);

} // namespace rdma
} // namespace facebook
