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
#include <memory>
#include <string>
#include <unordered_map>
#include <cstdint>

#include "RdmaRendezvous.h"
#include "RdmaPlatform.h"
#include "RdmaNativeStateCAbi.h"
#include "RdmaCAbiRegistry.h"

// The C-ABI compose bridge surface. This is the iOS equivalent of RdmaCompose.h.
// Two directions cross the boundary:
//   - C++ -> Kotlin: the Kotlin/Native shim registers C-compatible function
//     pointers at startup; C++ calls them via the registry (inline wrappers
//     below keep call sites unchanged). Objects cross as `void*` (StableRef).
//   - Kotlin -> C++: C++ exports real C symbols (rdma_nativeInvoke* etc.) that
//     Kotlin imports via cinterop.
//
// The Composer no longer crosses the boundary at all: the shim holds it in a
// global and every composer-taking wrapper reads it from there.

// --- C++-defined entry points (imported by Kotlin via cinterop). ---
extern "C" {
void rdma_nativeInvokeContent(void);
void rdma_nativeInvokeScopeBlock(int64_t blockId, int32_t changed);
void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle);
void rdma_nativeInvokeLambda(int64_t blockId, void* argsHandle);
int64_t rdma_nativeInvokeEffectBody(int64_t blockId);
void rdma_nativeInvokeDispose(int64_t resultId);
void rdma_nativeInvokeFreeBlock(int64_t blockId);

// Vtable dispatch: casts `vtablePtr` to an RdmaVtable*, calls the JS override for
// `vtableId` with no args, and returns a StableRef handle to the boxed String (or
// null). Called from the generated Kotlin `rdmaVtableDispatch` (native actual).
void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId);

// Runtime bootstrap.
void rdma_start(void* ctx);
int32_t rdma_is_ready(void);
void rdma_eval_bytes(const void* code, int32_t len);
} // extern "C"

// --- Kotlin-provided functions (registered via rdma_registerFunction). ---

// Value boxing (C++ -> Kotlin Any? handle). Caller owns the returned handle.
inline void* rdma_boxInt(int32_t v) { return ((void* (*)(int32_t))rdma_lookupFunction("rdma_boxInt"))(v); }
inline void* rdma_boxLong(int64_t v) { return ((void* (*)(int64_t))rdma_lookupFunction("rdma_boxLong"))(v); }
inline void* rdma_boxDouble(double v) { return ((void* (*)(double))rdma_lookupFunction("rdma_boxDouble"))(v); }
inline void* rdma_boxFloat(float v) { return ((void* (*)(float))rdma_lookupFunction("rdma_boxFloat"))(v); }
inline void* rdma_boxBoolean(bool v) { return ((void* (*)(bool))rdma_lookupFunction("rdma_boxBoolean"))(v); }
inline void* rdma_boxString(const char* v) { return ((void* (*)(const char*))rdma_lookupFunction("rdma_boxString"))(v); }
inline void* rdma_boxObject() { return ((void* (*)())rdma_lookupFunction("rdma_boxObject"))(); }
inline void* rdma_boxJsValueHolder(int64_t id) { return ((void* (*)(int64_t))rdma_lookupFunction("rdma_boxJsValueHolder"))(id); }

