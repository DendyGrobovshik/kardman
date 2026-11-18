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
void rdma_nativeInvokeScopeBlock1(int64_t blockId, int32_t changed, int32_t p1);
void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle);
void rdma_nativeInvokeLambda(int64_t blockId, void* argsHandle);
int64_t rdma_nativeInvokeEffectBody(int64_t blockId);
void rdma_nativeInvokeDispose(int64_t resultId);
void rdma_nativeInvokeFreeBlock(int64_t blockId);

// Vtable dispatch: casts `vtablePtr` to an RdmaVtable*, calls the JS override for
// `vtableId` with no args, and returns a StableRef handle to the boxed String (or
// null). Called from the generated Kotlin `rdmaVtableDispatch` (native actual).
void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId, void* argsHandle, const char* paramDescs,
                          const char* resultDesc, int32_t* status);

// Runtime bootstrap.
void rdma_start(void* ctx);
int32_t rdma_is_ready(void);
void rdma_eval_bytes(const void* code, int32_t len);
void rdma_eval_file(const char* path);
int32_t rdma_content_version(void);

// Installs the C-ABI global uncaught-exception handler (design §6).
// Called from RdmaBridge.setUncaughtExceptionHandler; forwards to the Kotlin
// dispatcher registered as "rdma_dispatchUncaught".
void rdma_set_uncaught_handler(void);

// Thread-local JS exception captured by the C++ backend during a blocking
// rdma_nativeInvoke* call (JS -> Native). Kotlin reads it after the call and
// throws RdmaException. Returns a NUL-terminated serialization, or null.
const char* rdma_takeNativeError(void);

// Stored bundle compile/load error (see docs/design/exception_propagation.md §7).
// Kotlin reads it from nativeIsReady() and throws BundleLoadException.
const char* rdma_takeEvalError(void);
} // extern "C"

// --- Kotlin-provided functions (registered via rdma_registerFunction). ---

// Value boxing (C++ -> Kotlin Any? handle). Caller owns the returned handle.
inline void* rdma_boxInt(int32_t v) { static auto fn = (void* (*)(int32_t))rdma_lookupFunction("rdma_boxInt"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(int32_t))rdma_lookupFunction("rdma_boxInt"); return fn(v); }
inline void* rdma_boxLong(int64_t v) { static auto fn = (void* (*)(int64_t))rdma_lookupFunction("rdma_boxLong"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(int64_t))rdma_lookupFunction("rdma_boxLong"); return fn(v); }
inline void* rdma_boxDouble(double v) { static auto fn = (void* (*)(double))rdma_lookupFunction("rdma_boxDouble"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(double))rdma_lookupFunction("rdma_boxDouble"); return fn(v); }
inline void* rdma_boxFloat(float v) { static auto fn = (void* (*)(float))rdma_lookupFunction("rdma_boxFloat"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(float))rdma_lookupFunction("rdma_boxFloat"); return fn(v); }
inline void* rdma_boxBoolean(bool v) { static auto fn = (void* (*)(bool))rdma_lookupFunction("rdma_boxBoolean"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(bool))rdma_lookupFunction("rdma_boxBoolean"); return fn(v); }
inline void* rdma_boxString(const char* v) { static auto fn = (void* (*)(const char*))rdma_lookupFunction("rdma_boxString"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(const char*))rdma_lookupFunction("rdma_boxString"); return fn(v); }
inline void* rdma_boxObject() { static auto fn = (void* (*)())rdma_lookupFunction("rdma_boxObject"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)())rdma_lookupFunction("rdma_boxObject"); return fn(); }
inline void* rdma_boxJsValueHolder(int64_t id) { static auto fn = (void* (*)(int64_t))rdma_lookupFunction("rdma_boxJsValueHolder"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(int64_t))rdma_lookupFunction("rdma_boxJsValueHolder"); return fn(id); }

