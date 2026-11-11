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
        case RdmaResultKind::Error: {
            throwJsException(rt, result.error);
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
                    checkKotlinError(r);
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
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                void* elem = rdma_listGet(handle, (int32_t)args[0].getNumber());
                if (!elem) return jsi::Value::null();
                return wrapTypedHandle(r, elem, elemType);
            });
    }
    if (n.rfind("set", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set"), 2,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2 || !args[0].isNumber()) return jsi::Value::undefined();
                void* value = jsiToHandle(r, args[1], elemType);
                rdma_listSet(handle, (int32_t)args[0].getNumber(), value);
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("add", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "add"), 2,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                if (count >= 2 && args[0].isNumber() && !args[1].isUndefined()) {
                    void* value = jsiToHandle(r, args[1], elemType);
                    rdma_listAddAt(handle, (int32_t)args[0].getNumber(), value);
                } else {
                    void* value = jsiToHandle(r, args[0], elemType);
                    rdma_listAdd(handle, value);
                }
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("removeAt", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeAt"), 1,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                rdma_listRemoveAt(handle, (int32_t)args[0].getNumber());
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                rdma_listClear(handle);
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("sizeImpl", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value((double)rdma_listSize(handle));
            });
    }
    if (n.rfind("isEmpty", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value(rdma_listIsEmpty(handle));
            });
    }
    if (n.rfind("contains", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "contains"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* value = jsiToHandle(r, args[0], elemType);
                return jsi::Value(rdma_listContains(handle, value));
            });
    }
    if (n.rfind("indexOf", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "indexOf"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(-1);
                void* value = jsiToHandle(r, args[0], elemType);
                return jsi::Value((double)rdma_listIndexOf(handle, value));
            });
    }
    if (n.rfind("lastIndexOf", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "lastIndexOf"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(-1);
                void* value = jsiToHandle(r, args[0], elemType);
                return jsi::Value((double)rdma_listLastIndexOf(handle, value));
            });
    }
    if (n.rfind("remove", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                void* value = jsiToHandle(r, args[0], elemType);
                return jsi::Value(rdma_listRemoveValue(handle, value));
            });
    }
    if (n.rfind("removeRange", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeRange"), 2,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2) return jsi::Value::undefined();
                rdma_listRemoveRange(handle, (int32_t)args[0].getNumber(), (int32_t)args[1].getNumber());
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto last = std::make_shared<int>(-1);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        return jsi::Value(*index < rdma_listSize(handle));
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, elemType, index, last](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        if (*index >= rdma_listSize(handle)) return jsi::Value::undefined();
                        int i = (*index)++;
                        *last = i;
                        return wrapTypedHandle(rr, rdma_listGet(handle, i), elemType);
                    });
                it.setProperty(r, "next", std::move(nextFn));
                auto removeFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "remove"), 0,
                    [handle, index, last](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        if (*last < 0) return jsi::Value::undefined();
                        rdma_listRemoveAt(handle, *last);
                        *index = *last;
                        *last = -1;
                        return jsi::Value::undefined();
                    });
                it.setProperty(r, "remove", std::move(removeFn));
                return it;
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                int32_t size = rdma_listSize(handle);
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    arr.setValueAtIndex(r, i, wrapTypedHandle(r, rdma_listGet(handle, i), elemType));
                }
                return arr;
            });
    }
    if (n.rfind("toString", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toString"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                const char* s = rdma_listToString(handle);
                return jsi::String::createFromUtf8(r, s ? s : "");
            });
    }
    if (n.rfind("equals", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "equals"), 1,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
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
                return jsi::Value(rdma_listEquals(handle, otherHandle));
            });
    }
    if (n.rfind("hashCode", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "hashCode"), 0,
            [handle = listHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value((double)rdma_listHashCode(handle));
            });
    }
    if (n.rfind("subList", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "subList"), 2,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2) return jsi::Value::undefined();
                void* sub = rdma_listSubList(handle, (int32_t)args[0].getNumber(), (int32_t)args[1].getNumber());
                if (!sub) return jsi::Value::null();
                auto host = std::make_shared<ListCAbiHost>(sub, elemType);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("addAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "addAll"), 2,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                const jsi::Value& otherArg = args[count - 1];
                if (!otherArg.isObject()) return jsi::Value(false);
                auto otherObj = otherArg.asObject(r);
                void* other = jsiToHandle(r, jsi::Value(r, otherObj), "list:" + elemType);
                if (count >= 2 && args[0].isNumber()) {
                    return jsi::Value(rdma_listAddAllAt(handle, (int32_t)args[0].getNumber(), other));
                }
                return jsi::Value(rdma_listAddAll(handle, other));
            });
    }
    if (n.rfind("removeAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "removeAll"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                auto otherObj = args[0].asObject(r);
                void* other = jsiToHandle(r, jsi::Value(r, otherObj), "list:" + elemType);
                return jsi::Value(rdma_listRemoveAll(handle, other));
            });
    }
    if (n.rfind("retainAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "retainAll"), 1,
            [handle = listHandle_, elemType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value(false);
                auto otherObj = args[0].asObject(r);
                void* other = jsiToHandle(r, jsi::Value(r, otherObj), "list:" + elemType);
                return jsi::Value(rdma_listRetainAll(handle, other));
            });
    }
    return jsi::Value::undefined();
}

