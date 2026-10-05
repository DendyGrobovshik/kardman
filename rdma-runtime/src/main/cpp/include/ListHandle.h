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

struct ListNativeState : public jsi::NativeState {
    JavaVM* jvm_;
    jobject globalListRef_;
    std::string elementType_;
    
    ListNativeState(JavaVM* jvm, jobject globalRef, const std::string& elemType)
        : jvm_(jvm), globalListRef_(globalRef), elementType_(elemType) {}
    
    ~ListNativeState() {
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

// Setup NativeState + get/size HostFunctions + defineProperty getter on existing object
void populateListHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalListRef, const std::string& elementType);

// Create a fresh ListHandle JSI object wrapping a JVM ArrayList
jsi::Object createListHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalListRef, const std::string& elementType);

// Materialize JS Array → JVM ArrayList + mutate JS object into permanent ListHandle
jobject materializeArray(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& elementType);

} // namespace rdma
} // namespace facebook