// Returns a NEW owned handle to the same underlying value — for values whose
// handle is owned by a live JS wrapper but must cross into a store that
// consumes its handle. Caller owns the returned handle.
inline void* rdma_cloneHandle(void* handle) { static auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_cloneHandle"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*))rdma_lookupFunction("rdma_cloneHandle"); return fn(handle); }

// Kernel enum `valueOf` bridges rebuild an entry from its name: the generated
// per-module shims register a handle bridge under `rdma_enum_valueOf:<fqn>`
// (CAbiKotlinGenerator.writeEnumMembers). Returns a fresh owned handle, or null
// when the enum has no registered bridge or the name is invalid — read
// rdma_takeError() for the kernel-side error before raising.
inline void* rdma_enumValueOfHandle(const char* fqn, const char* entryName) {
    std::string key = std::string("rdma_enum_valueOf:") + fqn;
    void* fn = rdma_lookupFunction(key.c_str());
    if (!fn) return nullptr;
    return ((void* (*)(const char*))fn)(entryName);
}

// Value classification + unboxing (Kotlin Any? -> C++). Read-only.
inline int32_t rdma_classifyValue(void* handle) { static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_classifyValue"); if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_classifyValue"); return fn(handle); }
inline int32_t rdma_valueAsInt(void* h) { static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_valueAsInt"); if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_valueAsInt"); return fn(h); }
inline int64_t rdma_valueAsLong(void* h) { static auto fn = (int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsLong"); if (__builtin_expect(fn == nullptr, 0)) fn = (int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsLong"); return fn(h); }
inline double rdma_valueAsDouble(void* h) { static auto fn = (double (*)(void*))rdma_lookupFunction("rdma_valueAsDouble"); if (__builtin_expect(fn == nullptr, 0)) fn = (double (*)(void*))rdma_lookupFunction("rdma_valueAsDouble"); return fn(h); }
inline float rdma_valueAsFloat(void* h) { static auto fn = (float (*)(void*))rdma_lookupFunction("rdma_valueAsFloat"); if (__builtin_expect(fn == nullptr, 0)) fn = (float (*)(void*))rdma_lookupFunction("rdma_valueAsFloat"); return fn(h); }
inline bool rdma_valueAsBoolean(void* h) { static auto fn = (bool (*)(void*))rdma_lookupFunction("rdma_valueAsBoolean"); if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*))rdma_lookupFunction("rdma_valueAsBoolean"); return fn(h); }
inline const char* rdma_valueAsString(void* h) { static auto fn = (const char* (*)(void*))rdma_lookupFunction("rdma_valueAsString"); if (__builtin_expect(fn == nullptr, 0)) fn = (const char* (*)(void*))rdma_lookupFunction("rdma_valueAsString"); return fn(h); }
inline const char* rdma_valueAsEnumName(void* h) { static auto fn = (const char* (*)(void*))rdma_lookupFunction("rdma_valueAsEnumName"); if (__builtin_expect(fn == nullptr, 0)) fn = (const char* (*)(void*))rdma_lookupFunction("rdma_valueAsEnumName"); return fn(h); }
inline int64_t rdma_valueAsJsValueId(void* h) { static auto fn = (int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsJsValueId"); if (__builtin_expect(fn == nullptr, 0)) fn = (int64_t (*)(void*))rdma_lookupFunction("rdma_valueAsJsValueId"); return fn(h); }

// Thread-local Kotlin exception (set by the shim's try/catch wrappers). Returns a
// NUL-terminated serialization of the last Kotlin exception on this thread, or
// null. C++ reads it after every Kotlin call and rethrows as a JS Error.
inline const char* rdma_takeError() { static auto fn = (const char* (*)())rdma_lookupFunction("rdma_takeError"); if (__builtin_expect(fn == nullptr, 0)) fn = (const char* (*)())rdma_lookupFunction("rdma_takeError"); return fn(); }

// Array access (lambda/callback args cross as an Array<Any?> handle).
inline int32_t rdma_arraySize(void* arrayHandle) { static auto fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_arraySize"); if (__builtin_expect(fn == nullptr, 0)) fn = (int32_t (*)(void*))rdma_lookupFunction("rdma_arraySize"); return fn(arrayHandle); }
inline void* rdma_arrayGet(void* arrayHandle, int32_t index) { static auto fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_arrayGet"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, int32_t))rdma_lookupFunction("rdma_arrayGet"); return fn(arrayHandle, index); }

// Composer proxy methods (called on the UI thread via rdmaCallUi). The composer
// is read from the shim's global; these take no composer argument.
inline void rdma_composer_startRestartGroup(int32_t key) { static auto fn = (void (*)(int32_t))rdma_lookupFunction("rdma_composer_startRestartGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(int32_t))rdma_lookupFunction("rdma_composer_startRestartGroup"); fn(key); }
inline void* rdma_composer_endRestartGroup() { static auto fn = (void* (*)())rdma_lookupFunction("rdma_composer_endRestartGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)())rdma_lookupFunction("rdma_composer_endRestartGroup"); return fn(); }
inline void rdma_composer_startReplaceGroup(int32_t key) { static auto fn = (void (*)(int32_t))rdma_lookupFunction("rdma_composer_startReplaceGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(int32_t))rdma_lookupFunction("rdma_composer_startReplaceGroup"); fn(key); }
inline void rdma_composer_endReplaceGroup() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_endReplaceGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_endReplaceGroup"); fn(); }
inline void rdma_composer_startMovableGroup(int32_t key, void* dataKey) { static auto fn = (void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startMovableGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startMovableGroup"); fn(key, dataKey); }
inline void rdma_composer_endMovableGroup() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_endMovableGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_endMovableGroup"); fn(); }
inline void rdma_composer_startReusableGroup(int32_t key, void* dataKey) { static auto fn = (void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startReusableGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(int32_t, void*))rdma_lookupFunction("rdma_composer_startReusableGroup"); fn(key, dataKey); }
inline void rdma_composer_endReusableGroup() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_endReusableGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_endReusableGroup"); fn(); }
inline void rdma_composer_skipCurrentGroup() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_skipCurrentGroup"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_skipCurrentGroup"); fn(); }
inline void rdma_composer_skipToGroupEnd() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_skipToGroupEnd"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_skipToGroupEnd"); fn(); }
inline void rdma_composer_startDefaults() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_startDefaults"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_startDefaults"); fn(); }
inline void rdma_composer_endDefaults() { static auto fn = (void (*)())rdma_lookupFunction("rdma_composer_endDefaults"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)())rdma_lookupFunction("rdma_composer_endDefaults"); fn(); }
inline bool rdma_composer_getDefaultsInvalid() { static auto fn = (bool (*)())rdma_lookupFunction("rdma_composer_getDefaultsInvalid"); if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)())rdma_lookupFunction("rdma_composer_getDefaultsInvalid"); return fn(); }
inline bool rdma_composer_shouldExecute(bool cond, int32_t value) { static auto fn = (bool (*)(bool, int32_t))rdma_lookupFunction("rdma_composer_shouldExecute"); if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(bool, int32_t))rdma_lookupFunction("rdma_composer_shouldExecute"); return fn(cond, value); }
inline bool rdma_composer_changed(void* key) { static auto fn = (bool (*)(void*))rdma_lookupFunction("rdma_composer_changed"); if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*))rdma_lookupFunction("rdma_composer_changed"); return fn(key); }
inline void* rdma_composer_rememberedValue() { static auto fn = (void* (*)())rdma_lookupFunction("rdma_composer_rememberedValue"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)())rdma_lookupFunction("rdma_composer_rememberedValue"); return fn(); }
inline void rdma_composer_updateRememberedValue(void* value) { static auto fn = (void (*)(void*))rdma_lookupFunction("rdma_composer_updateRememberedValue"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*))rdma_lookupFunction("rdma_composer_updateRememberedValue"); fn(value); }