// Value classification + unboxing (Kotlin Any? -> C++). Read-only.
inline int32_t rdma_classifyValue(void* handle) { return ((int32_t (*)(void*))rdma_lookupFunction("rdma_classifyValue"))(handle); }
inline int32_t rdma_valueAsInt(void* h) { return ((int32_t (*)(void*))rdma_lookupFunction("rdma_valueAsInt"))(h); }
inline int64_t rdma_valueAsLong(void* h) { return ((int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsLong"))(h); }
inline double rdma_valueAsDouble(void* h) { return ((double (*)(void*))rdma_lookupFunction("rdma_valueAsDouble"))(h); }
inline float rdma_valueAsFloat(void* h) { return ((float (*)(void*))rdma_lookupFunction("rdma_valueAsFloat"))(h); }
inline bool rdma_valueAsBoolean(void* h) { return ((bool (*)(void*))rdma_lookupFunction("rdma_valueAsBoolean"))(h); }
inline const char* rdma_valueAsString(void* h) { return ((const char* (*)(void*))rdma_lookupFunction("rdma_valueAsString"))(h); }
inline int64_t rdma_valueAsJsValueId(void* h) { return ((int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsJsValueId"))(h); }

// Array access (lambda/callback args cross as an Array<Any?> handle).
inline int32_t rdma_arraySize(void* arrayHandle) { return ((int32_t (*)(void*))rdma_lookupFunction("rdma_arraySize"))(arrayHandle); }
inline void* rdma_arrayGet(void* arrayHandle, int32_t index) { return ((void* (*)(void*, int32_t))rdma_lookupFunction("rdma_arrayGet"))(arrayHandle, index); }

// Composer proxy methods (called on the UI thread via rdmaCallUi). The composer
// is read from the shim's global; these take no composer argument.
inline void rdma_composer_startRestartGroup(int32_t key) { ((void (*)(int32_t))rdma_lookupFunction("rdma_composer_startRestartGroup"))(key); }
inline void* rdma_composer_endRestartGroup() { return ((void* (*)())rdma_lookupFunction("rdma_composer_endRestartGroup"))(); }
inline void rdma_composer_startReplaceGroup(int32_t key) { ((void (*)(int32_t))rdma_lookupFunction("rdma_composer_startReplaceGroup"))(key); }
inline void rdma_composer_endReplaceGroup() { ((void (*)())rdma_lookupFunction("rdma_composer_endReplaceGroup"))(); }
inline void rdma_composer_startMovableGroup(int32_t key, void* dataKey) { ((void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startMovableGroup"))(key, dataKey); }
inline void rdma_composer_endMovableGroup() { ((void (*)())rdma_lookupFunction("rdma_composer_endMovableGroup"))(); }
inline void rdma_composer_startReusableGroup(int32_t key, void* dataKey) { ((void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startReusableGroup"))(key, dataKey); }
inline void rdma_composer_endReusableGroup() { ((void (*)())rdma_lookupFunction("rdma_composer_endReusableGroup"))(); }
inline void rdma_composer_skipCurrentGroup() { ((void (*)())rdma_lookupFunction("rdma_composer_skipCurrentGroup"))(); }
inline void rdma_composer_skipToGroupEnd() { ((void (*)())rdma_lookupFunction("rdma_composer_skipToGroupEnd"))(); }
inline bool rdma_composer_shouldExecute(bool cond, int32_t value) { return ((bool (*)(bool, int32_t))rdma_lookupFunction("rdma_composer_shouldExecute"))(cond, value); }
inline bool rdma_composer_changed(void* key) { return ((bool (*)(void*))rdma_lookupFunction("rdma_composer_changed"))(key); }
inline void* rdma_composer_rememberedValue() { return ((void* (*)())rdma_lookupFunction("rdma_composer_rememberedValue"))(); }
inline void rdma_composer_updateRememberedValue(void* value) { ((void (*)(void*))rdma_lookupFunction("rdma_composer_updateRememberedValue"))(value); }

// State.
inline void* rdma_mutableStateOf(void* initialValue) { return ((void* (*)(void*))rdma_lookupFunction("rdma_mutableStateOf"))(initialValue); }
inline void* rdma_stateGetValue(void* stateHandle) { return ((void* (*)(void*))rdma_lookupFunction("rdma_stateGetValue"))(stateHandle); }
inline void rdma_stateSetValue(void* stateHandle, void* value) { ((void (*)(void*, void*))rdma_lookupFunction("rdma_stateSetValue"))(stateHandle, value); }

// Scope.
inline void rdma_scopeUpdateScope(void* scopeHandle, int64_t blockId) { ((void (*)(void*, int64_t))rdma_lookupFunction("rdma_scopeUpdateScope"))(scopeHandle, blockId); }

// Effects (composer read from the shim global).
inline void rdma_sideEffect(int64_t blockId) { ((void (*)(int64_t))rdma_lookupFunction("rdma_sideEffect"))(blockId); }
inline bool rdma_disposableEffect(void* keysHandle, int64_t blockId) { return ((bool (*)(void*, int64_t))rdma_lookupFunction("rdma_disposableEffect"))(keysHandle, blockId); }

namespace facebook {
namespace rdma {

// User-bridge hook: mirrors RdmaCompose.h but marshals objects via C ABI handles.
typedef void (*UserBridgeInstaller)(jsi::Runtime& rt, HostContext ctx, jsi::Object& rdma);
void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer);

// Wraps an arbitrary Kotlin value handle into a jsi::Value: primitives/String via
// the classify/getter surface, anything else via the registered object wrapper.
jsi::Value wrapAnyCAbi(jsi::Runtime& rt, HostContext ctx, void* handle);

// Proxy factories (state + scope) — the C-ABI equivalents of makeStateProxy /
// makeScopeUpdateScopeProxy. Take ownership of the passed handle.
jsi::Object makeStateProxyCAbi(jsi::Runtime& rt, void* stateHandle);
jsi::Object makeScopeUpdateScopeProxyCAbi(jsi::Runtime& rt, void* scopeHandle);

// The registered object wrapper (generated `wrapUserObject`). Set via the
// user-bridge registration hook.
typedef jsi::Value (*ObjectWrapper)(jsi::Runtime& rt, void* handle);
void rdmaSetObjectWrapper(ObjectWrapper wrapper);

// Converts a JSI value into a Kotlin boxed-value handle (or null). Primitives are
// boxed; JS objects/state-proxies are NOT (return null) — callers handle those.
void* boxJsiValue(jsi::Runtime& rt, const jsi::Value& v);

// `get_value` returns the state value classified so the caller can unbox it; this
// is the C-ABI analogue of `unboxJni`/`classifyBoxedValue`.
jsi::Value stateValueToJsi(jsi::Runtime& rt, void* valueHandle);

// Classifies a value handle into an RdmaResult descriptor, taking ownership of the
// handle (disposes non-transferred handles; transfers State handles to the result).
RdmaResult valueHandleToResult(void* valueHandle);

// Converts a UI-thread RdmaResult into a jsi::Value using the C-ABI proxies
// (StateRef -> makeStateProxyCAbi, ScopeRef -> makeScopeUpdateScopeProxyCAbi).
jsi::Value rdmaResultToJsiCAbi(jsi::Runtime& rt, RdmaResult& result);

// Returns the state handle backing a state proxy (or null if `v` is not one).
void* stateProxyHandle(jsi::Runtime& rt, const jsi::Value& v);

// Backing block/JS-value registries (shared with the generated composer proxy).
extern std::shared_ptr<jsi::Object> g_empty;
extern std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
extern int64_t g_nextJsValueId;

// Generic `Any?` classification used by wrapAnyCAbi and stateValueToJsi.
enum class CAbiValueKind {
    Null = 0, State = 1, JsValue = 2, Int = 3, Long = 4, Double = 5,
    Float = 6, Boolean = 7, String = 8, Other = 9, Empty = 10,
};

} // namespace rdma
} // namespace facebook
