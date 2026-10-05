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
#include "RdmaVtable.h"
#include "RdmaLog.h"

#include <jsi/jsi.h>
#include <string>
#include <vector>
#include <unordered_map>
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
std::shared_ptr<jsi::Object> g_empty;
static std::unordered_map<int64_t, std::shared_ptr<jsi::Function>> g_scopeBlocks;
static int64_t g_nextScopeBlockId = 1;
std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
int64_t g_nextJsValueId = 1;

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

void* boxJsiValue(jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isNumber()) return rdma_boxInt((int32_t)v.getNumber());
    if (v.isString()) return rdma_boxString(v.getString(rt).utf8(rt).c_str());
    if (v.isBool()) return rdma_boxBoolean(v.getBool());
    return nullptr;
}

static void* boxKeyForRemember(jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isObject()) return rdma_boxObject();
    return boxJsiValue(rt, v);
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
            return jsi::Value((double)v);
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
        case CAbiValueKind::State:
            return makeStateProxyCAbi(rt, handle);
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
            return RdmaResult::stateRef(handle);
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
            return RdmaResult::number((double)v);
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
        case CAbiValueKind::Null:
        default:
            if (handle) rdma_disposeStableRef(handle);
            return RdmaResult::null();
    }
}

jsi::Value stateValueToJsi(jsi::Runtime& rt, void* valueHandle) {
    return wrapAnyCAbi(rt, nullptr, valueHandle);
}

jsi::Value rdmaResultToJsiCAbi(jsi::Runtime& rt, RdmaResult& result) {
    switch (result.kind) {
        case RdmaResultKind::Undefined: return jsi::Value::undefined();
        case RdmaResultKind::Null: return jsi::Value::null();
        case RdmaResultKind::Bool: return jsi::Value(result.b);
        case RdmaResultKind::Number: return jsi::Value(result.num);
        case RdmaResultKind::String: return jsi::String::createFromUtf8(rt, result.str);
        case RdmaResultKind::Empty:
            return g_empty ? jsi::Value(rt, *g_empty) : jsi::Value::undefined();
        case RdmaResultKind::JsValueId: {
            auto it = g_jsValues.find(result.id);
            if (it != g_jsValues.end()) return jsi::Value(rt, *it->second);
            return jsi::Value::undefined();
        }
        case RdmaResultKind::StateRef: {
            void* ref = result.ref;
            result.ref = nullptr;
            if (!ref) return jsi::Value::null();
            return makeStateProxyCAbi(rt, ref);
        }
        case RdmaResultKind::ScopeRef: {
            void* ref = result.ref;
            result.ref = nullptr;
            if (!ref) return jsi::Value::null();
            return makeScopeUpdateScopeProxyCAbi(rt, ref);
        }
    }
    return jsi::Value::undefined();
}

// -------------------------------------------------------------------- proxies

class StateProxyHostCAbi : public jsi::HostObject {
public:
    explicit StateProxyHostCAbi(void* state) : state_(state) {}
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
                    return stateValueToJsi(r, v);
                });
        }
        if (n.rfind("set_value", 0) == 0) {
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "set_value"), 1,
                [state = state_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (count < 1) return jsi::Value::undefined();
                    void* boxed = boxJsiValue(r, args[0]);
                    if (boxed) {
                        // Route the state write to the UI (main) thread so the Compose
                        // snapshot system records the write and the Recomposer schedules
                        // a recomposition. Writing directly on the Hermes thread bypasses
                        // the Kotlin/Native snapshot observation and the change is lost.
                        dispatch_async(dispatch_get_main_queue(), ^{
                            rdma_stateSetValue(state, boxed);
                            rdma_disposeStableRef(boxed);
                        });
                    }
                    return jsi::Value::undefined();
                });
        }
        return jsi::Value::undefined();
    }

    void* state() const { return state_; }

private:
    void* state_;
};

jsi::Object makeStateProxyCAbi(jsi::Runtime& rt, void* stateHandle) {
    auto host = std::make_shared<StateProxyHostCAbi>(stateHandle);
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

ListCAbiHost::ListCAbiHost(void* listHandle, const std::string& elementType)
    : listHandle_(listHandle), elementType_(elementType) {}

ListCAbiHost::~ListCAbiHost() {
    if (listHandle_) rdma_disposeStableRef(listHandle_);
}

jsi::Value ListCAbiHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        return jsi::Value((double)rdma_listSize(listHandle_));
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                void* elem = rdma_listGet(handle, (int32_t)args[0].getNumber());
                return wrapAnyCAbi(r, nullptr, elem);
            });
    }
    if (n.rfind("sizeImpl", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value((double)rdma_listSize(handle));
            });
    }
    return jsi::Value::undefined();
}

