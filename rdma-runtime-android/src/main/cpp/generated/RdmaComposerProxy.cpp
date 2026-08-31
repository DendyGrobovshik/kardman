#include "RdmaComposerProxy.h"
#include "RdmaCompose.h"

#include <memory>
#include <string>
#include <unordered_map>

namespace facebook {
namespace rdma {

ComposerProxyCache g_composerProxyCache;

void initComposerProxyCache(JNIEnv* env) {
    g_composerProxyCache.startRestartGroup = env->GetMethodID(g_composeCache.composerClass, "startRestartGroup", "(I)Landroidx/compose/runtime/Composer;");
    g_composerProxyCache.endRestartGroup = env->GetMethodID(g_composeCache.composerClass, "endRestartGroup", "()Landroidx/compose/runtime/ScopeUpdateScope;");
    g_composerProxyCache.startReplaceGroup = env->GetMethodID(g_composeCache.composerClass, "startReplaceGroup", "(I)V");
    g_composerProxyCache.endReplaceGroup = env->GetMethodID(g_composeCache.composerClass, "endReplaceGroup", "()V");
    g_composerProxyCache.startMovableGroup = env->GetMethodID(g_composeCache.composerClass, "startMovableGroup", "(ILjava/lang/Object;)V");
    g_composerProxyCache.endMovableGroup = env->GetMethodID(g_composeCache.composerClass, "endMovableGroup", "()V");
    g_composerProxyCache.startReusableGroup = env->GetMethodID(g_composeCache.composerClass, "startReusableGroup", "(ILjava/lang/Object;)V");
    g_composerProxyCache.endReusableGroup = env->GetMethodID(g_composeCache.composerClass, "endReusableGroup", "()V");
    g_composerProxyCache.skipCurrentGroup = env->GetMethodID(g_composeCache.composerClass, "skipCurrentGroup", "()V");
    g_composerProxyCache.skipToGroupEnd = env->GetMethodID(g_composeCache.composerClass, "skipToGroupEnd", "()V");
    g_composerProxyCache.rememberedValue = env->GetMethodID(g_composeCache.composerClass, "rememberedValue", "()Ljava/lang/Object;");
    g_composerProxyCache.updateRememberedValue = env->GetMethodID(g_composeCache.composerClass, "updateRememberedValue", "(Ljava/lang/Object;)V");
    g_composerProxyCache.changed = env->GetMethodID(g_composeCache.composerClass, "changed", "(Ljava/lang/Object;)Z");
    g_composerProxyCache.shouldExecute = env->GetMethodID(g_composeCache.composerClass, "shouldExecute", "(ZI)Z");
}

class ComposerProxyHost : public jsi::HostObject, public std::enable_shared_from_this<ComposerProxyHost> {
public:
    explicit ComposerProxyHost(jobject composer) : composer_(composer) {}
    ~ComposerProxyHost() override {
        if (composer_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(composer_);
        }
    }

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
        if (n.rfind("startRestartGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startRestartGroup"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jint p0 = count > 0 && args[0].isNumber() ? (jint)args[0].getNumber() : 0;

                    jobject result = e->CallObjectMethod(self->composer_, g_composerProxyCache.startRestartGroup, p0);

                    if (result) e->DeleteLocalRef(result); // ComposerImpl returns `this`
                    return jsi::Object::createFromHostObject(r, self);
                });
        }
        if (n.rfind("endRestartGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endRestartGroup"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::null();

                    jobject scope = e->CallObjectMethod(self->composer_, g_composerProxyCache.endRestartGroup);

                    if (!scope) return jsi::Value::null();
                    jsi::Object proxy = makeScopeUpdateScopeProxy(r, scope);
                    e->DeleteLocalRef(scope);
                    return proxy;
                });
        }
        if (n.rfind("startReplaceGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startReplaceGroup"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jint p0 = count > 0 && args[0].isNumber() ? (jint)args[0].getNumber() : 0;

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.startReplaceGroup, p0);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endReplaceGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endReplaceGroup"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.endReplaceGroup);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("startMovableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startMovableGroup"), 2,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jint p0 = count > 0 && args[0].isNumber() ? (jint)args[0].getNumber() : 0;
                    jobject p1 = count > 1 ? boxJsi(e, r, args[1]) : nullptr;

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.startMovableGroup, p0, p1);
                    if (p1) e->DeleteLocalRef(p1);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endMovableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endMovableGroup"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.endMovableGroup);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("startReusableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "startReusableGroup"), 2,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jint p0 = count > 0 && args[0].isNumber() ? (jint)args[0].getNumber() : 0;
                    jobject p1 = count > 1 ? boxJsi(e, r, args[1]) : nullptr;

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.startReusableGroup, p0, p1);
                    if (p1) e->DeleteLocalRef(p1);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("endReusableGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "endReusableGroup"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.endReusableGroup);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("skipCurrentGroup", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "skipCurrentGroup"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.skipCurrentGroup);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("skipToGroupEnd", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "skipToGroupEnd"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();

                    e->CallVoidMethod(self->composer_, g_composerProxyCache.skipToGroupEnd);

                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("rememberedValue", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "rememberedValue"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject v = e->CallObjectMethod(self->composer_, g_composerProxyCache.rememberedValue);

                    jobject companion = e->GetStaticObjectField(g_composeCache.composerClass, g_composeCache.composerCompanionField);
                    jobject empty = e->CallObjectMethod(companion, g_composeCache.getEmpty);
                    e->DeleteLocalRef(companion);
                    bool isEmpty = e->IsSameObject(v, empty);
                    e->DeleteLocalRef(empty);
                    if (isEmpty) {
                        e->DeleteLocalRef(v);
                        return g_empty ? jsi::Value(r, *g_empty) : jsi::Value::undefined();
                    }
                    if (e->IsInstanceOf(v, g_composeCache.mutableStateClass)) {
                        jobject global = e->NewGlobalRef(v);
                        e->DeleteLocalRef(v);
                        return makeStateProxy(r, global);
                    }
                    if (e->IsInstanceOf(v, g_composeCache.jsValueHolderClass)) {
                        jlong id = e->CallLongMethod(v, g_composeCache.jsValueHolderGetId);
                        e->DeleteLocalRef(v);
                        auto it = g_jsValues.find(id);
                        if (it != g_jsValues.end()) {
                            return jsi::Value(r, *it->second);
                        }
                        return jsi::Value::undefined();
                    }
                    jsi::Value out = unboxJni(e, r, v);
                    e->DeleteLocalRef(v);
                    return out;
                });
        }
        if (n.rfind("updateRememberedValue", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "updateRememberedValue"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    if (count < 1) return jsi::Value::undefined();
                    jobject stored = nullptr;
                    bool deleteStored = false;
                    jobject stateObj = stateProxyJObject(r, args[0]);
                    if (stateObj) {
                        stored = stateObj;
                    } else if (args[0].isObject()) {
                        auto obj = std::make_shared<jsi::Object>(args[0].asObject(r));
                        int64_t id = g_nextJsValueId++;
                        g_jsValues[id] = obj;
                        stored = e->NewObject(g_composeCache.jsValueHolderClass, g_composeCache.jsValueHolderCtor, (jlong)id);
                        deleteStored = true;
                    } else {
                        stored = boxJsi(e, r, args[0]);
                        deleteStored = true;
                    }
                    if (stored) {
                        e->CallVoidMethod(self->composer_, g_composerProxyCache.updateRememberedValue, stored);
                        if (deleteStored) e->DeleteLocalRef(stored);
                    }
                    return jsi::Value::undefined();
                });
        }
        if (n.rfind("changed", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "changed"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value(true);
                    // Box primitive keys so `equals` compares correctly; use an identity-unique
                    // holder for object keys (which we cannot faithfully marshal yet).
                    jobject arg = nullptr;
                    if (count > 0) {
                        if (args[0].isObject()) {
                            arg = e->NewObject(g_composeCache.objectClass, g_composeCache.objectCtor);
                        } else {
                            arg = boxJsi(e, r, args[0]);
                        }
                    }
                    jboolean res = e->CallBooleanMethod(self->composer_, g_composerProxyCache.changed, arg);
                    if (arg) e->DeleteLocalRef(arg);
                    return jsi::Value((bool)res);
                });
        }
        if (n.rfind("shouldExecute", 0) == 0) {

            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "shouldExecute"), 2,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value(false);
                    jboolean p0 = count > 0 && args[0].isBool() ? args[0].getBool() : false;
                    jint p1 = count > 1 && args[1].isNumber() ? (jint)args[1].getNumber() : 0;

                    auto res = e->CallBooleanMethod(self->composer_, g_composerProxyCache.shouldExecute, p0, p1);

                    return jsi::Value((bool)res);
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

    jobject composer() const { return composer_; }

private:
    jobject composer_; // global ref
};

jsi::Object makeComposerProxy(jsi::Runtime& rt, jobject composer) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    jobject global = env->NewGlobalRef(composer);
    auto host = std::make_shared<ComposerProxyHost>(global);
    return jsi::Object::createFromHostObject(rt, host);
}

} // namespace rdma
} // namespace facebook
