#include "RdmaComposerProxy.h"
#include "RdmaComposeCAbi.h"

#include <memory>
#include <string>

namespace facebook {
namespace rdma {

class ComposerProxyHost : public jsi::HostObject, public std::enable_shared_from_this<ComposerProxyHost> {
public:
    ComposerProxyHost() = default;
    // The Composer never crosses the boundary: the Kotlin shim reads it from its
    // own global, so this proxy is stateless.

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
        if (n.rfind("startRestartGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startRestartGroup"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            int32_t p0 = count > 0 && args[0].isNumber() ? (int32_t)args[0].getNumber() : 0;

                    rdmaCallUi([p0]() -> RdmaResult {
                        rdma_composer_startRestartGroup(p0);

                        return RdmaResult::undefined();
                    });
                    return jsi::Object::createFromHostObject(r, self);
                });
        }
        if (n.rfind("endRestartGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endRestartGroup"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    RdmaResult result = rdmaCallUi([]() -> RdmaResult {
                        void* scope = rdma_composer_endRestartGroup();

                        if (!scope) return RdmaResult::null();
                        return RdmaResult::scopeRef(scope);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
        }
        if (n.rfind("startReplaceGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startReplaceGroup"), 1,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            int32_t p0 = count > 0 && args[0].isNumber() ? (int32_t)args[0].getNumber() : 0;

                    rdmaCallUi([p0]() -> RdmaResult {
                        rdma_composer_startReplaceGroup(p0);

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endReplaceGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endReplaceGroup"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    rdmaCallUi([]() -> RdmaResult {
                        rdma_composer_endReplaceGroup();

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("startMovableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startMovableGroup"), 2,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            int32_t p0 = count > 0 && args[0].isNumber() ? (int32_t)args[0].getNumber() : 0;
            void* p1 = count > 1 ? boxJsiValue(r, args[1]) : nullptr;

                    rdmaCallUi([p0, p1]() -> RdmaResult {
                        rdma_composer_startMovableGroup(p0, p1);
                if (p1) rdma_disposeStableRef(p1);

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endMovableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endMovableGroup"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    rdmaCallUi([]() -> RdmaResult {
                        rdma_composer_endMovableGroup();

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("startReusableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startReusableGroup"), 2,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            int32_t p0 = count > 0 && args[0].isNumber() ? (int32_t)args[0].getNumber() : 0;
            void* p1 = count > 1 ? boxJsiValue(r, args[1]) : nullptr;

                    rdmaCallUi([p0, p1]() -> RdmaResult {
                        rdma_composer_startReusableGroup(p0, p1);
                if (p1) rdma_disposeStableRef(p1);

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endReusableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endReusableGroup"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    rdmaCallUi([]() -> RdmaResult {
                        rdma_composer_endReusableGroup();

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("skipCurrentGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "skipCurrentGroup"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    rdmaCallUi([]() -> RdmaResult {
                        rdma_composer_skipCurrentGroup();

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("skipToGroupEnd", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "skipToGroupEnd"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {

                    rdmaCallUi([]() -> RdmaResult {
                        rdma_composer_skipToGroupEnd();

                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("rememberedValue", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "rememberedValue"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    RdmaResult result = rdmaCallUi([]() -> RdmaResult {
                        void* v = rdma_composer_rememberedValue();
                        return valueHandleToResult(v);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
        }
        if (n.rfind("updateRememberedValue", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "updateRememberedValue"), 1,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (count < 1) return jsi::Value::undefined();
                    void* stored = nullptr;
                    bool disposeStored = false;
                    void* stateHandle = stateProxyHandle(r, args[0]);
                    if (stateHandle) {
                        stored = stateHandle;
                    } else if (args[0].isObject()) {
                        auto obj = std::make_shared<jsi::Object>(args[0].asObject(r));
                        int64_t id = g_nextJsValueId++;
                        g_jsValues[id] = obj;
                        stored = rdma_boxJsValueHolder(id);
                        disposeStored = true;
                    } else {
                        stored = boxJsiValue(r, args[0]);
                        disposeStored = true;
                    }
                    rdmaCallUi([stored, disposeStored]() -> RdmaResult {
                        rdma_composer_updateRememberedValue(stored);
                        if (disposeStored && stored) rdma_disposeStableRef(stored);
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("changed", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "changed"), 1,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    void* key = nullptr;
                    if (count > 0) {
                        if (args[0].isObject()) key = rdma_boxObject();
                        else key = boxJsiValue(r, args[0]);
                    }
                    RdmaResult result = rdmaCallUi([key]() -> RdmaResult {
                        bool res = rdma_composer_changed(key);
                        if (key) rdma_disposeStableRef(key);
                        return RdmaResult::boolean(res);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
        }
        if (n.rfind("shouldExecute", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "shouldExecute"), 2,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            bool p0 = count > 0 && args[0].isBool() ? args[0].getBool() : false;
            int32_t p1 = count > 1 && args[1].isNumber() ? (int32_t)args[1].getNumber() : 0;

                    RdmaResult result = rdmaCallUi([p0, p1]() -> RdmaResult {
                        bool res = rdma_composer_shouldExecute(p0, p1);

                        return RdmaResult::boolean(res);
                    });
                    return rdmaResultToJsiCAbi(r, result);
                });
        }
        if (n.rfind("sourceInformation", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "sourceInformation"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("sourceInformationMarkerStart", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "sourceInformationMarkerStart"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("sourceInformationMarkerEnd", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "sourceInformationMarkerEnd"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("get_recomposeScope", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "get_recomposeScope"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::null();
                });
        }
        if (n.rfind("recordUsed", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "recordUsed"), 0,
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::undefined();
                });
        }

        return jsi::Value::undefined();
    }

private:
};

jsi::Object makeComposerProxy(jsi::Runtime& rt) {
    auto host = std::make_shared<ComposerProxyHost>();
    return jsi::Object::createFromHostObject(rt, host);
}

} // namespace rdma
} // namespace facebook
