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
#include "RdmaSignature.h"
#include "RdmaComposeCAbi.h"
#include "RdmaComposerProxy.h"
#include "RdmaRuntime.h"
#include "RdmaTypeDesc.h"
#include "RdmaJsLong.h"
#include "RdmaVtable.h"
#include "RdmaLog.h"
#include "RdmaBlocks.h"
#include "RdmaIdentity.h"

#include <jsi/jsi.h>
#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>
#include <atomic>
#include <utility>
#include <dispatch/dispatch.h>

#define LOG_TAG "RdmaComposeCAbi"

// Function-pointer registry (see RdmaCAbiRegistry.h). Lazily initialized so it is
// available regardless of static-init order between the C++ core and the
// Kotlin/Native framework eager initialization.
namespace {
using FnTable = std::unordered_map<std::string, void*>;
FnTable& rdmaFnTable() {
    static FnTable table;
    return table;
}
} // namespace

extern "C" {
void rdma_registerFunction(const char* name, void* fn) {
    if (name && fn) rdmaFnTable()[name] = fn;
}
void* rdma_lookupFunction(const char* name) {
    if (!name) return nullptr;
    auto it = rdmaFnTable().find(name);
    return it == rdmaFnTable().end() ? nullptr : it->second;
}
} // extern "C"

namespace facebook {
namespace rdma {

static std::atomic<int> g_contentVersion{0};
std::shared_ptr<jsi::Object> g_empty;
// Plugin JS blocks referenced by id live in the shared registry (RdmaBlocks.h).
std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
int64_t g_nextJsValueId = 1;

// Suspend completions: id -> the two registered JS continuation callbacks (result
// and error) plus the return type descriptor registered by the generated
// `_<name>Async` host function. All entries are created and consumed on the Hermes
// thread.
struct SuspendContinuation {
    std::shared_ptr<jsi::Function> onResult;
    std::shared_ptr<jsi::Function> onError;
    std::string typeDesc;
};
static std::unordered_map<int64_t, SuspendContinuation> g_suspendContinuations;
static int64_t g_nextContinuationId = 1;

static thread_local bool g_inComposition = false;

static UserBridgeInstaller g_userBridge = nullptr;
static ObjectWrapper g_objectWrapper = nullptr;

void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer) {
    g_userBridge = installer;
}

void rdmaSetObjectWrapper(ObjectWrapper wrapper) {
    g_objectWrapper = wrapper;
}

// ------------------------------------------------------------ value conversion

// Cross-runtime error for a type-marshal violation (docs/design/type_descriptors.md):
// a value that does not fit its channel's descriptor raises instead of being coerced.
[[noreturn]] static void throwTypeErrorCAbi(jsi::Runtime& rt, const std::string& message) {
    rdmaThrowTypeError(rt, message); // shared construction (RdmaJsLong.h) — no drift
}

// `Long` crossings use the shared helpers (RdmaJsLong.h, included via
// RdmaComposeCAbi.h): `rdmaCheckedJLong` reads the plugin's `Long(low, high)` object
// (or an integral JS number) with full 64-bit fidelity, `rdmaLongToJs` wraps a
// kernel Long back into that object (docs/design/type_descriptors.md#long-policy).

void* boxJsiValue(jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isNumber()) return rdma_boxInt((int32_t)v.getNumber());
    if (v.isString()) return rdma_boxString(v.getString(rt).utf8(rt).c_str());
    if (v.isBool()) return rdma_boxBoolean(v.getBool());
    return nullptr;
}

// Descriptor-typed strict boxing for state writes (docs/design/type_descriptors.md):
// every returned handle is OWNED by the caller (fresh StableRef — the shim's
// state setters only read the value behind it). Scalars get their exact Kotlin
// class, `null` is a value only on nullable channels, enum entries rebuild
// through the kernel's registered `valueOf` bridge, `@RDMA` objects reuse the
// kernel handle behind the plugin-side wrapper (cloned so disposal is safe),
// and lists/maps reuse or materialize their kernel collection. A value that
// does not fit its descriptor raises instead of being coerced.

// Strict scalar leaf boxing shared with the collection element path
// (`jsiToHandle`) so state writes and element writes cannot drift: a foreign
// shape raises, and `Long` accepts ONLY the plugin's `Long(low, high)` object
// (docs/design/type_descriptors.md#long-policy).
static void* boxLeafValueCAbi(jsi::Runtime& rt, const jsi::Value& v, const std::string& fqn) {
    if (fqn == "kotlin.Int") {
        if (!v.isNumber() || v.getNumber() != (double)(long long)v.getNumber() ||
            v.getNumber() < -2147483648.0 || v.getNumber() > 2147483647.0) {
            throwTypeErrorCAbi(rt, "value does not fit kotlin.Int");
        }
        return rdma_boxInt((int32_t)v.getNumber());
    }
    if (fqn == "rdma.BlockId") {
        // A plugin block id (`RDMA.registerBlock`): a plain non-negative integral JS
        // number, boxed as the kernel's `Long` (e.g. `List<() -> Unit>` widget args).
        if (!v.isNumber()) throwTypeErrorCAbi(rt, "expected a block id (number) at the boundary");
        double dv = v.getNumber();
        if (dv < 0 || (double)(long long)dv != dv) throwTypeErrorCAbi(rt, "invalid block id");
        return rdma_boxLong((int64_t)dv);
    }
    if (fqn == "kotlin.Long") {
        // The plugin's `Long(low, high)` object with full 64-bit fidelity
        // (docs/design/type_descriptors.md#long-policy).
        return rdma_boxLong((int64_t)rdmaCheckedJLong(rt, v));
    }
    if (fqn == "kotlin.Double") {
        if (!v.isNumber()) throwTypeErrorCAbi(rt, "expected a number for kotlin.Double");
        return rdma_boxDouble(v.getNumber());
    }
    if (fqn == "kotlin.Float") {
        // Float crosses as a Kotlin Float, not a Double (audit #26): box it as such.
        if (!v.isNumber()) throwTypeErrorCAbi(rt, "expected a number for kotlin.Float");
        return rdma_boxFloat((float)v.getNumber());
    }
    if (fqn == "kotlin.Boolean") {
        if (!v.isBool()) throwTypeErrorCAbi(rt, "expected a Boolean value");
        return rdma_boxBoolean(v.getBool());
    }
    if (fqn == "kotlin.String") {
        if (!v.isString()) throwTypeErrorCAbi(rt, "expected a String value");
        return rdma_boxString(v.getString(rt).utf8(rt).c_str());
    }
    return nullptr; // not a known scalar leaf
}

// Rebuilds an `@RDMA` enum entry from its name string through the kernel's
// registered `valueOf` bridge (`rdma_enumValueOfHandle`, populated by the
// generated per-module shims). Returns an OWNED handle of the entry.
static void* enumValueOfCAbi(jsi::Runtime& rt, const std::string& fqn, const std::string& entryName) {
    void* entry = rdma_enumValueOfHandle(fqn.c_str(), entryName.c_str());
    checkKotlinError(rt); // an invalid name surfaces as the kernel's error (types.md)
    if (!entry) {
        throwTypeErrorCAbi(rt, "cannot rebuild the enum entry '" + entryName + "' for " + fqn +
            ": no kernel valueOf bridge is registered for this enum");
    }
    return entry;
}

static void* boxStateValueCAbi(jsi::Runtime& rt, const jsi::Value& v, const TypeDesc& d) {
    // `kotlin.Unit` is a channel's "no-value": an `undefined` write or initial is legal
    // (audit #27). The kernel holds `undefined`/null for Unit, so no strict null-shape check
    // applies.
    if (d.kind == TypeDesc::LEAF && d.fqn == "kotlin.Unit") return nullptr;
    if (v.isNull() || v.isUndefined()) {
        if (d.nullable) return nullptr;
        throwTypeErrorCAbi(rt, "null is not a value of the non-nullable state type " + d.serialize());
    }
    if (d.kind == TypeDesc::LEAF) {
        if (d.isEnum) {
            // The entry crosses as its name string (types.md).
            if (!v.isString()) throwTypeErrorCAbi(rt, "expected the enum entry name string for " + d.fqn);
            return enumValueOfCAbi(rt, d.fqn, v.getString(rt).utf8(rt));
        }
        void* scalar = boxLeafValueCAbi(rt, v, d.fqn);
        if (scalar) return scalar;
        // `@RDMA` class leaf: the plugin-side value is the wrapper around the kernel
        // object's handle. Clone the handle (OWNED) so the state write keeps the
        // object and the caller's dispose is safe.
        if (v.isObject()) {
            jsi::Object o = v.asObject(rt);
            if (o.hasNativeState(rt)) {
                auto ns = std::dynamic_pointer_cast<RdmaObjectNativeStateCAbi>(o.getNativeState(rt));
                if (ns && ns->handle()) return rdma_cloneHandle(ns->handle());
            }
        }
        throwTypeErrorCAbi(rt, "value does not fit the state type " + d.serialize());
    }
    if (d.kind == TypeDesc::LIST) {
        if (!v.isObject()) throwTypeErrorCAbi(rt, "expected a List value for " + d.serialize());
        // `jsiToHandle` reuses a wrapped kernel list (same list object, cloned
        // handle) or materializes a plain JS array/Kotlin/JS list — either way it
        // returns an OWNED handle the state write consumes.
        return jsiToHandle(rt, v, d.serialize());
    }
    if (d.kind == TypeDesc::MAP) {
        if (!v.isObject()) throwTypeErrorCAbi(rt, "expected a Map value for " + d.serialize());
        return jsiToHandle(rt, v, d.serialize());
    }
    throwTypeErrorCAbi(rt, "value does not fit the state type " + d.serialize());
}

