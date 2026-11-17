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
#include "RdmaComposeCAbi.h"
#include "RdmaComposerProxy.h"
#include "RdmaRuntime.h"
#include "RdmaTypeDesc.h"
#include "RdmaJsLong.h"
#include "RdmaVtable.h"
#include "RdmaLog.h"

#include <jsi/jsi.h>
#include <string>
#include <vector>
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

static std::shared_ptr<jsi::Function> g_content;
static std::atomic<int> g_contentVersion{0};
std::shared_ptr<jsi::Object> g_empty;
static std::unordered_map<int64_t, std::shared_ptr<jsi::Function>> g_scopeBlocks;
static int64_t g_nextScopeBlockId = 1;
std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
int64_t g_nextJsValueId = 1;
// Block id -> owning pluginId (recorded by registerBlock, design §6).
std::unordered_map<int64_t, std::string> g_blockPluginId;

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

static void* boxKeyForRemember(jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isObject()) return rdma_boxObject();
    return boxJsiValue(rt, v);
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

// Forward-declared: defined in the map-host section below (used here for
// HostValue results).
static jsi::Value wrapTypedHandle(jsi::Runtime& rt, void* handle, const std::string& type);

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

class StateProxyHostCAbi : public jsi::HostObject {
public:
    explicit StateProxyHostCAbi(void* state, std::string typeDesc)
        : state_(state), desc_(TypeDesc::parseOrAny(typeDesc)) {}
    ~StateProxyHostCAbi() override {
        if (state_) rdma_disposeStableRef(state_);
    }

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
        if (n.rfind("get_value", 0) == 0) {
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "get_value"), 0,
                [state = state_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
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
                [state = state_, desc = desc_](
                        jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (count < 1) return jsi::Value::undefined();
                    // Descriptor-typed and strict (docs/design/type_descriptors.md):
                    // a foreign-typed value raises instead of being coerced, and `null`
                    // is written as a value only on nullable states. The legacy path
                    // keeps the old skip-on-unboxable behavior for pre-descriptor jars.
                    void* boxed = boxStateValueCAbi(r, args[0], desc);
                    if (g_inComposition) {
                        // Rendezvous write during composition: the write runs inside the
                        // UI thread's active composition snapshot, so it is visible to
                        // the same pass' reads (docs/bridges.md §state) — the Android
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
                    // and the Recomposer schedules a recomposition.
                    dispatch_async(dispatch_get_main_queue(), ^{
                        rdma_stateSetValue(state, boxed);
                        if (boxed) rdma_disposeStableRef(boxed);
                    });
                    return jsi::Value::undefined();
                });
        }
        return jsi::Value::undefined();
    }

    void* state() const { return state_; }

private:
    void* state_;
    TypeDesc desc_;
};

jsi::Object makeStateProxyCAbi(jsi::Runtime& rt, void* stateHandle, const std::string& typeDesc) {
    // The channel descriptor normally rides along the value transport
    // (`RdmaResult::stateRef(handle, desc)`). A handle without one falls back to
    // the descriptor the state handle itself carries (`RdmaTypedState`), and a
    // remembered raw `MutableState` (whose descriptor never existed) proxies with
    // an empty descriptor — reads work, writes fail loudly instead of guessing
    // (docs/design/type_descriptors.md: the descriptor is fixed per channel).
    std::string desc = typeDesc.empty() ? rdmaStateTypeDescStr(stateHandle) : typeDesc;
    auto host = std::make_shared<StateProxyHostCAbi>(stateHandle, desc);
    return jsi::Object::createFromHostObject(rt, host);
}

void* stateProxyHandle(jsi::Runtime& rt, const jsi::Value& v) {
    if (!v.isObject()) return nullptr;
    auto obj = v.asObject(rt);
    if (!obj.isHostObject(rt)) return nullptr;
    auto host = std::dynamic_pointer_cast<StateProxyHostCAbi>(obj.getHostObject(rt));
    return host ? host->state() : nullptr;
}

class ScopeUpdateScopeProxyHostCAbi : public jsi::HostObject {
public:
    explicit ScopeUpdateScopeProxyHostCAbi(void* scope) : scope_(scope) {}
    ~ScopeUpdateScopeProxyHostCAbi() override {
        if (scope_) rdma_disposeStableRef(scope_);
    }

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
        if (n.rfind("updateScope", 0) == 0) {
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "updateScope"), 1,
                [scope = scope_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                        return jsi::Value::undefined();
                    }
                    auto fn = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
                    int64_t id = g_nextScopeBlockId++;
                    g_scopeBlocks[id] = fn;
                    rdmaCallUi([scope, id]() -> RdmaResult {
                        rdma_scopeUpdateScope(scope, id);
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        return jsi::Value::undefined();
    }

private:
    void* scope_;
};

jsi::Object makeScopeUpdateScopeProxyCAbi(jsi::Runtime& rt, void* scopeHandle) {
    auto host = std::make_shared<ScopeUpdateScopeProxyHostCAbi>(scopeHandle);
    return jsi::Object::createFromHostObject(rt, host);
}

// -------------------------------------------------------------- list host

// Element type is a recursive descriptor: primitives / @RDMA FQN, or a nested
// `list:<elem>` for a list-of-lists.
static bool isListElementType(const std::string& elementType) {
    return elementType.rfind("list:", 0) == 0;
}

static std::string nestedElementType(const std::string& elementType) {
    return elementType.substr(5);
}

// Forward-declared (defined in the map-host section below).
static jsi::Value wrapTypedHandle(jsi::Runtime& rt, void* handle, const std::string& type);

// For a state-backed handle (a kernel `SnapshotStateList`/`SnapshotStateMap`) an
// operation during composition first touches the collection's structural state on
// the UI thread so Compose's snapshot read observer records the dependency —
// without this, mutations through the proxy would not schedule a recomposition
// (docs/design/type_descriptors.md#state-semantics). Same contract as the JNI
// backend's `touchCompositionRead` (ListHandle.cpp).
static void touchCompositionReadCAbi(void* handle, bool map) {
    if (!g_inComposition) return;
    rdmaCallUi([handle, map]() -> RdmaResult {
        if (map) rdma_mapSize(handle);
        else rdma_listSize(handle);
        return RdmaResult::undefined();
    });
}

// State-backed collection ops obey the state-value channel's thread contract
// (`StateProxyHostCAbi`): during composition the op runs inside the UI thread's
// active composition snapshot via the rendezvous — the kernel snapshot record
// must belong to the snapshot the composing pass can read/write; outside
// composition writes are posted to the main queue so the Compose snapshot system
// records them and the Recomposer schedules, and reads run directly. A plain
// (non-state) collection runs directly. Returns the shim call's result; a
// fire-and-forget posted write comes back as `Empty`.
static RdmaResult dispatchStateOp(bool stateBacked, bool write, std::function<RdmaResult()> op) {
    if (!stateBacked) return op();
    if (g_inComposition) return rdmaCallUi(op);
    if (write) {
        dispatch_async(dispatch_get_main_queue(), ^{ RdmaResult res = op(); (void)res; });
        return RdmaResult::empty();
    }
    return op();
}

// Defined in the collection-surface section below (used by the list/map hosts'
// iterator objects and the state-collection factories).
static std::vector<std::string> collectionMangledNamesOf(jsi::Runtime& rt, bool map);
static void installIteratorAliasesCAbi(jsi::Runtime& rt, jsi::Object& it, const std::vector<std::string>& names);

ListCAbiHost::ListCAbiHost(void* listHandle, const std::string& elementType, bool stateBacked)
    : listHandle_(listHandle), elementType_(elementType), stateBacked_(stateBacked) {}

ListCAbiHost::~ListCAbiHost() {
    if (listHandle_) rdma_disposeStableRef(listHandle_);
}

jsi::Value ListCAbiHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (stateBacked_ && n == "size") touchCompositionReadCAbi(listHandle_, /*map=*/false);
    jsi::Value v = getDispatch(rt, name);
    if (stateBacked_ && v.isObject() && v.asObject(rt).isFunction(rt)) {
        // Register the composition read when the method is CALLED, not when the
        // property is fetched (the same contract as the JNI `wrapStateBackedReads`).
        auto inner = std::make_shared<jsi::Function>(v.asObject(rt).asFunction(rt));
        v = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, n.c_str()), 0,
            [inner, handle = listHandle_](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                touchCompositionReadCAbi(handle, /*map=*/false);
                return inner->callWithThis(r, thisVal.asObject(r), args, count);
            });
    }
    return v;
}

