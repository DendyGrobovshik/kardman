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
#include <cstdint>

#if defined(__ANDROID__)
#include <unistd.h>
#include <jni.h>
#endif
#if defined(__APPLE__)
#include <pthread.h>
#endif

namespace facebook {
namespace rdma {

// Host runtime context: the JVM on Android, unused elsewhere. Passed through the
// neutral core to the per-platform backend, which casts it to the concrete type.
#if defined(__ANDROID__)
using HostContext = JavaVM*;
#else
using HostContext = void*;
#endif

// Thread id used only for debug asserts. On Android this is the Linux TID; on
// Apple platforms `pthread_self()` (an opaque pointer) is used instead.
inline int64_t rdmaGetTid() {
#if defined(__ANDROID__)
    return static_cast<int64_t>(gettid());
#else
    return static_cast<int64_t>(reinterpret_cast<uintptr_t>(pthread_self()));
#endif
}

// Attaches the calling thread to the host runtime (JVM attach on Android, no-op
// elsewhere). Returns true on success.
inline bool rdmaHostAttachThread(HostContext ctx) {
#if defined(__ANDROID__)
    JNIEnv* env = nullptr;
    if (ctx->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return true;
    if (ctx->AttachCurrentThread(&env, nullptr) == JNI_OK) return true;
    return false;
#else
    (void)ctx;
    return true;
#endif
}

} // namespace rdma
} // namespace facebook