// A Compose key (docs/internals/compose_protocol.md#object-keys): primitives are boxed and
// compare by value; a kernel object or a state is the native object itself (its `equals`);
// any other JS object crosses as its identity, so the same object is "unchanged".
void* rdmaBoxComposeKeyCAbi(jsi::Runtime& rt, const jsi::Value& v) {
    if (!v.isObject()) return boxJsiValue(rt, v);
    if (void* state = stateProxyHandle(rt, v)) return rdma_cloneHandle(state);
    if (auto objState = rdmaStateAs<RdmaObjectNativeStateCAbi>(rt, v, RdmaStateKind::Object)) {
        if (objState->handle()) return rdma_cloneHandle(objState->handle());
    }
    return rdma_boxIdentityKey(rdmaJsIdentity(rt, v.asObject(rt)));
}

static void* boxKeyForRemember(jsi::Runtime& rt, const jsi::Value& v) {
    return rdmaBoxComposeKeyCAbi(rt, v);
}

// The channel type descriptor a state handle carries (empty for a remembered raw
// `MutableState`, whose descriptor never existed — writes to such a channel fail
// loudly instead of guessing).
static std::string rdmaStateTypeDescStr(void* stateHandle) {
    const char* s = rdma_stateTypeDesc(stateHandle);
    return s ? std::string(s) : std::string();
}

jsi::Value wrapAnyCAbi(jsi::Runtime& rt, HostContext /*ctx*/, void* handle) {
    int32_t kind = rdma_classifyValue(handle);
    switch ((CAbiValueKind)kind) {
        case CAbiValueKind::Null:
            return jsi::Value::null();
        case CAbiValueKind::Int: {
            int32_t v = rdma_valueAsInt(handle);
            rdma_disposeStableRef(handle);
            return jsi::Value((double)v);
        }
        case CAbiValueKind::Long: {
            int64_t v = rdma_valueAsLong(handle);
            rdma_disposeStableRef(handle);
            return rdmaLongToJs(rt, (long long)v); // plugin's Long object, exact
        }
        case CAbiValueKind::Double: {
            double v = rdma_valueAsDouble(handle);
            rdma_disposeStableRef(handle);
            return jsi::Value(v);
        }
        case CAbiValueKind::Float: {
            float v = rdma_valueAsFloat(handle);
            rdma_disposeStableRef(handle);
            return jsi::Value((double)v);
        }
        case CAbiValueKind::Boolean: {
            bool v = rdma_valueAsBoolean(handle);
            rdma_disposeStableRef(handle);
            return jsi::Value(v);
        }
        case CAbiValueKind::String: {
            const char* s = rdma_valueAsString(handle);
            jsi::String out = jsi::String::createFromUtf8(rt, s ? s : "");
            rdma_disposeStableRef(handle);
            return out;
        }
        case CAbiValueKind::Enum: {
            // `@RDMA` enum entries cross as their name string (types.md).
            const char* s = rdma_valueAsEnumName(handle);
            jsi::String out = jsi::String::createFromUtf8(rt, s ? s : "");
            rdma_disposeStableRef(handle);
            return out;
        }
        case CAbiValueKind::State: {
            // The state handle carries its channel descriptor (RdmaTypedState): the
            // re-wrapped proxy marshals writes/reads with the exact channel type.
            return makeStateProxyCAbi(rt, handle, rdmaStateTypeDescStr(handle));
        }
        case CAbiValueKind::JsValue: {
            int64_t id = rdma_valueAsJsValueId(handle);
            rdma_disposeStableRef(handle);
            auto it = g_jsValues.find(id);
            if (it != g_jsValues.end()) return jsi::Value(rt, *it->second);
            return jsi::Value::undefined();
        }
        case CAbiValueKind::Other:
        default:
            if (g_objectWrapper) return g_objectWrapper(rt, handle);
            if (handle) rdma_disposeStableRef(handle);
            return jsi::Value::undefined();
    }
}