// Morphs a plugin-created Kotlin/JS `ArrayList` into a proxy list backed by the
// C ABI handle. Stores the `ListCAbiHost` on the object and installs forwarding
// wrappers for the plain and mangled storage methods.
void morphListCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& elementType) {
    auto proxy = std::make_shared<ListCAbiHost>(handle, elementType);
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

    auto forwardMangled = [&rt, &obj](const std::string& mangled, const char* plain) {
        obj.setProperty(rt, mangled.c_str(), jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, mangled.c_str()), 2,
            [plain](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                auto target = thisVal.asObject(r).getProperty(r, plain);
                if (!target.isObject() || !target.asObject(r).isFunction(r)) return jsi::Value::undefined();
                return target.asObject(r).asFunction(r).callWithThis(r, thisVal.asObject(r), args, count);
            }));
    };

    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);
    auto names = proto.getPropertyNames(rt);
    size_t n = names.size(rt);
    for (size_t i = 0; i < n; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        if (name.rfind("get_size_", 0) == 0) {
            forwardMangled(name, "sizeImpl");
        } else if (name.rfind("get_", 0) == 0) {
            forwardMangled(name, "get");
        } else if (name.rfind("set_", 0) == 0) {
            forwardMangled(name, "set");
        } else if (name.rfind("add_", 0) == 0) {
            forwardMangled(name, "add");
        } else if (name.rfind("removeAt_", 0) == 0) {
            forwardMangled(name, "removeAt");
        } else if (name.rfind("removeRange_", 0) == 0) {
            forwardMangled(name, "removeRange");
        } else if (name.rfind("clear_", 0) == 0) {
            forwardMangled(name, "clear");
        } else if (name.rfind("toArray", 0) == 0) {
            forwardMangled(name, "toArray");
        }
    }

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

MapCAbiHost::MapCAbiHost(void* mapHandle, const std::string& keyType, const std::string& valueType)
    : mapHandle_(mapHandle), keyType_(keyType), valueType_(valueType) {}

MapCAbiHost::~MapCAbiHost() {
    if (mapHandle_) rdma_disposeStableRef(mapHandle_);
}