void* jsiToHandle(jsi::Runtime& rt, const jsi::Value& v, const std::string& elementType) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (v.isNumber()) {
        if (elementType == "kotlin.Long") return rdma_boxLong((int64_t)v.getNumber());
        if (elementType == "kotlin.Double" || elementType == "kotlin.Float") return rdma_boxDouble(v.getNumber());
        return rdma_boxInt((int32_t)v.getNumber());
    }
    if (v.isBool()) return rdma_boxBoolean(v.getBool());
    if (v.isString()) return rdma_boxString(v.getString(rt).utf8(rt).c_str());
    if (v.isObject()) {
        auto obj = v.asObject(rt);
        if (obj.hasNativeState(rt)) {
            auto ns = obj.getNativeState(rt);
            auto objState = std::dynamic_pointer_cast<RdmaObjectNativeStateCAbi>(ns);
            if (objState) return objState->handle();
        }
        if (obj.isHostObject(rt)) {
            auto host = std::dynamic_pointer_cast<ListCAbiHost>(obj.getHostObject(rt));
            if (host) return host->listHandle();
        }
    }
    return nullptr;
}

// ------------------------------------------------------------- content/scope

void invokeRegisteredContent(jsi::Runtime& rt) {
    if (!g_content) return;
    bool prevInComposition = g_inComposition;
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt);
    try {
        g_content->call(rt, proxy, 0);
    } catch (const jsi::JSError& e) {
        LOGW("JSError in content: %s\n%s", e.what(), e.getStack().c_str());
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
    } catch (const jsi::JSError& e) {
        LOGW("JSError in scope block: %s\n%s", e.what(), e.getStack().c_str());
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
    it->second->call(rt, callArgs, jsArgs.size());
}

// ------------------------------------------------------------ init (JSI only)

void installRdmaComposeBridge(jsi::Runtime& rt, HostContext ctx) {
    jsi::Object rdma(rt);

    auto registerFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerContent"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count > 0 && args[0].isObject() && args[0].asObject(r).isFunction(r)) {
                g_content = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
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
        rt, jsi::PropNameID::forAscii(rt, "mutableStateOf"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            void* boxed = boxJsiValue(r, args[0]);
            RdmaResult result = rdmaCallUi([boxed]() -> RdmaResult {
                void* state = rdma_mutableStateOf(boxed);
                if (boxed) rdma_disposeStableRef(boxed);
                if (!state) return RdmaResult::undefined();
                return RdmaResult::stateRef(state);
            });
            return rdmaResultToJsiCAbi(r, result);
        });
    rdma.setProperty(rt, "mutableStateOf", std::move(mutableStateOfFn));

    auto registerBlockFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerBlock"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            auto fn = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
            int64_t id = g_nextScopeBlockId++;
            g_scopeBlocks[id] = fn;
            return jsi::Value((double)id);
        });
    rdma.setProperty(rt, "registerBlock", std::move(registerBlockFn));

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

} // namespace rdma
} // namespace facebook

// ------------------------------------------------------- C ABI entry points

extern "C" {

void rdma_nativeInvokeContent() {
    using namespace facebook::rdma;
    rdmaCallJs([] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeRegisteredContent(*rt);
    });
}

void rdma_nativeInvokeScopeBlock(int64_t blockId, int32_t changed) {
    using namespace facebook::rdma;
    rdmaCallJs([blockId, changed] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) invokeScopeBlock(*rt, blockId, changed);
    });
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
    rdmaCallJs([blockId, &resultId] {
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
    return resultId;
}

void rdma_nativeInvokeDispose(int64_t resultId) {
    using namespace facebook::rdma;
    rdmaCallJs([resultId] {
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
}

void rdma_nativeInvokeFreeBlock(int64_t blockId) {
    using namespace facebook::rdma;
    rdmaCallJs([blockId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        g_scopeBlocks.erase(blockId);
    });
}

void rdma_start(void* ctx) {
    facebook::rdma::rdmaStart(ctx);
}

int32_t rdma_is_ready(void) {
    return facebook::rdma::rdmaIsReady() ? 1 : 0;
}

void rdma_eval_bytes(const void* code, int32_t len) {
    if (!code || len <= 0) return;
    facebook::rdma::rdmaEvalAsset(std::string((const char*)code, (size_t)len));
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