RdmaResult valueHandleToResult(void* handle) {
    int32_t kind = rdma_classifyValue(handle);
    switch ((CAbiValueKind)kind) {
        case CAbiValueKind::Empty:
            if (handle) rdma_disposeStableRef(handle);
            return RdmaResult::empty();
        case CAbiValueKind::State:
            // The state handle carries its channel descriptor — ride it along so
            // `rdmaResultToJsiCAbi` re-wraps the proxy with the exact channel type
            // (no side registry, no descriptor loss).
            return RdmaResult::stateRef(handle, rdmaStateTypeDescStr(handle));
        case CAbiValueKind::JsValue: {
            int64_t id = rdma_valueAsJsValueId(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::jsValueId(id);
        }
        case CAbiValueKind::Int: {
            int32_t v = rdma_valueAsInt(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::number((double)v);
        }
        case CAbiValueKind::Long: {
            int64_t v = rdma_valueAsLong(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::i64Value((long long)v); // full 64 bits, never squeezed through a double
        }
        case CAbiValueKind::Double: {
            double v = rdma_valueAsDouble(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::number(v);
        }
        case CAbiValueKind::Float: {
            float v = rdma_valueAsFloat(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::number((double)v);
        }
        case CAbiValueKind::Boolean: {
            bool v = rdma_valueAsBoolean(handle);
            rdma_disposeStableRef(handle);
            return RdmaResult::boolean(v);
        }
        case CAbiValueKind::String: {
            const char* s = rdma_valueAsString(handle);
            std::string str(s ? s : "");
            rdma_disposeStableRef(handle);
            return RdmaResult::string(std::move(str));
        }
        case CAbiValueKind::Enum: {
            // `@RDMA` enum entries cross as their name string (types.md).
            const char* s = rdma_valueAsEnumName(handle);
            std::string str(s ? s : "");
            rdma_disposeStableRef(handle);
            return RdmaResult::string(std::move(str));
        }
        case CAbiValueKind::Other:
            // An opaque kernel object crossed as a handle (an `@RDMA` class, a
            // `List`/`Map` container): wrap it on the JS thread — `wrapTypedHandle`
            // with an empty descriptor falls through to the `Any` wrapper, the same
            // object shape the non-rendezvous `wrapAnyCAbi` path produces.
            return RdmaResult::hostValue(handle, "");
        case CAbiValueKind::Null:
        default:
            if (handle) rdma_disposeStableRef(handle);
            return RdmaResult::null();
    }
}

jsi::Value stateValueToJsi(jsi::Runtime& rt, void* valueHandle) {
    return wrapAnyCAbi(rt, nullptr, valueHandle);
}

// Rethrows a Kotlin exception (recorded by the shim's thread-local buffer) as a
// JS Error. Call after every Kotlin shim call (Native -> JS, §5).
void checkKotlinError(jsi::Runtime& rt) {
    const char* err = rdma_takeError();
    if (!err) return;
    RdmaExceptionInfo info;
    info.originalExceptionType = "kotlin.Throwable";
    info.message = err;
    throwJsException(rt, info);
}

// Captures a Kotlin exception into an Error result (for UI-thread ops returning
// their result through RdmaResult). Returns false if none.
bool captureCAbiError(RdmaResult& out) {
    const char* err = rdma_takeError();
    if (!err) return false;
    RdmaExceptionInfo info;
    info.originalExceptionType = "kotlin.Throwable";
    info.message = err;
    out = RdmaResult::fromError(std::move(info));
    return true;
}

// Descriptor-typed wrap of a host value (RdmaCollectionsCAbi.cpp). CONSUMES `handle`.
static jsi::Value wrapTypedHandle(jsi::Runtime& rt, void* handle, const std::string& type) {
    return rdmaWrapTypedHandleCAbi(rt, handle, type);
}

jsi::Value rdmaResultToJsiCAbi(jsi::Runtime& rt, RdmaResult& result) {
    switch (result.kind) {
        case RdmaResultKind::Undefined: return jsi::Value::undefined();
        case RdmaResultKind::Null: return jsi::Value::null();
        case RdmaResultKind::Bool: return jsi::Value(result.b);
        case RdmaResultKind::Number: return jsi::Value(result.num);
        case RdmaResultKind::I64: return rdmaLongToJs(rt, result.i64); // plugin's Long object, exact
        case RdmaResultKind::String: return jsi::String::createFromUtf8(rt, result.str);
        case RdmaResultKind::Empty:
            return g_empty ? jsi::Value(rt, *g_empty) : jsi::Value::undefined();
        case RdmaResultKind::JsValueId: {
            auto it = g_jsValues.find(result.id);
            if (it != g_jsValues.end()) return jsi::Value(rt, *it->second);
            return jsi::Value::undefined();
        }
        case RdmaResultKind::HostValue: {
            void* ref = result.ref;
            result.ref = nullptr; // the wrap keeps ownership
            return wrapTypedHandle(rt, ref, result.str);
        }
        case RdmaResultKind::StateRef: {
            void* ref = result.ref;
            result.ref = nullptr;
            if (!ref) return jsi::Value::null();
            return makeStateProxyCAbi(rt, ref, result.str);
        }
        case RdmaResultKind::ScopeRef: {
            void* ref = result.ref;
            result.ref = nullptr;
            if (!ref) return jsi::Value::null();
            return makeScopeUpdateScopeProxyCAbi(rt, ref);
        }
        case RdmaResultKind::Error: {
            throwJsException(rt, result.error);
        }
    }
    return jsi::Value::undefined();
}

// -------------------------------------------------------------------- proxies

// MutableState proxy: a NativeState object over a shared lazy prototype
// (docs/design/runtime_rework.md §3.8); `get_value`/`set_value` are resolved once per
// runtime by their compile-mangled names and read the state from `this`.
struct StateProxyStateCAbi : public RdmaTaggedState {
    StateProxyStateCAbi(void* state, std::string typeDesc)
        : RdmaTaggedState(RdmaStateKind::State), state_(state), desc_(TypeDesc::parseOrAny(typeDesc)) {}
    ~StateProxyStateCAbi() override {
        if (state_) rdma_disposeStableRef(state_);
    }
    void* state_;
    TypeDesc desc_;
};

static std::shared_ptr<StateProxyStateCAbi> stateProxyStateOf(jsi::Runtime& r, const jsi::Value& thisVal) {
    auto st = rdmaStateAs<StateProxyStateCAbi>(r, thisVal, RdmaStateKind::State);
    if (!st) throw jsi::JSError(r, "receiver is not a MutableState proxy");
    return st;
}

static jsi::Value resolveStateMethodCAbi(jsi::Runtime& rt, const std::string& n) {
    if (n.rfind("get_value", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get_value"), 0,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value*, size_t) -> jsi::Value {
                void* state = stateProxyStateOf(r, thisVal)->state_;
                if (g_inComposition) {
                    RdmaResult res = rdmaCallUi([state]() -> RdmaResult {
                        void* v = rdma_stateGetValue(state);
                        return valueHandleToResult(v);
                    });
                    return rdmaResultToJsiCAbi(r, res);
                }
                void* v = rdma_stateGetValue(state);
                checkKotlinError(r);
                return stateValueToJsi(r, v);
            });
    }
    if (n.rfind("set_value", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set_value"), 1,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                auto st = stateProxyStateOf(r, thisVal);
                void* state = st->state_;
                // Descriptor-typed and strict (docs/design/type_descriptors.md):
                // a foreign-typed value raises instead of being coerced, and `null`
                // is written as a value only on nullable states. The legacy path
                // keeps the old skip-on-unboxable behavior for pre-descriptor jars.
                void* boxed = boxStateValueCAbi(r, args[0], st->desc_);
                if (g_inComposition) {
                    // Rendezvous write during composition: the write runs inside the
                    // UI thread's active composition snapshot, so it is visible to
                    // the same pass' reads (docs/internals/compose_protocol.md#state) — the Android
                    // backend follows the same contract.
                    rdmaCallUi([state, boxed]() -> RdmaResult {
                        rdma_stateSetValue(state, boxed);
                        if (boxed) rdma_disposeStableRef(boxed);
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                }
                // Outside composition the UI thread is not in a rendezvous: post the
                // write to the main queue so the Compose snapshot system records it
                // and the Recomposer schedules a recomposition. The posted write holds
                // its own handle: the proxy may be collected before it runs.
                void* own = rdma_cloneHandle(state);
                dispatch_async(dispatch_get_main_queue(), ^{
                    rdma_stateSetValue(own, boxed);
                    if (boxed) rdma_disposeStableRef(boxed);
                    rdma_disposeStableRef(own);
                });
                return jsi::Value::undefined();
            });
    }
    return jsi::Value::undefined();
}

jsi::Object makeStateProxyCAbi(jsi::Runtime& rt, void* stateHandle, const std::string& typeDesc) {
    // The channel descriptor normally rides along the value transport
    // (`RdmaResult::stateRef(handle, desc)`). A handle without one falls back to
    // the descriptor the state handle itself carries (`RdmaTypedState`), and a
    // remembered raw `MutableState` (whose descriptor never existed) proxies with
    // an empty descriptor — reads work, writes fail loudly instead of guessing
    // (docs/design/type_descriptors.md: the descriptor is fixed per channel).
    std::string desc = typeDesc.empty() ? rdmaStateTypeDescStr(stateHandle) : typeDesc;
    static jsi::Object* proto = nullptr; // shared, intentionally leaked (lives as long as the runtime)
    if (!proto) proto = new jsi::Object(rdmaLazyProto(rt, resolveStateMethodCAbi));
    jsi::Object obj = jsi::Object::create(rt, jsi::Value(rt, *proto));
    obj.setNativeState(rt, std::make_shared<StateProxyStateCAbi>(stateHandle, desc));
    return obj;
}

void* stateProxyHandle(jsi::Runtime& rt, const jsi::Value& v) {
    auto st = rdmaStateAs<StateProxyStateCAbi>(rt, v, RdmaStateKind::State);
    return st ? st->state_ : nullptr;
}

// ScopeUpdateScope proxy (same object model as the state proxy).
struct ScopeProxyStateCAbi : public RdmaTaggedState {
    explicit ScopeProxyStateCAbi(void* scope) : RdmaTaggedState(RdmaStateKind::Scope), scope_(scope) {}
    ~ScopeProxyStateCAbi() override {
        if (scope_) rdma_disposeStableRef(scope_);
    }
    void* scope_;
};

static jsi::Value resolveScopeMethodCAbi(jsi::Runtime& rt, const std::string& n) {
    if (n.rfind("updateScope", 0) != 0) return jsi::Value::undefined();
    return jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "updateScope"), 1,
        [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            auto st = rdmaStateAs<ScopeProxyStateCAbi>(r, thisVal, RdmaStateKind::Scope);
            if (!st) throw jsi::JSError(r, "receiver is not a ScopeUpdateScope proxy");
            if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            void* scope = st->scope_;
            // Held by the kernel `ComposerScopeBlock` (retained on construction).
            int64_t id = rdmaBlockRegisterPlugin(r, args[0].asObject(r).asFunction(r), "");
            rdmaCallUi([scope, id]() -> RdmaResult {
                rdma_scopeUpdateScope(scope, id);
                return RdmaResult::undefined();
            });
            return jsi::Value::undefined();
        });
}

jsi::Object makeScopeUpdateScopeProxyCAbi(jsi::Runtime& rt, void* scopeHandle) {
    static jsi::Object* proto = nullptr; // shared, intentionally leaked
    if (!proto) proto = new jsi::Object(rdmaLazyProto(rt, resolveScopeMethodCAbi));
    jsi::Object obj = jsi::Object::create(rt, jsi::Value(rt, *proto));
    obj.setNativeState(rt, std::make_shared<ScopeProxyStateCAbi>(scopeHandle));
    return obj;
}

// ------------------------------------------------------------ collections
// List/map proxies live on shared prototypes (RdmaCollectionsCAbi.cpp,
// docs/design/runtime_rework.md §3).

// Element type is a recursive descriptor: primitives / @RDMA FQN, or a nested
// `list:<elem>` for a list-of-lists.
static bool isListElementType(const std::string& elementType) {
    return elementType.rfind("list:", 0) == 0;
}

static std::string nestedElementType(const std::string& elementType) {
    return elementType.substr(5);
}

// State-backed collection ops obey the state-value channel's thread contract
// (`StateProxyHostCAbi`): during composition the op runs inside the UI thread's
// active composition snapshot via the rendezvous — the kernel snapshot record
// must belong to the snapshot the composing pass can read/write; outside
// composition writes are posted to the main queue so the Compose snapshot system
// records them and the Recomposer schedules, and reads run directly. A plain
// (non-state) collection runs directly. Returns the shim call's result; a
// fire-and-forget posted write comes back as `Empty`.
RdmaResult rdmaDispatchStateOpCAbi(bool stateBacked, bool write, std::function<RdmaResult()> op) {
    if (!stateBacked) return op();
    if (g_inComposition) return rdmaCallUi(op);
    if (write) {
        dispatch_async(dispatch_get_main_queue(), ^{ RdmaResult res = op(); (void)res; });
        return RdmaResult::empty();
    }
    return op();
}

// -------------------------------------------------------------------- map host

static bool isMapElementType(const std::string& t) {
    return t.rfind("map:", 0) == 0;
}

// Parses a `map:<key>:<value>` descriptor body (the part after `map:`) into the
// key and value descriptors. Mirrors the Kotlin descriptor grammar.
static std::pair<std::string, std::string> parseMapBody(const std::string& s) {
    if (s.rfind("list:", 0) == 0) {
        auto inner = parseMapBody(s.substr(5));
        return {"list:" + inner.first, inner.second};
    }
    if (s.rfind("map:", 0) == 0) {
        auto k = parseMapBody(s.substr(4));
        auto v = parseMapBody(k.second);
        return {"map:" + k.first + ":" + v.first, v.second};
    }
    size_t idx = s.find(':');
    if (idx == std::string::npos) return {s, ""};
    return {s.substr(0, idx), s.substr(idx + 1)};
}

// Queries the plugin-side `RDMA.mapKind`: 0 = LinkedHashMap, 1 = HashMap,
// 2 = emptyMap, 3 = unsupported. Returns -1 when the classifier is not installed.
static int mapKindOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    auto fn = rdma.asObject(rt).getProperty(rt, "mapKind");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return -1;
    auto res = fn.asObject(rt).asFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

// Extracts entries of a plugin-side Kotlin/JS Map into a JS array of [key, value]
// pairs via `RDMA.mapToEntries`. Returns an empty array when the helper is absent.
static jsi::Array mapToEntries(jsi::Runtime& rt, const jsi::Object& obj) {
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return jsi::Array(rt, 0);
    auto fn = rdma.asObject(rt).getProperty(rt, "mapToEntries");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return jsi::Array(rt, 0);
    auto res = fn.asObject(rt).asFunction(rt).call(rt, jsi::Value(rt, obj));
    if (res.isObject() && res.asObject(rt).isArray(rt)) return res.asObject(rt).asArray(rt);
    return jsi::Array(rt, 0);
}

void rdmaRegisterSuspendResultType(int64_t completionId, const std::string& typeDesc) {
    auto it = g_suspendContinuations.find(completionId);
    if (it != g_suspendContinuations.end()) {
        it->second.typeDesc = typeDesc;
    }
}

// Materializes a JS map (Kotlin/JS LinkedHashMap/HashMap) into a native Map handle
// WITHOUT morphing or kind classification. Shared by materializeMap (generated)
// and jsiToHandle's nested-map branch.
void* buildMapHandleFromJs(jsi::Runtime& rt, jsi::Object& obj, const std::string& keyType, const std::string& valueType) {
    auto entries = mapToEntries(rt, obj);
    size_t n = entries.size(rt);
    std::vector<void*> keys;
    std::vector<void*> values;
    keys.reserve(n);
    values.reserve(n);
    for (size_t i = 0; i < n; i++) {
        auto pair = entries.getValueAtIndex(rt, i);
        if (!pair.isObject() || !pair.asObject(rt).isArray(rt)) continue;
        auto arr = pair.asObject(rt).asArray(rt);
        if (arr.size(rt) < 2) continue;
        keys.push_back(jsiToHandle(rt, arr.getValueAtIndex(rt, 0), keyType));
        values.push_back(jsiToHandle(rt, arr.getValueAtIndex(rt, 1), valueType));
    }
    return rdma_mapCreate(keys.data(), values.data(), (int32_t)keys.size());
}

void* jsiToHandle(jsi::Runtime& rt, const jsi::Value& v, const std::string& elementType) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (isListElementType(elementType)) {
        // Nested list: reuse a proxy's native list, else copy the elements (read
        // through the public `toArray()`, never through stdlib internals).
        if (!v.isObject()) return nullptr;
        if (void* h = rdmaListHandleOf(rt, v)) return rdma_cloneHandle(h);
        auto obj = v.asObject(rt);
        const std::string nested = nestedElementType(elementType);
        jsi::Array elems(rt, 0);
        if (obj.isArray(rt)) {
            elems = obj.asArray(rt);
        } else {
            jsi::Value toArray = obj.getProperty(rt, "toArray");
            if (!toArray.isObject() || !toArray.asObject(rt).isFunction(rt)) return nullptr;
            elems = toArray.asObject(rt).asFunction(rt).callWithThis(rt, obj, nullptr, 0).asObject(rt).asArray(rt);
        }
        size_t n = elems.size(rt);
        std::vector<void*> handles;
        handles.reserve(n);
        for (size_t i = 0; i < n; i++) {
            handles.push_back(jsiToHandle(rt, elems.getValueAtIndex(rt, i), nested));
        }
        return rdma_listCreate(handles.data(), (int32_t)handles.size());
    }
    if (isMapElementType(elementType)) {
        if (!v.isObject()) return nullptr;
        if (void* h = rdmaMapHandleOf(rt, v)) return rdma_cloneHandle(h);
        auto obj = v.asObject(rt);
        auto kv = parseMapBody(elementType.substr(4));
        return buildMapHandleFromJs(rt, obj, kv.first, kv.second);
    }
    // Leaf: the descriptor's declared type (the `?`/`!` marks stripped first —
    // `MyEnum!` is an enum leaf, `kotlin.Long?` is a nullable Long). Scalar/enum
    // leaves follow the DESCRIPTOR, not the value's shape: a foreign value raises
    // instead of being coerced (the JNI `jsiToJobject` follows the same
    // descriptor-first order — the two backends must not drift).
    std::string fqn = elementType.substr(0, elementType.find_first_of("?!"));
    bool isEnum = elementType.find('!') != std::string::npos;
    if (isEnum) {
        // The entry crosses as its name string (types.md): rebuild it kernel-side
        // through the enum's `valueOf` bridge, so an invalid name surfaces as the
        // kernel's error instead of a wrong entry.
        if (!v.isString()) throwTypeErrorCAbi(rt, "expected the enum entry name string for " + fqn);
        return enumValueOfCAbi(rt, fqn, v.getString(rt).utf8(rt));
    }
    void* scalar = boxLeafValueCAbi(rt, v, fqn);
    if (scalar) return scalar;
    if (v.isObject()) {
        if (auto objState = rdmaStateAs<RdmaObjectNativeStateCAbi>(rt, v, RdmaStateKind::Object)) {
            if (objState->handle()) return rdma_cloneHandle(objState->handle());
        }
        if (void* h = rdmaListHandleOf(rt, v)) return rdma_cloneHandle(h);
        if (void* h = rdmaMapHandleOf(rt, v)) return rdma_cloneHandle(h);
    }
    // Unknown leaves keep the runtime-dispatch boxer (pre-descriptor behavior,
    // e.g. callback-id lists) — the same fallback as the JNI `jsiToJobject`.
    return boxJsiValue(rt, v);
}

// ------------------------------------------------------------- content/scope

void invokeScopeBlock(jsi::Runtime& rt, int64_t blockId, int32_t changed) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        block->call(rt, proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

void invokeScopeBlock1(jsi::Runtime& rt, int64_t blockId, int32_t changed, int32_t p1) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        block->call(rt, jsi::Value((double)p1), proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

// ------------------------------------------------------------- components

namespace {
struct ComponentRegistry {
    std::mutex mutex;
    std::unordered_map<std::string, int64_t> ids; // name -> retained block
};
ComponentRegistry& componentRegistry() {
    static ComponentRegistry r;
    return r;
}
using ComponentsChangedFn = void (*)();
} // namespace

int64_t rdmaComponentBlockCAbi(const std::string& name) {
    auto& reg = componentRegistry();
    std::lock_guard<std::mutex> lk(reg.mutex);
    auto it = reg.ids.find(name);
    return it == reg.ids.end() ? 0 : it->second;
}

static void registerComponentBlock(jsi::Runtime& rt, const std::string& name, const std::string& paramDescs,
                                   const jsi::Function& fn, const std::string& pluginId) {
    int64_t id = rdmaBlockRegisterPlugin(rt, fn, pluginId);
    rdmaBlockSetParamDescs(id, paramDescs);
    rdmaBlockRetain(id);
    int64_t old = 0;
    {
        auto& reg = componentRegistry();
        std::lock_guard<std::mutex> lk(reg.mutex);
        auto it = reg.ids.find(name);
        if (it != reg.ids.end()) old = it->second;
        reg.ids[name] = id;
    }
    if (old) rdmaBlockRelease(old);
    // Hosts that want new code at once (not at the next appearance) key on this.
    g_contentVersion.fetch_add(1, std::memory_order_release);
    LOGI("Component registered: %s", name.c_str());
    static auto changed = (ComponentsChangedFn)rdma_lookupFunction("rdma_onComponentsChanged");
    if (changed) changed();
}

// Runs component block `blockId` against the current composer (held by the Kotlin host).
// CONSUMES `argsHandle`.
void invokeComponentCAbi(jsi::Runtime& rt, int64_t blockId, void* argsHandle) {
    auto block = rdmaBlockGet(blockId);
    if (!block) {
        if (argsHandle) rdma_disposeStableRef(argsHandle);
        return;
    }
    std::vector<std::string> descs = rdmaBlockParamDescs(blockId);
    std::vector<jsi::Value> callArgs;
    if (argsHandle) {
        int32_t n = rdma_arraySize(argsHandle);
        for (int32_t i = 0; i < n; i++) {
            void* elem = rdma_arrayGet(argsHandle, i);
            const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
            callArgs.push_back(typed ? rdmaWrapTypedHandleCAbi(rt, elem, descs[(size_t)i]) : wrapAnyCAbi(rt, nullptr, elem));
        }
        rdma_disposeStableRef(argsHandle);
    }
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    callArgs.push_back(jsi::Value(rt, makeComposerProxy(rt)));
    callArgs.push_back(jsi::Value(0));
    try {
        block->call(rt, static_cast<const jsi::Value*>(callArgs.data()), callArgs.size());
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

// C strings returned by the Kotlin shim live in a per-thread arena that is cleared at
// these points, where no such pointer is in use any more (docs/design/runtime_rework.md §4.6).
void rdmaPlatformAfterJsTask() { rdma_clearStringArena(); }
void rdmaPlatformAfterUiTask() { rdma_clearStringArena(); }

// --------------------------------------------------------- callback/lambda

// Invokes a plugin callback/lambda block with kernel arguments. Arguments are
// marshalled by the block's parameter descriptors when the bridge registered them
// (a `List`/`Map` argument becomes a collection proxy), else by runtime shape.
static void runCallbackOrLambda(jsi::Runtime& rt, int64_t blockId, void* argsHandle) {
    auto block = rdmaBlockGet(blockId);
    if (!block) {
        if (argsHandle) rdma_disposeStableRef(argsHandle);
        return;
    }
    std::vector<jsi::Value> jsArgs;
    if (argsHandle) {
        std::vector<std::string> descs = rdmaBlockParamDescs(blockId);
        int32_t n = rdma_arraySize(argsHandle);
        jsArgs.reserve(n);
        for (int32_t i = 0; i < n; i++) {
            void* elem = rdma_arrayGet(argsHandle, i);
            const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
            jsArgs.push_back(typed ? rdmaWrapTypedHandleCAbi(rt, elem, descs[(size_t)i]) : wrapAnyCAbi(rt, nullptr, elem));
        }
        rdma_disposeStableRef(argsHandle);
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    try {
        block->call(rt, callArgs, jsArgs.size());
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), rdmaBlockPluginId(blockId));
    }
}

// ------------------------------------------------- hotfix slots (rework §6)

static std::vector<std::string> splitSlotDescs(const std::string& s) {
    std::vector<std::string> out;
    if (s.empty()) return out;
    size_t start = 0;
    while (true) {
        size_t comma = s.find(',', start);
        out.push_back(s.substr(start, comma == std::string::npos ? std::string::npos : comma - start));
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return out;
}

// Runs a hotfix JS implementation with kernel arguments (marshalled by the callable's
// parameter descriptors). CONSUMES `argsHandle`; returns a NEW handle to the
// kernel-typed result (null for Unit/null). Throws jsi::JSError.
static void* callJsTypedCAbi(jsi::Runtime& rt, const std::shared_ptr<jsi::Function>& fn, void* argsHandle,
                             const std::string& paramDescs, const std::string& resultDesc);

static void* runSlotCAbi(jsi::Runtime& rt, int64_t blockId, void* argsHandle,
                         const std::string& paramDescs, const std::string& resultDesc) {
    return callJsTypedCAbi(rt, rdmaBlockGet(blockId), argsHandle, paramDescs, resultDesc);
}

// Calls a JS function with kernel arguments marshalled by `paramDescs`; converts the result
// by `resultDesc`. CONSUMES `argsHandle`; returns a NEW handle (null for Unit/null).
// Throws jsi::JSError. Runs on the Hermes thread.
static void* callJsTypedCAbi(jsi::Runtime& rt, const std::shared_ptr<jsi::Function>& fn, void* argsHandle,
                             const std::string& paramDescs, const std::string& resultDesc) {
    std::vector<jsi::Value> jsArgs;
    if (argsHandle) {
        std::vector<std::string> descs = splitSlotDescs(paramDescs);
        int32_t n = rdma_arraySize(argsHandle);
        jsArgs.reserve(n);
        for (int32_t i = 0; i < n; i++) {
            void* elem = rdma_arrayGet(argsHandle, i);
            const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
            jsArgs.push_back(typed ? rdmaWrapTypedHandleCAbi(rt, elem, descs[(size_t)i]) : wrapAnyCAbi(rt, nullptr, elem));
        }
        rdma_disposeStableRef(argsHandle);
    }
    if (!fn) throw jsi::JSError(rt, "hotfix implementation is no longer registered");
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    jsi::Value result = fn->call(rt, callArgs, jsArgs.size());
    if (resultDesc.empty() || resultDesc == "kotlin.Unit") return nullptr;
    return jsiToHandle(rt, result, resultDesc);
}

// ------------------------------------------- kernel lambdas handed to JS

namespace {
// Owns the StableRef of a kernel lambda wrapped as a JS host function; released when
// Hermes collects the JS function.
struct KotlinLambdaHolder {
    void* handle;
    explicit KotlinLambdaHolder(void* h) : handle(h) {}
    KotlinLambdaHolder(const KotlinLambdaHolder&) = delete;
    KotlinLambdaHolder& operator=(const KotlinLambdaHolder&) = delete;
    ~KotlinLambdaHolder() {
        if (handle) rdma_disposeStableRef(handle);
    }
};
using FunctionBlockIdFn = int64_t (*)(void*);
using InvokeFunctionFn = void* (*)(void*, int32_t, void**);
} // namespace

jsi::Value rdmaWrapNativeFunctionCAbi(jsi::Runtime& rt, void* handle, const std::string& paramDescs,
                                      const std::string& resultDesc) {
    if (!handle) return jsi::Value::null();
    static auto blockIdOf = (FunctionBlockIdFn)rdma_lookupFunction("rdma_functionBlockId");
    static auto invoke = (InvokeFunctionFn)rdma_lookupFunction("rdma_invokeFunction");
    // A plugin lambda that went through the kernel comes back as the plugin's function.
    if (int64_t id = blockIdOf ? blockIdOf(handle) : 0) {
        if (auto js = rdmaBlockGet(id)) {
            rdma_disposeStableRef(handle);
            return jsi::Value(rt, *js);
        }
    }
    std::vector<std::string> descs = splitSlotDescs(paramDescs);
    if (descs.size() > 3 || !invoke) {
        rdma_disposeStableRef(handle);
        throw jsi::JSError(rt, "a kernel lambda with " + std::to_string(descs.size()) + " parameters cannot cross (at most 3)");
    }
    auto holder = std::make_shared<KotlinLambdaHolder>(handle);
    return jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "rdmaKernelLambda"), (unsigned)descs.size(),
        [holder, descs, resultDesc](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            std::vector<void*> argv(descs.size(), nullptr);
            for (size_t i = 0; i < descs.size(); i++) {
                argv[i] = i < count ? jsiToHandle(r, args[i], descs[i]) : nullptr;
            }
            void* res = invoke(holder->handle, (int32_t)argv.size(), argv.empty() ? nullptr : argv.data());
            checkKotlinError(r);
            if (resultDesc.empty() || resultDesc == "kotlin.Unit") {
                if (res) rdma_disposeStableRef(res);
                return jsi::Value::undefined();
            }
            return rdmaWrapTypedHandleCAbi(r, res, resultDesc);
        });
}

// ------------------------------------------- global uncaught handler (H2)

// Thread-local buffers backing the C strings passed to the Kotlin dispatcher; they
// remain valid for the duration of the synchronous rdma_dispatchUncaught call.
thread_local std::string g_tlsUncaughtPid;
thread_local std::string g_tlsUncaughtType;
thread_local std::string g_tlsUncaughtMsg;
thread_local std::string g_tlsUncaughtStack;

// Global (design §6) handler installed by the host via
// rdma_set_uncaught_handler. Serializes the exception and forwards it to the Kotlin
// dispatcher rdma_dispatchUncaught(pluginId, type, message, stack).
void cAbiUncaughtHandler(const RdmaExceptionInfo& info, const std::string& pluginId) {
    using Dispatcher = void (*)(const char*, const char*, const char*, const char*);
    void* fn = rdma_lookupFunction("rdma_dispatchUncaught");
    if (!fn) {
        LOGW("Uncaught plugin exception [%s] (%s): %s (no iOS dispatcher)",
             pluginId.c_str(), info.originalExceptionType.c_str(), info.message.c_str());
        return;
    }
    g_tlsUncaughtPid = pluginId;
    g_tlsUncaughtType = info.originalExceptionType;
    g_tlsUncaughtMsg = info.message;
    g_tlsUncaughtStack = info.stack;
    ((Dispatcher)fn)(g_tlsUncaughtPid.c_str(), g_tlsUncaughtType.c_str(),
                     g_tlsUncaughtMsg.c_str(), g_tlsUncaughtStack.c_str());
}

void rdmaInstallUncaughtHandlerCAbi() {
    rdmaSetUncaughtExceptionHandler(cAbiUncaughtHandler);
}

// ------------------------------------------------------------ init (JSI only)

void installRdmaComposeBridge(jsi::Runtime& rt, HostContext ctx) {
    jsi::Object rdma(rt);

    auto registerComponentFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerComponent"), 4,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 3 || !args[0].isString() || !args[2].isObject() || !args[2].asObject(r).isFunction(r)) {
                throw jsi::JSError(r, "registerComponent(name, paramDescs, content, pluginId): bad arguments");
            }
            std::string descs = args[1].isString() ? args[1].getString(r).utf8(r) : "";
            std::string pid = (count > 3 && args[3].isString()) ? args[3].getString(r).utf8(r) : "";
            registerComponentBlock(r, args[0].getString(r).utf8(r), descs, args[2].asObject(r).asFunction(r), pid);
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "registerComponent", std::move(registerComponentFn));

    auto setEmptyFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "setComposerEmpty"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count > 0 && args[0].isObject()) {
                g_empty = std::make_shared<jsi::Object>(args[0].asObject(r));
            }
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "setComposerEmpty", std::move(setEmptyFn));

    auto mutableStateOfFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "mutableStateOf"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            // The channel's value type descriptor is baked in by the plugin bridge
            // (docs/design/type_descriptors.md) and fixed at channel creation.
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "kotlin.Any";
            TypeDesc desc = TypeDesc::parseOrAny(descRaw);
            void* boxed = boxStateValueCAbi(r, args[0], desc);
            // Typed channels accept `null` initial values on nullable states. The
            // shim consumes the boxed initial (the state keeps the value object).
            RdmaResult result = rdmaCallUi([boxed, descRaw]() -> RdmaResult {
                void* state = rdma_mutableStateOf(boxed, descRaw.c_str());
                if (boxed) rdma_disposeStableRef(boxed);
                if (!state) return RdmaResult::undefined();
                return RdmaResult::stateRef(state, descRaw);
            });
            return rdmaResultToJsiCAbi(r, result);
        });
    rdma.setProperty(rt, "mutableStateOf", std::move(mutableStateOfFn));

    // Snapshot collections (docs/design/type_descriptors.md#state-semantics): the
    // guest passes its materialized values; the kernel copies them into a
    // `SnapshotStateList`/`SnapshotStateMap` and returns a state-backed proxy, so
    // mutation through the proxy schedules a recomposition. The element typing
    // rides in the channel's type descriptor (`list:<elem>`, `map:<k>:<v>`).
    auto mutableStateListOfFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "mutableStateListOf"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "list:kotlin.Any";
            TypeDesc d = TypeDesc::parseOrAny(descRaw);
            std::string elemDesc = (d.kind == TypeDesc::LIST && d.elem) ? d.elem->serialize() : "kotlin.Any";
            void* source = jsiToHandle(r, args[0], "list:" + elemDesc);
            // The snapshot collection is created inside the UI thread's active
            // compose world — the same contract as the `mutableStateOf` factory:
            // a record created on the JS thread's implicit writer lives in a
            // not-yet-applied snapshot and the composition's reads reject it
            // (docs/design/type_descriptors.md#state-semantics).
            void* snap = nullptr;
            RdmaResult result = rdmaCallUi([source, &snap]() -> RdmaResult {
                snap = rdma_mutableStateListOf(source);
                if (source) rdma_disposeStableRef(source);
                RdmaResult captured;
                if (captureCAbiError(captured)) return captured;
                return RdmaResult::undefined();
            });
            if (result.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, result);
            if (!snap) return jsi::Value::undefined();
            return rdmaCreateListHandleCAbi(r, snap, elemDesc, /*stateBacked=*/true);
        });
    rdma.setProperty(rt, "mutableStateListOf", std::move(mutableStateListOfFn));

    auto mutableStateMapOfFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "mutableStateMapOf"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "map:kotlin.Any:kotlin.Any";
            TypeDesc d = TypeDesc::parseOrAny(descRaw);
            std::string keyDesc = (d.kind == TypeDesc::MAP && d.key) ? d.key->serialize() : "kotlin.Any";
            std::string valueDesc = (d.kind == TypeDesc::MAP && d.value) ? d.value->serialize() : "kotlin.Any";
            void* source = jsiToHandle(r, args[0], "map:" + keyDesc + ":" + valueDesc);
            // Created inside the UI thread's active compose world (see the list
            // factory above and `mutableStateOf`).
            void* snap = nullptr;
            RdmaResult result = rdmaCallUi([source, &snap]() -> RdmaResult {
                snap = rdma_mutableStateMapOf(source);
                if (source) rdma_disposeStableRef(source);
                RdmaResult captured;
                if (captureCAbiError(captured)) return captured;
                return RdmaResult::undefined();
            });
            if (result.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, result);
            if (!snap) return jsi::Value::undefined();
            return rdmaCreateMapHandleCAbi(r, snap, keyDesc, valueDesc, /*stateBacked=*/true);
        });
    rdma.setProperty(rt, "mutableStateMapOf", std::move(mutableStateMapOfFn));

    auto registerBlockFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerBlock"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            // Owned by the kernel holders of the id (docs/design/runtime_rework.md §4);
            // the same function registered again keeps its id.
            std::string pluginId = (count > 1 && args[1].isString()) ? args[1].getString(r).utf8(r) : "";
            int64_t id = rdmaBlockRegisterPlugin(r, args[0].asObject(r).asFunction(r), pluginId);
            return jsi::Value((double)id);
        });
    rdma.setProperty(rt, "registerBlock", std::move(registerBlockFn));

    // Hotfix slot registration (docs/design/runtime_rework.md §6.3): a polyfill bundle
    // calls `RDMA.installSlot?.("<fqn>", fn)` next to `RDMA.<name> = fn`, so native
    // callers of the callable run the JS implementation too.
    auto installSlotFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "installSlot"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isString() || !args[1].isObject() || !args[1].asObject(r).isFunction(r)) {
                return jsi::Value(false);
            }
            using InstallFn = int64_t (*)(const char*, int64_t);
            static auto install = (InstallFn)rdma_lookupFunction("rdma_installSlot");
            if (!install) install = (InstallFn)rdma_lookupFunction("rdma_installSlot");
            if (!install) return jsi::Value(false);
            std::string fqn = args[0].getString(r).utf8(r);
            int64_t id = rdmaBlockRegisterExplicit(
                std::make_shared<jsi::Function>(args[1].asObject(r).asFunction(r)), "hotfix");
            rdmaBlockRetain(id);
            int64_t previous = install(fqn.c_str(), id);
            if (previous != 0) rdmaBlockRelease(previous);
            return jsi::Value(true);
        });
    rdma.setProperty(rt, "installSlot", std::move(installSlotFn));

    auto registerContinuationFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerContinuation"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isObject() || !args[0].asObject(r).isFunction(r) ||
                !args[1].isObject() || !args[1].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            SuspendContinuation cont;
            cont.onResult = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
            cont.onError = std::make_shared<jsi::Function>(args[1].asObject(r).asFunction(r));
            int64_t id = g_nextContinuationId++;
            g_suspendContinuations[id] = std::move(cont);
            return jsi::Value((double)id);
        });
    rdma.setProperty(rt, "registerContinuation", std::move(registerContinuationFn));

    // The plugin cancelled the coroutine waiting for completion `id`: cancel the kernel call.
    auto cancelContinuationFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "cancelContinuation"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
            using CancelFn = void (*)(int64_t);
            static auto cancel = (CancelFn)rdma_lookupFunction("rdma_cancelSuspend");
            if (cancel) cancel((int64_t)args[0].getNumber());
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "cancelContinuation", std::move(cancelContinuationFn));

    auto sideEffectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "sideEffect"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
            int64_t blockId = (int64_t)args[0].getNumber();
            rdmaCallUi([blockId]() -> RdmaResult {
                rdma_sideEffect(blockId);
                return RdmaResult::undefined();
            });
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "sideEffect", std::move(sideEffectFn));

    auto disposableEffectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "disposableEffect"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isObject() || !args[0].asObject(r).isArray(r) ||
                !args[1].isObject() || !args[1].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            auto fn = std::make_shared<jsi::Function>(args[1].asObject(r).asFunction(r));
            int64_t id = rdmaBlockRegisterExplicit(fn);

            auto keysArr = args[0].asObject(r).asArray(r);
            size_t n = keysArr.size(r);
            std::vector<void*> keys;
            keys.reserve(n);
            for (size_t i = 0; i < n; i++) {
                keys.push_back(boxKeyForRemember(r, keysArr.getValueAtIndex(r, i)));
            }
            void* keysHandle = rdma_arrayCreate(keys.data(), (int32_t)keys.size());
            for (void* k : keys) {
                if (k) rdma_disposeStableRef(k);
            }

            RdmaResult result = rdmaCallUi([id, keysHandle]() -> RdmaResult {
                bool used = rdma_disposableEffect(keysHandle, id);
                if (keysHandle) rdma_disposeStableRef(keysHandle);
                return RdmaResult::boolean(used);
            });

            if (!result.b) {
                rdmaBlockErase(id);
            }
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "disposableEffect", std::move(disposableEffectFn));

    if (g_userBridge) {
        g_userBridge(rt, ctx, rdma);
    }

    rt.global().setProperty(rt, "RDMA", std::move(rdma));
    LOGI("Compose bridge installed (C ABI)");
}