jsi::Value MapCAbiHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        return jsi::Value((double)rdma_mapSize(mapHandle_));
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value::undefined();
                void* key = jsiToHandle(r, args[0], keyType);
                void* value = rdma_mapGet(handle, key);
                return wrapTypedHandle(r, value, valueType);
            });
    }
    if (n.rfind("set", 0) == 0 || n.rfind("put", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set"), 2,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 2) return jsi::Value::undefined();
                void* key = jsiToHandle(r, args[0], keyType);
                void* value = jsiToHandle(r, args[1], valueType);
                rdma_mapPut(handle, key, value);
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("remove", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = mapHandle_, keyType = keyType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* key = jsiToHandle(r, args[0], keyType);
                return jsi::Value(rdma_mapRemove(handle, key));
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = mapHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                rdma_mapClear(handle);
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("containsKey", 0) == 0 || n.rfind("has", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "containsKey"), 1,
            [handle = mapHandle_, keyType = keyType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* key = jsiToHandle(r, args[0], keyType);
                return jsi::Value(rdma_mapContainsKey(handle, key));
            });
    }
    if (n.rfind("isEmpty", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
            [handle = mapHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value(rdma_mapIsEmpty(handle));
            });
    }
    if (n.rfind("sizeImpl", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
            [handle = mapHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                return jsi::Value((double)rdma_mapSize(handle));
            });
    }
    if (n.rfind("keys", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "keys"), 0,
            [handle = mapHandle_, keyType = keyType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 0, keyType, keyType);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("values", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "values"), 0,
            [handle = mapHandle_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapViewHost>(handle, 1, valueType, valueType);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("entries", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "entries"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto host = std::make_shared<MapEntrySetHost>(handle, keyType, valueType);
                return jsi::Object::createFromHostObject(r, host);
            });
    }
    if (n.rfind("forEach", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "forEach"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) return jsi::Value::undefined();
                auto cb = args[0].asObject(r).asFunction(r);
                int32_t size = rdma_mapSize(handle);
                for (int32_t i = 0; i < size; i++) {
                    void* k = rdma_mapKeyAt(handle, i);
                    void* v = rdma_mapValueAt(handle, i);
                jsi::Value keyV = wrapTypedHandle(r, k, keyType);
                jsi::Value valV = wrapTypedHandle(r, v, valueType);
                std::vector<jsi::Value> argv;
                argv.reserve(2);
                argv.push_back(std::move(valV));
                argv.push_back(std::move(keyV));
                const jsi::Value* argsPtr = argv.data();
                cb.callWithThis(r, thisVal.asObject(r), argsPtr, argv.size());
                }
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("putAll", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "putAll"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
                auto other = args[0].asObject(r);
                auto entries = mapToEntries(r, other);
                size_t n = entries.size(r);
                for (size_t i = 0; i < n; i++) {
                    auto pair = entries.getValueAtIndex(r, i);
                    if (!pair.isObject() || !pair.asObject(r).isArray(r)) continue;
                    auto arr = pair.asObject(r).asArray(r);
                    if (arr.size(r) < 2) continue;
                    void* k = jsiToHandle(r, arr.getValueAtIndex(r, 0), keyType);
                    void* v = jsiToHandle(r, arr.getValueAtIndex(r, 1), valueType);
                    rdma_mapPut(handle, k, v);
                }
                return jsi::Value::undefined();
            });
    }
    return jsi::Value::undefined();
}

// A live view over a native Map (`keys`/`values`). `mode` 0 = keys, 1 = values.
MapViewHost::MapViewHost(void* mapHandle, int mode, const std::string& elementType, const std::string& /*unused*/)
    : mapHandle_(mapHandle), mode_(mode), elementType_(elementType) {}

MapViewHost::~MapViewHost() {}

