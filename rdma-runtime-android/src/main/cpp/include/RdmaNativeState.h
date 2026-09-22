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

namespace facebook {
namespace rdma {

// Common base for every generated `<X>NativeState` that backs an @RDMA object
// handle on the JSI side. It owns the global JVM ref and exposes it through a
// non-virtual inline accessor, so generated glue can extract the jobject without
// knowing the concrete class (and, critically, without any virtual-call overhead:
// `std::static_pointer_cast<RdmaObjectNativeState>` is a no-op under single
// inheritance, and `getObject()` is a direct member load).
class RdmaObjectNativeState : public jsi::NativeState {
public:
    RdmaObjectNativeState(JavaVM* jvm, jobject globalRef) : jvm_(jvm), globalRef_(globalRef) {}

    ~RdmaObjectNativeState() override {
        if (globalRef_ != nullptr && jvm_ != nullptr) {
            JNIEnv* env = nullptr;
            jint res = jvm_->GetEnv((void**)&env, JNI_VERSION_1_6);
            bool isAttached = false;
            if (res == JNI_EDETACHED) {
                res = jvm_->AttachCurrentThread(&env, nullptr);
                if (res == JNI_OK) isAttached = true;
            }
            if (env != nullptr) {
                env->DeleteGlobalRef(globalRef_);
            }
            if (isAttached) jvm_->DetachCurrentThread();
        }
    }

    jobject getObject() const { return globalRef_; }
    JavaVM* getJvm() const { return jvm_; }

private:
    JavaVM* jvm_;
    jobject globalRef_;
};

} // namespace rdma
} // namespace facebook