// Resumes the plugin suspend continuation with a marshalled result. Runs on the
// Hermes thread; `valueHandle` is a StableRef consumed here (transferred into the
// proxy host object or disposed after unboxing).
static void runSuspendResult(jsi::Runtime& rt, int64_t completionId, void* valueHandle) {
    auto it = g_suspendContinuations.find(completionId);
    if (it == g_suspendContinuations.end()) {
        if (valueHandle) rdma_disposeStableRef(valueHandle);
        return;
    }
    auto onResult = it->second.onResult;
    std::string desc = it->second.typeDesc;
    g_suspendContinuations.erase(it);
    try {
        jsi::Value v = wrapTypedHandle(rt, valueHandle, desc);
        const jsi::Value* callArgs = &v;
        onResult->call(rt, callArgs, static_cast<size_t>(1));
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), "");
    }
}

// Resumes the plugin suspend continuation with an error. Runs on the Hermes
// thread; the description mirrors Android's RdmaExceptionInfo so the plugin sees
// the same `message`/`name` (originalExceptionType) shape on both platforms.
static void runSuspendError(jsi::Runtime& rt, int64_t completionId, const RdmaExceptionInfo& info) {
    auto it = g_suspendContinuations.find(completionId);
    if (it == g_suspendContinuations.end()) return;
    auto onError = it->second.onError;
    g_suspendContinuations.erase(it);
    try {
        jsi::Object err = makeJsErrorObject(rt, info);
        jsi::Value errValue(rt, err);
        const jsi::Value* callArgs = &errValue;
        onError->call(rt, callArgs, static_cast<size_t>(1));
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), "");
    }
}

} // namespace rdma
} // namespace facebook

