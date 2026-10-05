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
#include <stdint.h>

// C-ABI entry points DEFINED in C++ and IMPORTED by the Kotlin/Native runtime
// shim (via cinterop). This is deliberately a separate, pure-C header from
// RdmaComposeCAbi.h, which also declares the Kotlin-defined shim surface (those
// are exported via @CName and must not be re-imported here).
void rdma_nativeInvokeContent(void);
void rdma_nativeInvokeScopeBlock(int64_t blockId, int32_t changed);
void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle);
void rdma_nativeInvokeLambda(int64_t blockId, void* argsHandle);
int64_t rdma_nativeInvokeEffectBody(int64_t blockId);
void rdma_nativeInvokeDispose(int64_t resultId);
void rdma_nativeInvokeFreeBlock(int64_t blockId);
void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId);
void rdma_start(void* ctx);
int32_t rdma_is_ready(void);
void rdma_eval_bytes(const void* code, int32_t len);

// Function-pointer registry (see RdmaCAbiRegistry.h). Kotlin/Native registers its
// C-compatible shim functions here at startup; C++ looks them up by name.
void rdma_registerFunction(const char* name, void* fn);
void* rdma_lookupFunction(const char* name);