// State. `rdma_mutableStateOf` takes the channel's value type descriptor (fixed
// at creation): the state handle model it as a carrier (`RdmaTypedState`) so the
// descriptor travels with the handle. State handles/initial values are only
// read by the shim (borrowed) — the caller owns them.
inline void* rdma_mutableStateOf(void* initialValue, const char* typeDesc) {
    static auto fn = (void* (*)(void*, const char*))rdma_lookupFunction("rdma_mutableStateOf");
    if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*, const char*))rdma_lookupFunction("rdma_mutableStateOf");
    return fn(initialValue, typeDesc);
}
inline const char* rdma_stateTypeDesc(void* stateHandle) { static auto fn = (const char* (*)(void*))rdma_lookupFunction("rdma_stateTypeDesc"); if (__builtin_expect(fn == nullptr, 0)) fn = (const char* (*)(void*))rdma_lookupFunction("rdma_stateTypeDesc"); return fn(stateHandle); }
inline void* rdma_stateGetValue(void* stateHandle) { static auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_stateGetValue"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*))rdma_lookupFunction("rdma_stateGetValue"); return fn(stateHandle); }
inline void rdma_stateSetValue(void* stateHandle, void* value) { static auto fn = (void (*)(void*, void*))rdma_lookupFunction("rdma_stateSetValue"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*, void*))rdma_lookupFunction("rdma_stateSetValue"); fn(stateHandle, value); }