// ------------------------------------------------------- C ABI entry points

namespace {
thread_local std::string g_tlsCaughtError;
thread_local std::string g_tlsCaughtErrorBuf;

static void appendCauseChain(std::string& out, const facebook::rdma::RdmaExceptionInfo& info) {
    for (auto c = info.cause; c; c = c->cause) {
        out += "\nCaused by: ";
        out += c->message.empty() ? c->originalExceptionType : c->message;
        if (!c->stack.empty()) {
            out += "\n";
            out += c->stack;
        }
    }
}

void storeCaughtError(const facebook::rdma::RdmaExceptionInfo& info) {
    g_tlsCaughtError = info.message.empty() ? info.originalExceptionType : info.message;
    if (!info.stack.empty()) {
        g_tlsCaughtError += "\n";
        g_tlsCaughtError += info.stack;
    }
    appendCauseChain(g_tlsCaughtError, info);
}
} // namespace

namespace {
// One native → JS call executed on the Hermes thread for a waiting caller (heap state:
// a timed-out caller may return before the task runs or finishes).
struct CAbiJsCall {
    void* args = nullptr;   // consumed by `run`
    void* result = nullptr; // produced by `run`
    facebook::rdma::RdmaExceptionInfo error;
    bool ran = false;
    void releaseAll() {
        if (args) { rdma_disposeStableRef(args); args = nullptr; }
        if (result) { rdma_disposeStableRef(result); result = nullptr; }
    }
};
} // namespace