jsi::Value ListCAbiHost::getDispatch(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        RdmaResult res = dispatchStateOp(stateBacked_, /*write=*/false, [handle = listHandle_]() -> RdmaResult {
            int32_t v = rdma_listSize(handle);
            RdmaResult captured;
            if (captureCAbiError(captured)) return captured;
            return RdmaResult::number((double)v);
        });
        return rdmaResultToJsiCAbi(rt, res);
    }
    if (n.rfind("get_size_", 0) == 0) {
        // A compile-mangled `size` read (`get_size_..._k$()`) on a bare host object.
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_listSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                RdmaResult res = dispatchStateOp(sb, false, [handle, index = (int32_t)args[0].getNumber(), elemType]() -> RdmaResult {
                    void* elem = rdma_listGet(handle, index);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return elem ? RdmaResult::hostValue(elem, elemType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("set", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set"), 2,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2 || !args[0].isNumber()) return jsi::Value::undefined();
                void* value = jsiToHandle(r, args[1], elemType);
                // Kotlin `MutableList.set` returns the previous element (parity
                // with the JNI backend, audit #16). `wrapTypedHandle` consumes it.
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, index = (int32_t)args[0].getNumber(), value, elemType]() -> RdmaResult {
                    void* previous = rdma_listSet(handle, index, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return previous ? RdmaResult::hostValue(previous, elemType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "add" || n.rfind("add_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "add"), 2,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                void* value = jsiToHandle(r, (count >= 2 && args[0].isNumber() && !args[1].isUndefined()) ? args[1] : args[0], elemType);
                int32_t at = (count >= 2 && args[0].isNumber() && !args[1].isUndefined()) ? (int32_t)args[0].getNumber() : -1;
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, at, value]() -> RdmaResult {
                    if (at >= 0) rdma_listAddAt(handle, at, value);
                    else rdma_listAdd(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("removeAt", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeAt"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                // Kotlin `MutableList.removeAt` returns the removed element
                // (parity with the JNI backend, audit #16).
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, index = (int32_t)args[0].getNumber(), elemType]() -> RdmaResult {
                    void* removed = rdma_listRemoveAt(handle, index);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return removed ? RdmaResult::hostValue(removed, elemType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle]() -> RdmaResult {
                    rdma_listClear(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("sizeImpl", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_listSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("isEmpty", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    bool v = rdma_listIsEmpty(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(v);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "containsAll" || n.rfind("containsAll_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "containsAll"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                void* other = jsiToHandle(r, args[0], "list:" + elemType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, other]() -> RdmaResult {
                    bool has = rdma_listContainsAll(handle, other);
                    if (other) rdma_disposeStableRef(other);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(has);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "contains" || n.rfind("contains_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "contains"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* value = jsiToHandle(r, args[0], elemType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, value]() -> RdmaResult {
                    bool has = rdma_listContains(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(has);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("indexOf", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "indexOf"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(-1);
                void* value = jsiToHandle(r, args[0], elemType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, value]() -> RdmaResult {
                    int32_t index = rdma_listIndexOf(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)index);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("lastIndexOf", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "lastIndexOf"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(-1);
                void* value = jsiToHandle(r, args[0], elemType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, value]() -> RdmaResult {
                    int32_t index = rdma_listLastIndexOf(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)index);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "remove" || n.rfind("remove_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                void* value = jsiToHandle(r, args[0], elemType);
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, value]() -> RdmaResult {
                    bool removed = rdma_listRemoveValue(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(removed);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("removeRange", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeRange"), 2,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2) return jsi::Value::undefined();
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, from = (int32_t)args[0].getNumber(), to = (int32_t)args[1].getNumber()]() -> RdmaResult {
                    rdma_listRemoveRange(handle, from, to);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto last = std::make_shared<int>(-1);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        RdmaResult res = dispatchStateOp(sb, false, [handle, index]() -> RdmaResult {
                            bool more = *index < rdma_listSize(handle);
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::boolean(more);
                        });
                        return rdmaResultToJsiCAbi(rr, res);
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, elemType, index, last, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        RdmaResult res = dispatchStateOp(sb, false, [handle, elemType, index, last]() -> RdmaResult {
                            if (*index >= rdma_listSize(handle)) return RdmaResult::undefined();
                            int i = (*index)++;
                            *last = i;
                            void* elem = rdma_listGet(handle, i);
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return elem ? RdmaResult::hostValue(elem, elemType) : RdmaResult::null();
                        });
                        return rdmaResultToJsiCAbi(rr, res);
                    });
                it.setProperty(r, "next", std::move(nextFn));
                auto removeFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "remove"), 0,
                    [handle, index, last, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        if (*last < 0) return jsi::Value::undefined();
                        RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, index, last]() -> RdmaResult {
                            void* removed = rdma_listRemoveAt(handle, *last);
                            if (removed) rdma_disposeStableRef(removed);
                            *index = *last;
                            *last = -1;
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::undefined();
                        });
                        return rdmaResultToJsiCAbi(rr, res);
                    });
                it.setProperty(r, "remove", std::move(removeFn));
                installIteratorAliasesCAbi(r, it, collectionMangledNamesOf(r, /*map=*/false));
                return it;
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                int32_t size = (int32_t)dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_listSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                }).num;
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    RdmaResult elem = dispatchStateOp(sb, false, [handle, i, elemType]() -> RdmaResult {
                        void* v = rdma_listGet(handle, i);
                        RdmaResult captured;
                        if (captureCAbiError(captured)) return captured;
                        return v ? RdmaResult::hostValue(v, elemType) : RdmaResult::null();
                    });
                    arr.setValueAtIndex(r, i, rdmaResultToJsiCAbi(r, elem));
                }
                return arr;
            });
    }
    if (n.rfind("toString", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toString"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    const char* s = rdma_listToString(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::string(s ? s : "");
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("equals", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "equals"), 1,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                auto other = args[0].asObject(r);
                void* otherHandle = nullptr;
                if (other.isHostObject(r)) {
                    auto host = std::dynamic_pointer_cast<ListCAbiHost>(other.getHostObject(r));
                    if (host) otherHandle = host->listHandle();
                } else {
                    auto p = other.getProperty(r, "__rdma_proxy");
                    if (p.isObject()) {
                        auto h = std::dynamic_pointer_cast<ListCAbiHost>(p.asObject(r).getHostObject(r));
                        if (h) otherHandle = h->listHandle();
                    }
                }
                if (!otherHandle) return jsi::Value(false);
                RdmaResult res = dispatchStateOp(sb, false, [handle, otherHandle]() -> RdmaResult {
                    bool eq = rdma_listEquals(handle, otherHandle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(eq);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("hashCode", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "hashCode"), 0,
            [handle = listHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t h = rdma_listHashCode(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)h);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("subList", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "subList"), 2,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2 || !args[0].isNumber() || !args[1].isNumber()) return jsi::Value::undefined();
                RdmaResult res = dispatchStateOp(sb, false, [handle, from = (int32_t)args[0].getNumber(), to = (int32_t)args[1].getNumber(), elemType]() -> RdmaResult {
                    void* sub = rdma_listSubList(handle, from, to);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return sub ? RdmaResult::hostValue(sub, "list:" + elemType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("addAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "addAll"), 2,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                const jsi::Value& otherArg = args[count - 1];
                if (!otherArg.isObject()) return jsi::Value(false);
                void* other = jsiToHandle(r, jsi::Value(r, otherArg.asObject(r)), "list:" + elemType);
                int32_t at = (count >= 2 && args[0].isNumber()) ? (int32_t)args[0].getNumber() : -1;
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, at, other]() -> RdmaResult {
                    bool ok = (at >= 0)
                        ? rdma_listAddAllAt(handle, at, other)
                        : rdma_listAddAll(handle, other);
                    if (other) rdma_disposeStableRef(other);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(ok);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("removeAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeAll"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                void* other = jsiToHandle(r, jsi::Value(r, args[0].asObject(r)), "list:" + elemType);
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, other]() -> RdmaResult {
                    bool removed = rdma_listRemoveAll(handle, other);
                    if (other) rdma_disposeStableRef(other);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(removed);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("retainAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "retainAll"), 1,
            [handle = listHandle_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                void* other = jsiToHandle(r, jsi::Value(r, args[0].asObject(r)), "list:" + elemType);
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, other]() -> RdmaResult {
                    bool kept = rdma_listRetainAll(handle, other);
                    if (other) rdma_disposeStableRef(other);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(kept);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    return jsi::Value::undefined();
}

// Mangled collection method spellings (`add_1x7w2j_k$`, ...) discovered by the
// guest bridge (`RDMA.listMethodNames` / `RDMA.mapMethodNames`, compiled with the
// plugin). Cached per runtime once discovered. TODO: the static caches assume a
// single JS runtime — revisit with a second runtime (docs/design/collections.md).
static std::vector<std::string> collectionMangledNamesOf(jsi::Runtime& rt, bool map) {
    static std::vector<std::string> listCached;
    static std::vector<std::string> mapCached;
    static bool listComputed = false;
    static bool mapComputed = false;
    std::vector<std::string>& cached = map ? mapCached : listCached;
    bool& computed = map ? mapComputed : listComputed;
    if (computed) return cached;
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return cached;
    auto fn = rdma.asObject(rt).getProperty(rt, map ? "mapMethodNames" : "listMethodNames");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return cached;
    jsi::Value res = fn.asObject(rt).asFunction(rt).call(rt);
    if (!res.isObject() || !res.asObject(rt).isArray(rt)) return cached;
    jsi::Array arr = res.asObject(rt).getArray(rt);
    size_t n = arr.size(rt);
    std::vector<std::string> out;
    for (size_t i = 0; i < n; i++) {
        jsi::Value v = arr.getValueAtIndex(rt, i);
        if (v.isString()) out.push_back(v.getString(rt).utf8(rt));
    }
    if (!out.empty()) {
        cached = std::move(out);
        computed = true;
    }
    return cached;
}

// Installs the compile-mangled method aliases (`add_1x7w2j_k$` -> `add`) onto a
// collection proxy object. The name set comes from the object's own Kotlin/JS
// prototype (a morphed plugin collection) or from the guest bridge name table (a
// kernel-created proxy). A missing plain target is a loud install-time error — no
// quiet correctness hole. ONE table for both discovery paths and the property
// setters they mirror (`installListMangledForwards` / `installMapMangledForwards`
// in the JNI backend) so the two backends cannot drift.
static void installMangledAliasesCAbi(jsi::Runtime& rt, jsi::Object& obj, const std::vector<std::string>& names, bool map) {
    static const std::pair<const char*, const char*> kListTable[] = {
        {"get_size_", "sizeImpl"},
        {"get_", "get"},
        {"set_", "set"},
        {"add_", "add"},
        {"removeAt_", "removeAt"},
        {"removeRange_", "removeRange"},
        {"removeAll_", "removeAll"},
        {"retainAll_", "retainAll"},
        {"addAll_", "addAll"},
        {"clear_", "clear"},
        {"toArray", "toArray"},
        {"remove_", "remove"},
        {"containsAll_", "containsAll"},
        {"contains_", "contains"},
        {"indexOf_", "indexOf"},
        {"lastIndexOf_", "lastIndexOf"},
        {"isEmpty_", "isEmpty"},
        {"iterator", "iterator"},
        {"subList_", "subList"},
    };
    static const std::pair<const char*, const char*> kMapTable[] = {
        {"get_size_", "sizeImpl"},
        {"get_keys_", "keys"},
        {"get_values_", "values"},
        {"get_entries_", "entries"},
        {"get_", "get"},
        {"put_", "set"},
        {"remove_", "remove"},
        {"clear_", "clear"},
        {"containsKey_", "containsKey"},
        {"containsValue_", "containsValue"},
        {"isEmpty_", "isEmpty"},
        {"putAll_", "putAll"},
    };
    const std::pair<const char*, const char*>* table = map ? kMapTable : kListTable;
    const size_t tableSize = map ? sizeof(kMapTable) / sizeof(kMapTable[0]) : sizeof(kListTable) / sizeof(kListTable[0]);
    for (const std::string& name : names) {
        for (size_t i = 0; i < tableSize; i++) {
            if (name.rfind(table[i].first, 0) == 0) {
                jsi::Value target = obj.getProperty(rt, table[i].second);
                if (!target.isObject() || !target.asObject(rt).isFunction(rt)) {
                    throw jsi::JSError(rt, std::string("collection proxy: no implementation for '") + table[i].second + "'");
                }
                obj.setProperty(rt, name.c_str(), std::move(target));
                break;
            }
        }
    }
}

// Morphs a plugin-created Kotlin/JS `ArrayList` into a proxy list backed by the
// C ABI handle. Stores the `ListCAbiHost` on the object and installs both
// wrappers for the plain and mangled storage methods.
void morphListCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& elementType, bool stateBacked) {
    auto proxy = std::make_shared<ListCAbiHost>(handle, elementType, stateBacked);
    obj.setProperty(rt, "__rdma_proxy", jsi::Object::createFromHostObject(rt, proxy));

    auto installPlain = [&rt, &obj](const char* name, size_t arity) {
        obj.setProperty(rt, name, jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, name), arity,
            [name](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                auto proxyObj = thisVal.asObject(r).getProperty(r, "__rdma_proxy");
                if (!proxyObj.isObject()) return jsi::Value::undefined();
                auto target = proxyObj.asObject(r).getProperty(r, name);
                if (!target.isObject() || !target.asObject(r).isFunction(r)) return jsi::Value::undefined();
                return target.asObject(r).asFunction(r).callWithThis(r, proxyObj.asObject(r), args, count);
            }));
    };
    installPlain("get", 1);
    installPlain("set", 2);
    installPlain("add", 2);
    installPlain("removeAt", 1);
    installPlain("remove", 1);
    installPlain("removeRange", 2);
    installPlain("clear", 0);
    installPlain("sizeImpl", 0);
    installPlain("isEmpty", 0);
    installPlain("contains", 1);
    installPlain("indexOf", 1);
    installPlain("lastIndexOf", 1);
    installPlain("iterator", 0);
    installPlain("toArray", 0);
    installPlain("toString", 0);
    installPlain("equals", 1);
    installPlain("hashCode", 0);
    installPlain("subList", 2);
    installPlain("addAll", 2);
    installPlain("removeAll", 1);
    installPlain("retainAll", 1);
    installPlain("containsAll", 1);

    // The morphed object's own Kotlin/JS prototype names are the name set to alias.
    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);
    auto protoNames = proto.getPropertyNames(rt);
    size_t n = protoNames.size(rt);
    std::vector<std::string> names;
    names.reserve(n);
    for (size_t i = 0; i < n; i++) {
        names.push_back(protoNames.getValueAtIndex(rt, i).getString(rt).utf8(rt));
    }
    installMangledAliasesCAbi(rt, obj, names, /*map=*/false);

    // Define a `size` getter on the instance that forwards to the native size,
    // shadowing the Kotlin/JS ArrayList's own `size` (which reads the stale
    // backing array after morph). Mirrors the JNI populateListHandle.
    rt.global().setProperty(rt, "__rdma_list", jsi::Value(rt, obj));
    rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(
        "Object.defineProperty(globalThis.__rdma_list, 'size', {"
        "  get: function() { return this.sizeImpl(); },"
        "  configurable: true"
        "})"
    ), "<listGetter>");
    rt.global().setProperty(rt, "__rdma_list", jsi::Value::undefined());
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

// Wraps a Kotlin value handle into a JS value using its type descriptor. Nested
// lists/maps are wrapped as their host objects; everything else (primitives,
// String, @RDMA) goes through wrapAnyCAbi.
static jsi::Value wrapTypedHandle(jsi::Runtime& rt, void* handle, const std::string& type) {
    if (type == "kotlin.Unit") {
        if (handle) rdma_disposeStableRef(handle);
        return jsi::Value::undefined();
    }
    if (!handle) return jsi::Value::null();
    if (isListElementType(type)) {
        auto host = std::make_shared<ListCAbiHost>(handle, type.substr(5));
        return jsi::Object::createFromHostObject(rt, host);
    }
    if (isMapElementType(type)) {
        auto kv = parseMapBody(type.substr(4));
        auto host = std::make_shared<MapCAbiHost>(handle, kv.first, kv.second);
        return jsi::Object::createFromHostObject(rt, host);
    }
    return wrapAnyCAbi(rt, nullptr, handle);
}

void rdmaRegisterSuspendResultType(int64_t completionId, const std::string& typeDesc) {
    auto it = g_suspendContinuations.find(completionId);
    if (it != g_suspendContinuations.end()) {
        it->second.typeDesc = typeDesc;
    }
}

MapCAbiHost::MapCAbiHost(void* mapHandle, const std::string& keyType, const std::string& valueType, bool stateBacked)
    : mapHandle_(mapHandle), keyType_(keyType), valueType_(valueType), stateBacked_(stateBacked) {}

MapCAbiHost::~MapCAbiHost() {
    if (mapHandle_) rdma_disposeStableRef(mapHandle_);
}

jsi::Value MapCAbiHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (stateBacked_ && (n == "size" || n.rfind("get_size_", 0) == 0)) touchCompositionReadCAbi(mapHandle_, /*map=*/true);
    jsi::Value v = getDispatch(rt, name);
    if (stateBacked_ && v.isObject() && v.asObject(rt).isFunction(rt)) {
        // Register the composition read when the method is CALLED, not when the
        // property is fetched (the same contract as the JNI `wrapStateBackedMapReads`).
        auto inner = std::make_shared<jsi::Function>(v.asObject(rt).asFunction(rt));
        v = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, n.c_str()), 0,
            [inner, handle = mapHandle_](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                touchCompositionReadCAbi(handle, /*map=*/true);
                return inner->callWithThis(r, thisVal.asObject(r), args, count);
            });
    }
    return v;
}

jsi::Value MapCAbiHost::getDispatch(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        RdmaResult res = dispatchStateOp(stateBacked_, /*write=*/false, [handle = mapHandle_]() -> RdmaResult {
            int32_t v = rdma_mapSize(handle);
            RdmaResult captured;
            if (captureCAbiError(captured)) return captured;
            return RdmaResult::number((double)v);
        });
        return rdmaResultToJsiCAbi(rt, res);
    }
    // Compile-mangled spellings on a bare host object (`get_size_...`, `get_keys_...`).
    if (n.rfind("get_size_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = mapHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_mapSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("get_keys_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "keys"), 0,
            [handle = mapHandle_, keyType = keyType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 0, keyType, keyType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("get_values_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "values"), 0,
            [handle = mapHandle_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 1, valueType, valueType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("get_entries_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "entries"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                auto host = std::make_shared<MapEntrySetHost>(handle, keyType, valueType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                void* key = jsiToHandle(r, args[0], keyType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, key, valueType]() -> RdmaResult {
                    void* value = rdma_mapGet(handle, key);
                    if (key) rdma_disposeStableRef(key);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return value ? RdmaResult::hostValue(value, valueType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "set" || n.rfind("set_", 0) == 0 || n == "put" || n.rfind("put_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set"), 2,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2) return jsi::Value::undefined();
                void* key = jsiToHandle(r, args[0], keyType);
                void* value = jsiToHandle(r, args[1], valueType);
                // Kotlin `MutableMap.put`/`set` returns the previous value (`V?`)
                // (parity with the JNI backend, audit #16).
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, key, value, valueType]() -> RdmaResult {
                    void* previous = rdma_mapPut(handle, key, value);
                    if (key) rdma_disposeStableRef(key);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return previous ? RdmaResult::hostValue(previous, valueType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "remove" || n.rfind("remove_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::null();
                void* key = jsiToHandle(r, args[0], keyType);
                // Kotlin `MutableMap.remove(key)` returns the removed value (`V?`)
                // (parity with the JNI backend, audit #16).
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, key, valueType]() -> RdmaResult {
                    void* removed = rdma_mapRemoveEntry(handle, key);
                    if (key) rdma_disposeStableRef(key);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return removed ? RdmaResult::hostValue(removed, valueType) : RdmaResult::null();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = mapHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle]() -> RdmaResult {
                    rdma_mapClear(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "containsValue" || n.rfind("containsValue_", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "containsValue"), 1,
            [handle = mapHandle_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* value = jsiToHandle(r, args[0], valueType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, value]() -> RdmaResult {
                    bool has = rdma_mapContainsValue(handle, value);
                    if (value) rdma_disposeStableRef(value);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(has);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n == "containsKey" || n.rfind("containsKey_", 0) == 0 || n == "has") {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "containsKey"), 1,
            [handle = mapHandle_, keyType = keyType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* key = jsiToHandle(r, args[0], keyType);
                RdmaResult res = dispatchStateOp(sb, false, [handle, key]() -> RdmaResult {
                    bool has = rdma_mapContainsKey(handle, key);
                    if (key) rdma_disposeStableRef(key);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(has);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("isEmpty", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
            [handle = mapHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    bool empty = rdma_mapIsEmpty(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(empty);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("sizeImpl", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = mapHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_mapSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("keys", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "keys"), 0,
            [handle = mapHandle_, keyType = keyType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 0, keyType, keyType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("values", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "values"), 0,
            [handle = mapHandle_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 1, valueType, valueType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("entries", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "entries"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapEntrySetHost>(handle, keyType, valueType, sb);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("forEach", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "forEach"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) return jsi::Value::undefined();
                auto cb = args[0].asObject(r).asFunction(r);
                RdmaResult sizeRes = dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_mapSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                });
                if (sizeRes.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, sizeRes);
                int32_t size = (int32_t)sizeRes.num;
                for (int32_t i = 0; i < size; i++) {
                    void* k = nullptr;
                    void* v = nullptr;
                    RdmaResult res = dispatchStateOp(sb, false, [handle, i, &k, &v]() -> RdmaResult {
                        k = rdma_mapKeyAt(handle, i);
                        v = rdma_mapValueAt(handle, i);
                        RdmaResult captured;
                        if (captureCAbiError(captured)) return captured;
                        return RdmaResult::undefined();
                    });
                    if (res.kind == RdmaResultKind::Error) {
                        if (k) rdma_disposeStableRef(k);
                        if (v) rdma_disposeStableRef(v);
                        return rdmaResultToJsiCAbi(r, res);
                    }
                    jsi::Value keyV = wrapTypedHandle(r, k, keyType);
                    jsi::Value valV = wrapTypedHandle(r, v, valueType);
                    std::vector<jsi::Value> argv;
                    argv.reserve(2);
                    argv.push_back(std::move(valV));
                    argv.push_back(std::move(keyV));
                    cb.callWithThis(r, thisVal.asObject(r), static_cast<const jsi::Value*>(argv.data()), argv.size());
                }
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("putAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "putAll"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
                auto other = args[0].asObject(r);
                auto entries = mapToEntries(r, other);
                size_t n = entries.size(r);
                std::vector<std::pair<void*, void*>> pairs;
                pairs.reserve(n);
                for (size_t i = 0; i < n; i++) {
                    auto pair = entries.getValueAtIndex(r, i);
                    if (!pair.isObject() || !pair.asObject(r).isArray(r)) continue;
                    auto arr = pair.asObject(r).asArray(r);
                    if (arr.size(r) < 2) continue;
                    void* k = jsiToHandle(r, arr.getValueAtIndex(r, 0), keyType);
                    void* v = jsiToHandle(r, arr.getValueAtIndex(r, 1), valueType);
                    pairs.emplace_back(k, v);
                }
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, pairs]() -> RdmaResult {
                    for (const auto& kv : pairs) {
                        rdma_mapPut(handle, kv.first, kv.second);
                        if (kv.first) rdma_disposeStableRef(kv.first);
                        if (kv.second) rdma_disposeStableRef(kv.second);
                    }
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    return jsi::Value::undefined();
}

// A live view over a native Map (`keys`/`values`). `mode` 0 = keys, 1 = values.
MapViewHost::MapViewHost(void* mapHandle, int mode, const std::string& elementType, const std::string& /*unused*/,
                         bool stateBacked)
    : mapHandle_(mapHandle), mode_(mode), elementType_(elementType), stateBacked_(stateBacked) {}

MapViewHost::~MapViewHost() {}

jsi::Value MapViewHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        RdmaResult res = dispatchStateOp(stateBacked_, /*write=*/false, [handle = mapHandle_]() -> RdmaResult {
            int32_t v = rdma_mapSize(handle);
            RdmaResult captured;
            if (captureCAbiError(captured)) return captured;
            return RdmaResult::number((double)v);
        });
        return rdmaResultToJsiCAbi(rt, res);
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, mode = mode_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                void* elem = nullptr;
                RdmaResult res = dispatchStateOp(sb, false, [handle, mode, index = (int32_t)args[0].getNumber(), &elem]() -> RdmaResult {
                    elem = (mode == 0) ? rdma_mapKeyAt(handle, index) : rdma_mapValueAt(handle, index);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, res);
                return wrapTypedHandle(r, elem, elemType);
            });
    }
    if (n.rfind("remove", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = mapHandle_, mode = mode_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* elem = jsiToHandle(r, args[0], elemType);
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle, mode, elem]() -> RdmaResult {
                    bool removed = (mode == 0) ? rdma_mapRemove(handle, elem) : rdma_mapRemoveValue(handle, elem);
                    if (elem) rdma_disposeStableRef(elem);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::boolean(removed);
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = mapHandle_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                RdmaResult res = dispatchStateOp(sb, /*write=*/true, [handle]() -> RdmaResult {
                    rdma_mapClear(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                return rdmaResultToJsiCAbi(r, res);
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = mapHandle_, mode = mode_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                int32_t size = (int32_t)dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_mapSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                }).num;
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    void* elem = nullptr;
                    RdmaResult res = dispatchStateOp(sb, false, [handle, mode, i, &elem]() -> RdmaResult {
                        elem = (mode == 0) ? rdma_mapKeyAt(handle, i) : rdma_mapValueAt(handle, i);
                        RdmaResult captured;
                        if (captureCAbiError(captured)) return captured;
                        return RdmaResult::undefined();
                    });
                    if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, res);
                    arr.setValueAtIndex(r, i, wrapTypedHandle(r, elem, elemType));
                }
                return arr;
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = mapHandle_, mode = mode_, elemType = elementType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        RdmaResult res = dispatchStateOp(sb, false, [handle, index]() -> RdmaResult {
                            bool more = *index < rdma_mapSize(handle);
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::boolean(more);
                        });
                        return rdmaResultToJsiCAbi(rr, res);
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, mode, elemType, index, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        void* elem = nullptr;
                        bool fetched = false;
                        RdmaResult res = dispatchStateOp(sb, false, [handle, mode, index, &elem, &fetched]() -> RdmaResult {
                            if (*index >= rdma_mapSize(handle)) return RdmaResult::undefined();
                            int32_t i = (*index)++;
                            elem = (mode == 0) ? rdma_mapKeyAt(handle, i) : rdma_mapValueAt(handle, i);
                            fetched = true;
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::undefined();
                        });
                        if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(rr, res);
                        if (!fetched) return jsi::Value::undefined(); // exhausted
                        return wrapTypedHandle(rr, elem, elemType);
                    });
                it.setProperty(r, "next", std::move(nextFn));
                installIteratorAliasesCAbi(r, it, collectionMangledNamesOf(r, /*map=*/true));
                return it;
            });
    }
    return jsi::Value::undefined();
}

MapEntrySetHost::MapEntrySetHost(void* mapHandle, const std::string& keyType, const std::string& valueType,
                                 bool stateBacked)
    : mapHandle_(mapHandle), keyType_(keyType), valueType_(valueType), stateBacked_(stateBacked) {}

MapEntrySetHost::~MapEntrySetHost() {}

jsi::Value MapEntrySetHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        RdmaResult res = dispatchStateOp(stateBacked_, /*write=*/false, [handle = mapHandle_]() -> RdmaResult {
            int32_t v = rdma_mapSize(handle);
            RdmaResult captured;
            if (captureCAbiError(captured)) return captured;
            return RdmaResult::number((double)v);
        });
        return rdmaResultToJsiCAbi(rt, res);
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                void* k = nullptr;
                void* v = nullptr;
                bool fetched = false;
                RdmaResult res = dispatchStateOp(sb, false, [handle, index = (int32_t)args[0].getNumber(), &k, &v, &fetched]() -> RdmaResult {
                    if (index < 0 || index >= rdma_mapSize(handle)) return RdmaResult::undefined();
                    k = rdma_mapKeyAt(handle, index);
                    v = rdma_mapValueAt(handle, index);
                    fetched = true;
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::undefined();
                });
                if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, res);
                if (!fetched) return jsi::Value::undefined(); // out of range
                auto entry = jsi::Object(r);
                entry.setProperty(r, "key", wrapTypedHandle(r, k, keyType));
                entry.setProperty(r, "value", wrapTypedHandle(r, v, valueType));
                return entry;
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                int32_t size = (int32_t)dispatchStateOp(sb, false, [handle]() -> RdmaResult {
                    int32_t v = rdma_mapSize(handle);
                    RdmaResult captured;
                    if (captureCAbiError(captured)) return captured;
                    return RdmaResult::number((double)v);
                }).num;
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    void* k = nullptr;
                    void* v = nullptr;
                    RdmaResult res = dispatchStateOp(sb, false, [handle, i, &k, &v]() -> RdmaResult {
                        k = rdma_mapKeyAt(handle, i);
                        v = rdma_mapValueAt(handle, i);
                        RdmaResult captured;
                        if (captureCAbiError(captured)) return captured;
                        return RdmaResult::undefined();
                    });
                    if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, res);
                    auto entry = jsi::Object(r);
                    entry.setProperty(r, "key", wrapTypedHandle(r, k, keyType));
                    entry.setProperty(r, "value", wrapTypedHandle(r, v, valueType));
                    arr.setValueAtIndex(r, i, std::move(entry));
                }
                return arr;
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_, sb = stateBacked_](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        RdmaResult res = dispatchStateOp(sb, false, [handle, index]() -> RdmaResult {
                            bool more = *index < rdma_mapSize(handle);
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::boolean(more);
                        });
                        return rdmaResultToJsiCAbi(rr, res);
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, keyType, valueType, index, sb](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        void* k = nullptr;
                        void* v = nullptr;
                        bool fetched = false;
                        RdmaResult res = dispatchStateOp(sb, false, [handle, index, &k, &v, &fetched]() -> RdmaResult {
                            if (*index >= rdma_mapSize(handle)) return RdmaResult::undefined();
                            int32_t i = (*index)++;
                            k = rdma_mapKeyAt(handle, i);
                            v = rdma_mapValueAt(handle, i);
                            fetched = true;
                            RdmaResult captured;
                            if (captureCAbiError(captured)) return captured;
                            return RdmaResult::undefined();
                        });
                        if (res.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(rr, res);
                        if (!fetched) return jsi::Value::undefined(); // exhausted
                        auto entry = jsi::Object(rr);
                        entry.setProperty(rr, "key", wrapTypedHandle(rr, k, keyType));
                        entry.setProperty(rr, "value", wrapTypedHandle(rr, v, valueType));
                        return entry;
                    });
                it.setProperty(r, "next", std::move(nextFn));
                return it;
            });
    }
    return jsi::Value::undefined();
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

// Morphs a plugin-created Kotlin/JS `Map` into a proxy map backed by the C ABI
// handle. Mirrors morphListCAbi: stores the MapCAbiHost on the object and installs
// plain and mangled method aliases.
void morphMapCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    auto proxy = std::make_shared<MapCAbiHost>(handle, keyType, valueType, stateBacked);
    obj.setProperty(rt, "__rdma_proxy", jsi::Object::createFromHostObject(rt, proxy));

    auto installPlain = [&rt, &obj](const char* name, size_t arity) {
        obj.setProperty(rt, name, jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, name), arity,
            [name](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                auto proxyObj = thisVal.asObject(r).getProperty(r, "__rdma_proxy");
                if (!proxyObj.isObject()) return jsi::Value::undefined();
                auto target = proxyObj.asObject(r).getProperty(r, name);
                if (!target.isObject() || !target.asObject(r).isFunction(r)) return jsi::Value::undefined();
                return target.asObject(r).asFunction(r).callWithThis(r, proxyObj.asObject(r), args, count);
            }));
    };
    installPlain("get", 1);
    installPlain("set", 2);
    installPlain("remove", 1);
    installPlain("clear", 0);
    installPlain("containsKey", 1);
    installPlain("sizeImpl", 0);
    installPlain("isEmpty", 0);
    installPlain("keys", 0);
    installPlain("values", 0);
    installPlain("entries", 0);
    installPlain("forEach", 1);
    installPlain("putAll", 1);
    installPlain("containsValue", 1);

    // The morphed object's own Kotlin/JS prototype names are the name set to alias.
    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);
    auto protoNames = proto.getPropertyNames(rt);
    size_t n = protoNames.size(rt);
    std::vector<std::string> names;
    names.reserve(n);
    for (size_t i = 0; i < n; i++) {
        names.push_back(protoNames.getValueAtIndex(rt, i).getString(rt).utf8(rt));
    }
    installMangledAliasesCAbi(rt, obj, names, /*map=*/true);
}

// Iterator objects speak the same compile-mangled Kotlin protocol
// (`hasNext_...`/`next_...`/`remove_...`) — install the aliases from the same
// discovered name set (parity with the JNI `installIteratorMangledForwards`).
static void installIteratorAliasesCAbi(jsi::Runtime& rt, jsi::Object& it, const std::vector<std::string>& names) {
    static const std::pair<const char*, const char*> kTable[] = {
        {"hasNext_", "hasNext"},
        {"next_", "next"},
        {"remove_", "remove"},
    };
    for (const std::string& name : names) {
        for (const auto& entry : kTable) {
            if (name.rfind(entry.first, 0) == 0) {
                jsi::Value target = it.getProperty(rt, entry.second);
                if (target.isObject() && target.asObject(rt).isFunction(rt)) {
                    it.setProperty(rt, name.c_str(), std::move(target));
                }
                break;
            }
        }
    }
}

// The plugin-facing object over a kernel-created state collection
// (`mutableStateListOf`/`mutableStateMapOf`, docs/design/type_descriptors.md):
// the same plain + mangled method surface a morphed plugin collection gets
// (`morphListCAbi`/`morphMapCAbi`), fed from the guest bridge name table
// (`RDMA.listMethodNames`) since a fresh object has no Kotlin/JS prototype of its
// own — parity with the JNI `populateListHandle` + `installListMangledForwards`.
static jsi::Object createStateListHandleCAbi(jsi::Runtime& rt, void* snapHandle, const std::string& elemDesc) {
    jsi::Object obj(rt);
    morphListCAbi(rt, obj, snapHandle, elemDesc, /*stateBacked=*/true);
    installMangledAliasesCAbi(rt, obj, collectionMangledNamesOf(rt, /*map=*/false), /*map=*/false);
    return obj;
}

static jsi::Object createStateMapHandleCAbi(jsi::Runtime& rt, void* snapHandle, const std::string& keyDesc, const std::string& valueDesc) {
    jsi::Object obj(rt);
    morphMapCAbi(rt, obj, snapHandle, keyDesc, valueDesc, /*stateBacked=*/true);
    installMangledAliasesCAbi(rt, obj, collectionMangledNamesOf(rt, /*map=*/true), /*map=*/true);
    return obj;
}

void* jsiToHandle(jsi::Runtime& rt, const jsi::Value& v, const std::string& elementType) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (isListElementType(elementType)) {
        // Nested list: reuse an already-morphed/proxied list, else materialize
        // recursively.
        if (!v.isObject()) return nullptr;
        auto obj = v.asObject(rt);
        if (obj.isHostObject(rt)) {
            auto host = std::dynamic_pointer_cast<ListCAbiHost>(obj.getHostObject(rt));
            if (host) return rdma_cloneHandle(host->listHandle());
        }
        {
            auto p = obj.getProperty(rt, "__rdma_proxy");
            if (p.isObject()) {
                auto h = std::dynamic_pointer_cast<ListCAbiHost>(p.asObject(rt).getHostObject(rt));
                if (h) return rdma_cloneHandle(h->listHandle());
            }
        }
        const std::string nested = nestedElementType(elementType);
        if (obj.isArray(rt)) {
            auto arr = obj.asArray(rt);
            size_t n = arr.size(rt);
            std::vector<void*> handles;
            handles.reserve(n);
            for (size_t i = 0; i < n; i++) {
                handles.push_back(jsiToHandle(rt, arr.getValueAtIndex(rt, i), nested));
            }
            return rdma_listCreate(handles.data(), (int32_t)handles.size());
        }
        // Kotlin/JS List (ArrayList): the same discovery order as the JNI backend's
        // `materializeArray` (ListHandle.cpp) — an indexable `length`/`size`
        // wrapper, the unmangled backing field `array_1`, else the first
        // array-valued own property.
        jsi::Array backing(rt, 0);
        size_t len = 0;
        int path = 0; // 0=none, 2=length/size, 3=backing array
        auto lp = obj.getProperty(rt, "length");
        if (!lp.isNumber()) lp = obj.getProperty(rt, "size");
        if (lp.isNumber()) {
            len = (size_t)lp.getNumber();
            path = 2;
        } else {
            auto array1 = obj.getProperty(rt, "array_1");
            if (array1.isObject() && array1.asObject(rt).isArray(rt)) {
                backing = array1.asObject(rt).asArray(rt);
                len = backing.size(rt);
                path = 3;
            } else {
                auto names = obj.getPropertyNames(rt);
                size_t n = names.size(rt);
                for (size_t i = 0; i < n; i++) {
                    auto name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
                    auto val = obj.getProperty(rt, name.c_str());
                    if (val.isObject() && val.asObject(rt).isArray(rt)) {
                        backing = val.asObject(rt).asArray(rt);
                        len = backing.size(rt);
                        path = 3;
                        break;
                    }
                }
            }
        }
        if (path == 0) return nullptr;
        std::vector<void*> handles;
        handles.reserve(len);
        for (size_t i = 0; i < len; i++) {
            jsi::Value elem = (path == 2)
                ? obj.getProperty(rt, std::to_string(i).c_str())
                : jsi::Value(rt, backing.getValueAtIndex(rt, i));
            handles.push_back(jsiToHandle(rt, elem, nested));
        }
        return rdma_listCreate(handles.data(), (int32_t)handles.size());
    }
    if (isMapElementType(elementType)) {
        if (!v.isObject()) return nullptr;
        auto obj = v.asObject(rt);
        if (obj.isHostObject(rt)) {
            auto host = std::dynamic_pointer_cast<MapCAbiHost>(obj.getHostObject(rt));
            if (host) return rdma_cloneHandle(host->mapHandle());
        }
        {
            auto p = obj.getProperty(rt, "__rdma_proxy");
            if (p.isObject()) {
                auto h = std::dynamic_pointer_cast<MapCAbiHost>(p.asObject(rt).getHostObject(rt));
                if (h) return rdma_cloneHandle(h->mapHandle());
            }
        }
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
        jsi::Object obj = v.asObject(rt);
        if (obj.hasNativeState(rt)) {
            auto ns = obj.getNativeState(rt);
            auto objState = std::dynamic_pointer_cast<RdmaObjectNativeStateCAbi>(ns);
            if (objState && objState->handle()) return rdma_cloneHandle(objState->handle());
        }
        if (obj.isHostObject(rt)) {
            auto host = std::dynamic_pointer_cast<ListCAbiHost>(obj.getHostObject(rt));
            if (host) return rdma_cloneHandle(host->listHandle());
            auto mapHost = std::dynamic_pointer_cast<MapCAbiHost>(obj.getHostObject(rt));
            if (mapHost) return rdma_cloneHandle(mapHost->mapHandle());
        }
    }
    // Unknown leaves keep the runtime-dispatch boxer (pre-descriptor behavior,
    // e.g. callback-id lists) — the same fallback as the JNI `jsiToJobject`.
    return boxJsiValue(rt, v);
}

// ------------------------------------------------------------- content/scope

void invokeRegisteredContent(jsi::Runtime& rt) {
    if (!g_content) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        g_content->call(rt, proxy, 0);
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

void invokeScopeBlock(jsi::Runtime& rt, int64_t blockId, int32_t changed) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        it->second->call(rt, proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

void invokeScopeBlock1(jsi::Runtime& rt, int64_t blockId, int32_t changed, int32_t p1) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        it->second->call(rt, jsi::Value((double)p1), proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        throw;
    }
    g_inComposition = prevInComposition;
}

// --------------------------------------------------------- callback/lambda

static void runCallbackOrLambda(jsi::Runtime& rt, int64_t blockId, void* argsHandle) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) {
        if (argsHandle) rdma_disposeStableRef(argsHandle);
        return;
    }
    std::vector<jsi::Value> jsArgs;
    if (argsHandle) {
        int32_t n = rdma_arraySize(argsHandle);
        jsArgs.reserve(n);
        for (int32_t i = 0; i < n; i++) {
            void* elem = rdma_arrayGet(argsHandle, i);
            jsArgs.push_back(wrapAnyCAbi(rt, nullptr, elem));
        }
        rdma_disposeStableRef(argsHandle);
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    try {
        it->second->call(rt, callArgs, jsArgs.size());
    } catch (const jsi::JSError& e) {
        auto pid = g_blockPluginId.find(blockId);
        rdmaUncaughtException(fromJSError(rt, e), pid != g_blockPluginId.end() ? pid->second : "");
    }
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

    auto registerFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerContent"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count > 0 && args[0].isObject() && args[0].asObject(r).isFunction(r)) {
                g_content = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
                g_contentVersion.fetch_add(1, std::memory_order_release);
                LOGI("Content registered");
            }
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "registerContent", std::move(registerFn));

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
            return createStateListHandleCAbi(r, snap, elemDesc);
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
            return createStateMapHandleCAbi(r, snap, keyDesc, valueDesc);
        });
    rdma.setProperty(rt, "mutableStateMapOf", std::move(mutableStateMapOfFn));

    auto registerBlockFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerBlock"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            auto fn = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
            int64_t id = g_nextScopeBlockId++;
            g_scopeBlocks[id] = fn;
            if (count > 1 && args[1].isString()) {
                g_blockPluginId[id] = args[1].getString(r).utf8(r);
            }
            return jsi::Value((double)id);
        });
    rdma.setProperty(rt, "registerBlock", std::move(registerBlockFn));

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
            int64_t id = g_nextScopeBlockId++;
            g_scopeBlocks[id] = fn;

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
                g_scopeBlocks.erase(id);
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

extern "C" {

void rdma_nativeInvokeContent() {
    using namespace facebook::rdma;
    RdmaExceptionInfo err = rdmaCallJs([] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeRegisteredContent(*rt);
    });
    if (!err.empty()) storeCaughtError(err);
}

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

void rdma_nativeInvokeCallback(int64_t blockId, void* argsHandle) {
    using namespace facebook::rdma;
    rdmaPostJs([blockId, argsHandle] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) runCallbackOrLambda(*rt, blockId, argsHandle);
        else if (argsHandle) rdma_disposeStableRef(argsHandle);
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
        auto it = g_scopeBlocks.find(blockId);
        if (it == g_scopeBlocks.end()) return;
        auto fn = it->second;
        g_scopeBlocks.erase(it);
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
        g_scopeBlocks.erase(blockId);
    });
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

void* rdma_vtableDispatch(int64_t vtablePtr, int32_t vtableId) {
    using namespace facebook::rdma;
    if (vtablePtr == 0) return nullptr;
    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    if (vtableId < 0 || (size_t)vtableId >= vt->entries.size()) return nullptr;
    auto& entry = vt->entries[vtableId];
    if (!entry) return nullptr;
    try {
        auto& irt = *(facebook::jsi::IRuntime*)vt->rt;
        facebook::jsi::Value result = entry->call(irt, nullptr, 0);
        if (result.isString()) {
            std::string s = result.getString(*vt->rt).utf8(*vt->rt);
            return rdma_boxString(s.c_str());
        }
    } catch (const std::exception& e) {
        LOGW("rdma_vtableDispatch error: %s", e.what());
    }
    return nullptr;
}

} // extern "C"