// Snapshot collections: copy the borrowed source list/map handle into a
// `SnapshotStateList`/`SnapshotStateMap`. Caller owns the returned handle.
inline void* rdma_mutableStateListOf(void* sourceList) { static auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_mutableStateListOf"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*))rdma_lookupFunction("rdma_mutableStateListOf"); return fn(sourceList); }
inline void* rdma_mutableStateMapOf(void* sourceMap) { static auto fn = (void* (*)(void*))rdma_lookupFunction("rdma_mutableStateMapOf"); if (__builtin_expect(fn == nullptr, 0)) fn = (void* (*)(void*))rdma_lookupFunction("rdma_mutableStateMapOf"); return fn(sourceMap); }

// Scope.
inline void rdma_scopeUpdateScope(void* scopeHandle, int64_t blockId) { static auto fn = (void (*)(void*, int64_t))rdma_lookupFunction("rdma_scopeUpdateScope"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(void*, int64_t))rdma_lookupFunction("rdma_scopeUpdateScope"); fn(scopeHandle, blockId); }

// Effects (composer read from the shim global).
inline void rdma_sideEffect(int64_t blockId) { static auto fn = (void (*)(int64_t))rdma_lookupFunction("rdma_sideEffect"); if (__builtin_expect(fn == nullptr, 0)) fn = (void (*)(int64_t))rdma_lookupFunction("rdma_sideEffect"); fn(blockId); }
inline bool rdma_disposableEffect(void* keysHandle, int64_t blockId) { static auto fn = (bool (*)(void*, int64_t))rdma_lookupFunction("rdma_disposableEffect"); if (__builtin_expect(fn == nullptr, 0)) fn = (bool (*)(void*, int64_t))rdma_lookupFunction("rdma_disposableEffect"); return fn(keysHandle, blockId); }

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
jsi::Object makeStateProxyCAbi(jsi::Runtime& rt, void* stateHandle, const std::string& typeDesc = "kotlin.Any");
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

// Captures a Kotlin exception (recorded by the shim's thread-local buffer via
// rdma_takeError) into an Error result; returns false if none (Native -> JS, §5).
bool captureCAbiError(RdmaResult& out);

// Rethrows a Kotlin exception as a JS Error. Call on the Hermes thread after any
// Kotlin shim call (used by generated proxies/widgets).
void checkKotlinError(jsi::Runtime& rt);

// Backing block/JS-value registries (shared with the generated composer proxy).
extern std::shared_ptr<jsi::Object> g_empty;
extern std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
extern int64_t g_nextJsValueId;

// Generic `Any?` classification used by wrapAnyCAbi and stateValueToJsi.
enum class CAbiValueKind {
    Null = 0, State = 1, JsValue = 2, Int = 3, Long = 4, Double = 5,
    Float = 6, Boolean = 7, String = 8, Other = 9, Empty = 10, Enum = 11,
};

} // namespace rdma
} // namespace facebook