// Runs `run` on the Hermes thread for the caller. Returns the result handle; `*status`:
// 1 = the runtime is not up (fall through), 2 = error stored for rdma_takeNativeError.
// CONSUMES `argsHandle`.
static void* callJsForCAbi(void* argsHandle, int32_t* status, const std::string& what,
                           std::function<void*(facebook::jsi::Runtime&, void*)> run) {
    using namespace facebook::rdma;
    auto call = std::make_shared<CAbiJsCall>();
    call->args = argsHandle;
    RdmaExceptionInfo waitErr = rdmaCallJsBlocking(
        [call, run] {
            facebook::jsi::Runtime* rt = getRdmaRuntime();
            if (!rt) return;
            call->ran = true;
            void* args = call->args;
            call->args = nullptr; // `run` consumes it
            try {
                call->result = run(*rt, args);
            } catch (const facebook::jsi::JSError& e) {
                call->error = fromJSError(*rt, e);
            }
        },
        [call] { call->releaseAll(); },
        what);
    if (!waitErr.empty()) {
        storeCaughtError(waitErr);
        *status = 2;
        return nullptr;
    }
    if (!call->error.empty()) {
        call->releaseAll();
        storeCaughtError(call->error);
        *status = 2;
        return nullptr;
    }
    if (!call->ran) {
        call->releaseAll();
        *status = 1;
        return nullptr;
    }
    void* result = call->result;
    call->result = nullptr;
    return result;
}

