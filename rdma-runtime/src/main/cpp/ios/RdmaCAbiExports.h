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
void rdma_nativeInvokeScopeBlock(int64_t blockId, int32_t changed);
void rdma_nativeInvokeScopeBlock1(int64_t blockId, int32_t changed, int32_t p1);
// Components (docs/internals/compose_protocol.md#components): the registered block of the
// component `name` (0 if none), and a composition of it with kernel arguments converted
// by its descriptors (CONSUMES `argsHandle`).
int64_t rdma_componentBlock(const char* name);
void rdma_nativeInvokeComponent(int64_t blockId, void* argsHandle);
void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle);
// Hotfix slot (docs/design/runtime_rework.md §6): runs the JS implementation and
// returns a NEW handle to its kernel-typed result. `*status`: 0 = ok, 1 = fall
// through to the native implementation (calling thread cannot run JS), 2 = error
// (taken with rdma_takeNativeError). CONSUMES `argsHandle`.
void* rdma_nativeInvokeSlot(int64_t blockId, void* argsHandle, const char* paramDescs, const char* resultDesc, int32_t* status);
void rdma_nativeInvokeLambda(int64_t blockId, void* argsHandle);
int64_t rdma_nativeInvokeEffectBody(int64_t blockId);
void rdma_nativeInvokeDispose(int64_t resultId);
void rdma_nativeInvokeFreeBlock(int64_t blockId);
// Block holders (docs/design/runtime_rework.md §4): any thread.
void rdma_nativeRetainBlock(int64_t blockId);
void rdma_nativeReleaseBlock(int64_t blockId);
void rdma_nativeSetBlockParamDescs(int64_t blockId, const char* descs);
int32_t rdma_nativeBlockCount(void);
void rdma_nativeReleaseJsValue(int64_t valueId);
void rdma_nativeInvokeContinuation(int64_t completionId, void* valueHandle);
void rdma_nativeInvokeContinuationError(int64_t completionId, const char* serialized);
void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId, void* argsHandle, const char* paramDescs,
                          const char* resultDesc, int32_t* status);
void rdma_start(void* ctx);
int32_t rdma_is_ready(void);
void rdma_eval_bytes(const void* code, int32_t len);
void rdma_eval_file(const char* path);
void rdma_eval_files(const char* newlineSeparatedPaths);
int32_t rdma_verify_ed25519(const void* message, int32_t length, const void* signature, const void* publicKey);
int32_t rdma_content_version(void);
void rdma_set_uncaught_handler(void);
const char* rdma_takeNativeError(void);
const char* rdma_takeEvalError(void);

// Function-pointer registry (see RdmaCAbiRegistry.h). Kotlin/Native registers its
// C-compatible shim functions here at startup; C++ looks them up by name.
void rdma_registerFunction(const char* name, void* fn);
void* rdma_lookupFunction(const char* name);