jsi::Value MapViewHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        return jsi::Value((double)rdma_mapSize(mapHandle_));
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, mode = mode_, elementType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                void* elem = (mode == 0) ? rdma_mapKeyAt(handle, (int32_t)args[0].getNumber())
                                         : rdma_mapValueAt(handle, (int32_t)args[0].getNumber());
                return wrapTypedHandle(r, elem, elementType);
            });
    }
    if (n.rfind("remove", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
            [handle = mapHandle_, mode = mode_, elementType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1) return jsi::Value(false);
                void* elem = jsiToHandle(r, args[0], elementType);
                if (mode == 0) return jsi::Value(rdma_mapRemove(handle, elem));
                return jsi::Value(rdma_mapRemoveValue(handle, elem));
            });
    }
    if (n.rfind("clear", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
            [handle = mapHandle_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                rdma_mapClear(handle);
                return jsi::Value::undefined();
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = mapHandle_, mode = mode_, elementType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                int32_t size = rdma_mapSize(handle);
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    void* elem = (mode == 0) ? rdma_mapKeyAt(handle, i) : rdma_mapValueAt(handle, i);
                    arr.setValueAtIndex(r, i, wrapTypedHandle(r, elem, elementType));
                }
                return arr;
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = mapHandle_, mode = mode_, elementType = elementType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        return jsi::Value(*index < rdma_mapSize(handle));
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, mode, elementType, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        if (*index >= rdma_mapSize(handle)) return jsi::Value::undefined();
                        int32_t i = (*index)++;
                        void* elem = (mode == 0) ? rdma_mapKeyAt(handle, i) : rdma_mapValueAt(handle, i);
                        return wrapTypedHandle(rr, elem, elementType);
                    });
                it.setProperty(r, "next", std::move(nextFn));
                return it;
            });
    }
    return jsi::Value::undefined();
}

MapEntrySetHost::MapEntrySetHost(void* mapHandle, const std::string& keyType, const std::string& valueType)
    : mapHandle_(mapHandle), keyType_(keyType), valueType_(valueType) {}

MapEntrySetHost::~MapEntrySetHost() {}