extern "C" {

void rdma_nativeInvokeScopeBlock(int64_t blockId, int32_t changed) {
    using namespace facebook::rdma;
    RdmaExceptionInfo err = rdmaCallJs([blockId, changed] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeScopeBlock(*rt, blockId, changed);
    });
    if (!err.empty()) storeCaughtError(err);
}

void rdma_nativeInvokeScopeBlock1(int64_t blockId, int32_t changed, int32_t p1) {
    using namespace facebook::rdma;
    RdmaExceptionInfo err = rdmaCallJs([blockId, changed, p1] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeScopeBlock1(*rt, blockId, changed, p1);
    });
    if (!err.empty()) storeCaughtError(err);
}

int64_t rdma_componentBlock(const char* name) {
    return name ? facebook::rdma::rdmaComponentBlockCAbi(name) : 0;
}

void rdma_nativeInvokeComponent(int64_t blockId, void* argsHandle) {
    using namespace facebook::rdma;
    RdmaExceptionInfo err = rdmaCallJs([blockId, argsHandle] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeComponentCAbi(*rt, blockId, argsHandle);
        else if (argsHandle) rdma_disposeStableRef(argsHandle);
    });
    if (!err.empty()) storeCaughtError(err);
}

void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle) {
    using namespace facebook::rdma;
    rdmaPostJs([blockId, argsHandle] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) runCallbackOrLambda(*rt, blockId, argsHandle);
        else if (argsHandle) rdma_disposeStableRef(argsHandle);
    });
}

// A native caller of a hotfixed callable (rework §6.4, D5 = B): routed to the Hermes
// thread from any thread. `*status` 2 = error (a JS exception or a timeout).
void* rdma_nativeInvokeSlot(int64_t blockId, void* argsHandle, const char* paramDescs, const char* resultDesc, int32_t* status) {
    using namespace facebook::rdma;
    *status = 0;
    const std::string pd = paramDescs ? paramDescs : "";
    const std::string rd = resultDesc ? resultDesc : "";
    return callJsForCAbi(argsHandle, status, "hotfix #" + std::to_string(blockId),
                         [blockId, pd, rd](facebook::jsi::Runtime& rt, void* args) {
                             return runSlotCAbi(rt, blockId, args, pd, rd);
                         });
}

