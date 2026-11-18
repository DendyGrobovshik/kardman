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

#include <jni.h>

namespace facebook {
namespace rdma {

// Scopes every JNI local reference created inside a host function. The Hermes
// thread is a native-attached thread with no Java frame that would pop locals, so
// without a frame each crossing leaks its locals until the thread detaches
// (docs/design/runtime_rework.md §3.9). Exception-safe: the frame is popped when a
// JS exception unwinds through the host function.
class JniLocalFrame {
public:
    explicit JniLocalFrame(JNIEnv* env, jint capacity = 16) : env_(env) {
        pushed_ = env_ && env_->PushLocalFrame(capacity) == 0;
    }
    ~JniLocalFrame() {
        if (pushed_) env_->PopLocalFrame(nullptr);
    }
    JniLocalFrame(const JniLocalFrame&) = delete;
    JniLocalFrame& operator=(const JniLocalFrame&) = delete;

private:
    JNIEnv* env_;
    bool pushed_ = false;
};

} // namespace rdma
} // namespace facebook