jsi::Value MapEntrySetHost::get(jsi::Runtime& rt, const jsi::PropNameID& name) {
    std::string n = name.utf8(rt);
    if (n == "size") {
        return jsi::Value((double)rdma_mapSize(mapHandle_));
    }
    if (n.rfind("get", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get"), 1,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
                int32_t i = (int32_t)args[0].getNumber();
                if (i < 0 || i >= rdma_mapSize(handle)) return jsi::Value::undefined();
                auto entry = jsi::Object(r);
                entry.setProperty(r, "key", wrapTypedHandle(r, rdma_mapKeyAt(handle, i), keyType));
                entry.setProperty(r, "value", wrapTypedHandle(r, rdma_mapValueAt(handle, i), valueType));
                return entry;
            });
    }
    if (n.rfind("toArray", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                int32_t size = rdma_mapSize(handle);
                auto arr = jsi::Array(r, size);
                for (int32_t i = 0; i < size; i++) {
                    auto entry = jsi::Object(r);
                    entry.setProperty(r, "key", wrapTypedHandle(r, rdma_mapKeyAt(handle, i), keyType));
                    entry.setProperty(r, "value", wrapTypedHandle(r, rdma_mapValueAt(handle, i), valueType));
                    arr.setValueAtIndex(r, i, std::move(entry));
                }
                return arr;
            });
    }
    if (n.rfind("iterator", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
            [handle = mapHandle_, keyType = keyType_, valueType = valueType_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                auto index = std::make_shared<int>(0);
                auto it = jsi::Object(r);
                auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                    [handle, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        return jsi::Value(*index < rdma_mapSize(handle));
                    });
                it.setProperty(r, "hasNext", std::move(hasNextFn));
                auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                    [handle, keyType, valueType, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                        if (*index >= rdma_mapSize(handle)) return jsi::Value::undefined();
                        int32_t i = (*index)++;
                        auto entry = jsi::Object(rr);
                        entry.setProperty(rr, "key", wrapTypedHandle(rr, rdma_mapKeyAt(handle, i), keyType));
                        entry.setProperty(rr, "value", wrapTypedHandle(rr, rdma_mapValueAt(handle, i), valueType));
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
// forwarding wrappers for the plain and mangled storage methods.
void morphMapCAbi(jsi::Runtime& rt, jsi::Object& obj, void* handle, const std::string& keyType, const std::string& valueType) {
    auto proxy = std::make_shared<MapCAbiHost>(handle, keyType, valueType);
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

    auto forwardMangled = [&rt, &obj](const std::string& mangled, const char* plain) {
        obj.setProperty(rt, mangled.c_str(), jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, mangled.c_str()), 2,
            [plain](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                auto target = thisVal.asObject(r).getProperty(r, plain);
                if (!target.isObject() || !target.asObject(r).isFunction(r)) return jsi::Value::undefined();
                return target.asObject(r).asFunction(r).callWithThis(r, thisVal.asObject(r), args, count);
            }));
    };

    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);
    auto names = proto.getPropertyNames(rt);
    size_t n = names.size(rt);
    for (size_t i = 0; i < n; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        if (name.rfind("get_size_", 0) == 0) {
            forwardMangled(name, "sizeImpl");
        } else if (name.rfind("get_keys_", 0) == 0) {
            forwardMangled(name, "keys");
        } else if (name.rfind("get_values_", 0) == 0) {
            forwardMangled(name, "values");
        } else if (name.rfind("get_entries_", 0) == 0) {
            forwardMangled(name, "entries");
        } else if (name.rfind("get_", 0) == 0) {
            forwardMangled(name, "get");
        } else if (name.rfind("put_", 0) == 0) {
            forwardMangled(name, "set");
        } else if (name.rfind("remove_", 0) == 0) {
            forwardMangled(name, "remove");
        } else if (name.rfind("clear_", 0) == 0) {
            forwardMangled(name, "clear");
        } else if (name.rfind("containsKey_", 0) == 0) {
            forwardMangled(name, "containsKey");
        } else if (name.rfind("isEmpty_", 0) == 0) {
            forwardMangled(name, "isEmpty");
        } else if (name.rfind("putAll_", 0) == 0) {
            forwardMangled(name, "putAll");
        }
    }
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
            if (host) return host->listHandle();
        }
        {
            auto p = obj.getProperty(rt, "__rdma_proxy");
            if (p.isObject()) {
                auto h = std::dynamic_pointer_cast<ListCAbiHost>(p.asObject(rt).getHostObject(rt));
                if (h) return h->listHandle();
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
        // Kotlin/JS List (ArrayList): wraps a backing JS array in an own field.
        auto names = obj.getPropertyNames(rt);
        size_t n = names.size(rt);
        for (size_t i = 0; i < n; i++) {
            auto name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
            auto val = obj.getProperty(rt, name.c_str());
            if (val.isObject() && val.asObject(rt).isArray(rt)) {
                auto backing = val.asObject(rt).asArray(rt);
                size_t m = backing.size(rt);
                std::vector<void*> handles;
                handles.reserve(m);
                for (size_t j = 0; j < m; j++) {
                    handles.push_back(jsiToHandle(rt, backing.getValueAtIndex(rt, j), nested));
                }
                return rdma_listCreate(handles.data(), (int32_t)handles.size());
            }
        }
        return nullptr;
    }
    if (isMapElementType(elementType)) {
        if (!v.isObject()) return nullptr;
        auto obj = v.asObject(rt);
        if (obj.isHostObject(rt)) {
            auto host = std::dynamic_pointer_cast<MapCAbiHost>(obj.getHostObject(rt));
            if (host) return host->mapHandle();
        }
        {
            auto p = obj.getProperty(rt, "__rdma_proxy");
            if (p.isObject()) {
                auto h = std::dynamic_pointer_cast<MapCAbiHost>(p.asObject(rt).getHostObject(rt));
                if (h) return h->mapHandle();
            }
        }
        auto kv = parseMapBody(elementType.substr(4));
        return buildMapHandleFromJs(rt, obj, kv.first, kv.second);
    }
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
            auto mapHost = std::dynamic_pointer_cast<MapCAbiHost>(obj.getHostObject(rt));
            if (mapHost) return mapHost->mapHandle();
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