void rdma_nativeInvokeLambda(int64_t blockId, void* argsHandle) {
    using namespace facebook::rdma;
    rdmaPostJs([blockId, argsHandle] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) runCallbackOrLambda(*rt, blockId, argsHandle);
        else if (argsHandle) rdma_disposeStableRef(argsHandle);
    });
}

int64_t rdma_nativeInvokeEffectBody(int64_t blockId) {
    using namespace facebook::rdma;
    int64_t resultId = 0;
    RdmaExceptionInfo err = rdmaCallJs([blockId, &resultId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        auto fn = rdmaBlockGet(blockId);
        if (!fn) return;
        rdmaBlockErase(blockId);
        facebook::jsi::Value result = fn->call(*rt, nullptr, 0);
        if (result.isObject()) {
            auto obj = std::make_shared<facebook::jsi::Object>(result.asObject(*rt));
            resultId = g_nextJsValueId++;
            g_jsValues[resultId] = obj;
        }
    });
    if (!err.empty()) storeCaughtError(err);
    return resultId;
}

void rdma_nativeInvokeDispose(int64_t resultId) {
    using namespace facebook::rdma;
    RdmaExceptionInfo err = rdmaCallJs([resultId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        auto it = g_jsValues.find(resultId);
        if (it == g_jsValues.end()) return;
        facebook::jsi::Object& obj = *it->second;
        facebook::jsi::Value dispose = obj.getProperty(*rt, "dispose");
        if (dispose.isObject() && dispose.asObject(*rt).isFunction(*rt)) {
            dispose.asObject(*rt).asFunction(*rt).callWithThis(*rt, obj, nullptr, 0);
        }
        g_jsValues.erase(it);
    });
    if (!err.empty()) storeCaughtError(err);
}

void rdma_nativeInvokeFreeBlock(int64_t blockId) {
    using namespace facebook::rdma;
    rdmaCallJs([blockId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        rdmaBlockErase(blockId);
    });
}

// ------------------------------------------------- block holders (runtime_rework §4)

void rdma_nativeRetainBlock(int64_t blockId) { facebook::rdma::rdmaBlockRetain(blockId); }

void rdma_nativeReleaseBlock(int64_t blockId) { facebook::rdma::rdmaBlockRelease(blockId); }

void rdma_nativeSetBlockParamDescs(int64_t blockId, const char* descs) {
    facebook::rdma::rdmaBlockSetParamDescs(blockId, std::string(descs ? descs : ""));
}

int32_t rdma_nativeBlockCount(void) { return (int32_t)facebook::rdma::rdmaBlockCount(); }

void rdma_nativeReleaseJsValue(int64_t valueId) {
    using namespace facebook::rdma;
    // Released by a JsValueHolder leaving the composition; erased on the JS thread.
    rdmaPostJs([valueId] { g_jsValues.erase(valueId); });
}

void rdma_nativeInvokeContinuation(int64_t completionId, void* valueHandle) {
    using namespace facebook::rdma;
    rdmaPostJs([completionId, valueHandle] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runSuspendResult(*rt, completionId, valueHandle);
        } else if (valueHandle) {
            rdma_disposeStableRef(valueHandle);
        }
    });
}

// Parses the length-prefixed serialization produced by the Kotlin/Native shim
// (RdmaComposeHost.serializeThrowable): each level is
// `<typeLen>:<type><msgLen>:<msg><stackLen>:<stack><hasCause>` where `hasCause`
// is '1' (nested level follows) or '0'. `pos` advances through the shared buffer.
static facebook::rdma::RdmaExceptionInfo parseSerializedThrowable(const std::string& s, size_t& pos) {
    using namespace facebook::rdma;
    RdmaExceptionInfo info;
    auto readField = [&]() -> std::string {
        size_t colon = s.find(':', pos);
        if (colon == std::string::npos) return "";
        size_t len = 0;
        for (size_t i = pos; i < colon; i++) {
            len = len * 10 + static_cast<size_t>(s[i] - '0');
        }
        pos = colon + 1;
        if (pos + len > s.size()) return "";
        std::string v = s.substr(pos, len);
        pos += len;
        return v;
    };
    info.originalExceptionType = readField();
    info.message = readField();
    info.stack = readField();
    if (pos < s.size()) {
        char flag = s[pos++];
        if (flag == '1') {
            info.cause = std::make_shared<RdmaExceptionInfo>(parseSerializedThrowable(s, pos));
        }
    }
    return info;
}

void rdma_nativeInvokeContinuationError(int64_t completionId, const char* serialized) {
    using namespace facebook::rdma;
    std::string s = serialized ? serialized : "";
    RdmaExceptionInfo info;
    if (!s.empty()) {
        size_t pos = 0;
        info = parseSerializedThrowable(s, pos);
    }
    rdmaPostJs([completionId, info] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runSuspendError(*rt, completionId, info);
        }
    });
}

void rdma_start(void* ctx) {
    facebook::rdma::rdmaStart(ctx);
}

void rdma_set_uncaught_handler(void) {
    facebook::rdma::rdmaInstallUncaughtHandlerCAbi();
}

int32_t rdma_is_ready(void) {
    return facebook::rdma::rdmaIsReady() ? 1 : 0;
}

void rdma_eval_bytes(const void* code, int32_t len) {
    if (!code || len <= 0) return;
    facebook::rdma::rdmaEvalAsset(std::string((const char*)code, (size_t)len));
}

void rdma_eval_file(const char* path) {
    if (!path) return;
    facebook::rdma::rdmaEvalFile(std::string(path));
}

void rdma_eval_files(const char* newlineSeparatedPaths) {
    if (!newlineSeparatedPaths) return;
    std::vector<std::string> paths;
    std::string all(newlineSeparatedPaths);
    size_t start = 0;
    while (start <= all.size()) {
        size_t end = all.find('\n', start);
        if (end == std::string::npos) end = all.size();
        if (end > start) paths.emplace_back(all.substr(start, end - start));
        start = end + 1;
    }
    facebook::rdma::rdmaEvalFiles(paths);
}

int32_t rdma_verify_ed25519(const void* message, int32_t length, const void* signature, const void* publicKey) {
    if (length < 0) return 0;
    return facebook::rdma::rdmaVerifyEd25519((const uint8_t*)message, (size_t)length, (const uint8_t*)signature, (const uint8_t*)publicKey) ? 1 : 0;
}

int32_t rdma_content_version(void) {
    return facebook::rdma::g_contentVersion.load(std::memory_order_acquire);
}

const char* rdma_takeNativeError(void) {
    if (g_tlsCaughtError.empty()) return nullptr;
    g_tlsCaughtErrorBuf = g_tlsCaughtError;
    g_tlsCaughtError.clear();
    return g_tlsCaughtErrorBuf.c_str();
}

const char* rdma_takeEvalError(void) {
    static thread_local std::string buf;
    facebook::rdma::RdmaExceptionInfo info;
    if (!rdmaTakeEvalError(info)) return nullptr;
    buf = info.message.empty() ? info.originalExceptionType : info.message;
    if (!info.stack.empty()) {
        buf += "\n";
        buf += info.stack;
    }
    return buf.c_str();
}

// A native call of an `open` method overridden by a plugin subclass (the vtable), from
// any thread (§6.4); the result is converted by the method's result descriptor; a JS
// exception or a timeout is an error (§2.8). `*status`: 1 = not overridden (fall
// through to the native body), 2 = error. CONSUMES `argsHandle`.
void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId, void* argsHandle, const char* paramDescs,
                          const char* resultDesc, int32_t* status) {
    using namespace facebook::rdma;
    *status = 0;
    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    if (!vt || vtableId < 0 || (size_t)vtableId >= vt->entries.size() || !vt->entries[(size_t)vtableId]) {
        if (argsHandle) rdma_disposeStableRef(argsHandle);
        *status = 1;
        return nullptr;
    }
    std::shared_ptr<facebook::jsi::Function> fn = vt->entries[(size_t)vtableId];
    const std::string pd = paramDescs ? paramDescs : "";
    const std::string rd = resultDesc ? resultDesc : "";
    return callJsForCAbi(argsHandle, status, "override #" + std::to_string(vtableId),
                         [fn, pd, rd](facebook::jsi::Runtime& rt, void* args) {
                             return callJsTypedCAbi(rt, fn, args, pd, rd);
                         });
}

} // extern "C"
