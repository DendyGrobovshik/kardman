#include "RdmaCompose.h"
#include "RdmaComposerProxy.h"

#include <jsi/jsi.h>
#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <android/log.h>

#include "RdmaRuntime.h"

#define LOG_TAG "RdmaCompose"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

ComposeJniCache g_composeCache;

static std::shared_ptr<jsi::Function> g_content;
std::shared_ptr<jsi::Object> g_empty;
static std::unordered_map<int64_t, std::shared_ptr<jsi::Function>> g_scopeBlocks;
static int64_t g_nextScopeBlockId = 1;
std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
int64_t g_nextJsValueId = 1;
jobject g_currentComposer = nullptr; // global ref to the currently-composing Composer

static UserBridgeInstaller g_userBridge = nullptr;

extern "C" void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer) {
    g_userBridge = installer;
}

// ---------------------------------------------------------------- JNI cache

static jclass cacheClass(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    if (!local) {
        LOGW("FindClass failed: %s", name);
        return nullptr;
    }
    jclass global = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

static bool initComposeJniCache(JNIEnv* env) {
    if (g_composeCache.composerClass != nullptr) return true;

    g_composeCache.composerClass = cacheClass(env, "androidx/compose/runtime/Composer");
    g_composeCache.scopeUpdateScopeClass = cacheClass(env, "androidx/compose/runtime/ScopeUpdateScope");
    g_composeCache.mutableStateClass = cacheClass(env, "androidx/compose/runtime/MutableState");
    g_composeCache.snapshotStateKt = cacheClass(env, "androidx/compose/runtime/SnapshotStateKt");
    g_composeCache.composerCompanion = cacheClass(env, "androidx/compose/runtime/Composer$Companion");
    g_composeCache.scopeBlockClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/ComposerScopeBlock");

    if (!g_composeCache.composerClass || !g_composeCache.mutableStateClass ||
        !g_composeCache.snapshotStateKt || !g_composeCache.composerCompanion) {
        return false;
    }

    g_composeCache.stateGetValue = env->GetMethodID(g_composeCache.mutableStateClass, "getValue", "()Ljava/lang/Object;");
    g_composeCache.stateSetValue = env->GetMethodID(g_composeCache.mutableStateClass, "setValue", "(Ljava/lang/Object;)V");

    g_composeCache.mutableStateOf = env->GetStaticMethodID(g_composeCache.snapshotStateKt, "mutableStateOf", "(Ljava/lang/Object;Landroidx/compose/runtime/SnapshotMutationPolicy;)Landroidx/compose/runtime/MutableState;");
    g_composeCache.structuralEqualityPolicy = env->GetStaticMethodID(g_composeCache.snapshotStateKt, "structuralEqualityPolicy", "()Landroidx/compose/runtime/SnapshotMutationPolicy;");

    g_composeCache.getEmpty = env->GetMethodID(g_composeCache.composerCompanion, "getEmpty", "()Ljava/lang/Object;");
    g_composeCache.composerCompanionField = env->GetStaticFieldID(g_composeCache.composerClass, "Companion", "Landroidx/compose/runtime/Composer$Companion;");

    g_composeCache.updateScope = env->GetMethodID(g_composeCache.scopeUpdateScopeClass, "updateScope", "(Lkotlin/jvm/functions/Function2;)V");
    g_composeCache.scopeBlockCtor = env->GetMethodID(g_composeCache.scopeBlockClass, "<init>", "(J)V");

    g_composeCache.objectClass = cacheClass(env, "java/lang/Object");
    g_composeCache.objectCtor = env->GetMethodID(g_composeCache.objectClass, "<init>", "()V");

    g_composeCache.jsValueHolderClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/JsValueHolder");
    g_composeCache.jsValueHolderCtor = env->GetMethodID(g_composeCache.jsValueHolderClass, "<init>", "(J)V");
    g_composeCache.jsValueHolderGetId = env->GetMethodID(g_composeCache.jsValueHolderClass, "getId", "()J");

    return g_composeCache.stateGetValue &&
           g_composeCache.stateSetValue && g_composeCache.mutableStateOf &&
           g_composeCache.structuralEqualityPolicy &&
           g_composeCache.getEmpty && g_composeCache.updateScope &&
           g_composeCache.scopeBlockCtor &&
           g_composeCache.objectCtor &&
           g_composeCache.jsValueHolderCtor && g_composeCache.jsValueHolderGetId;
}

JNIEnv* getEnv(JavaVM* jvm) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// ------------------------------------------------- value conversion (JVM <-> JSI)

jobject boxJsi(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isNumber()) {
        jclass integer = env->FindClass("java/lang/Integer");
        jmethodID valueOf = env->GetStaticMethodID(integer, "valueOf", "(I)Ljava/lang/Integer;");
        jobject r = env->CallStaticObjectMethod(integer, valueOf, (jint)v.getNumber());
        env->DeleteLocalRef(integer);
        return r;
    }
    if (v.isString()) {
        std::string s = v.getString(rt).utf8(rt);
        return env->NewStringUTF(s.c_str());
    }
    if (v.isBool()) {
        jclass boolean = env->FindClass("java/lang/Boolean");
        jmethodID valueOf = env->GetStaticMethodID(boolean, "valueOf", "(Z)Ljava/lang/Boolean;");
        jobject r = env->CallStaticObjectMethod(boolean, valueOf, (jboolean)v.getBool());
        env->DeleteLocalRef(boolean);
        return r;
    }
    return nullptr;
}

jsi::Value unboxJni(JNIEnv* env, jsi::Runtime& rt, jobject o) {
    if (o == nullptr) return jsi::Value::null();
    jclass cls = env->GetObjectClass(o);
    jclass integer = env->FindClass("java/lang/Integer");
    jclass boolean = env->FindClass("java/lang/Boolean");
    jclass string = env->FindClass("java/lang/String");
    jclass dbl = env->FindClass("java/lang/Double");
    jclass lng = env->FindClass("java/lang/Long");
    jclass flt = env->FindClass("java/lang/Float");

    jsi::Value result = jsi::Value::undefined();
    if (env->IsInstanceOf(o, integer)) {
        jmethodID intValue = env->GetMethodID(integer, "intValue", "()I");
        result = jsi::Value((double)env->CallIntMethod(o, intValue));
    } else if (env->IsInstanceOf(o, boolean)) {
        jmethodID boolValue = env->GetMethodID(boolean, "booleanValue", "()Z");
        result = jsi::Value(env->CallBooleanMethod(o, boolValue));
    } else if (env->IsInstanceOf(o, string)) {
        jmethodID getBytes = env->GetMethodID(string, "getBytes", "(Ljava/lang/String;)[B");
        jstring utf8 = env->NewStringUTF("UTF-8");
        jbyteArray bytes = (jbyteArray)env->CallObjectMethod(o, getBytes, utf8);
        env->DeleteLocalRef(utf8);
        if (bytes != nullptr) {
            jsize len = env->GetArrayLength(bytes);
            jbyte* elems = env->GetByteArrayElements(bytes, nullptr);
            std::string s((char*)elems, len);
            env->ReleaseByteArrayElements(bytes, elems, JNI_ABORT);
            result = jsi::String::createFromUtf8(rt, s);
        }
    } else if (env->IsInstanceOf(o, dbl)) {
        jmethodID doubleValue = env->GetMethodID(dbl, "doubleValue", "()D");
        result = jsi::Value(env->CallDoubleMethod(o, doubleValue));
    } else if (env->IsInstanceOf(o, lng)) {
        jmethodID longValue = env->GetMethodID(lng, "longValue", "()J");
        result = jsi::Value((double)env->CallLongMethod(o, longValue));
    } else if (env->IsInstanceOf(o, flt)) {
        jmethodID floatValue = env->GetMethodID(flt, "floatValue", "()F");
        result = jsi::Value((double)env->CallFloatMethod(o, floatValue));
    }

    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(integer);
    env->DeleteLocalRef(boolean);
    env->DeleteLocalRef(string);
    env->DeleteLocalRef(dbl);
    env->DeleteLocalRef(lng);
    env->DeleteLocalRef(flt);
    return result;
}

// ----------------------------------------------------------------- proxies

class StateProxyHost : public jsi::HostObject {
public:
    StateProxyHost(jobject state) : state_(state) {}
    ~StateProxyHost() override {
        if (state_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(state_);
        }
    }

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
        JNIEnv* env = getEnv(g_composeCache.jvm);
        if (!env) return jsi::Value::undefined();
        if (n.rfind("get_value", 0) == 0) {
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "get_value"), 0,
                [state = state_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                    jsi::Value out = unboxJni(e, r, v);
                    e->DeleteLocalRef(v);
                    return out;
                });
        }
        if (n.rfind("set_value", 0) == 0) {
            auto fn = jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "set_value"), 1,
                [state = state_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    if (count < 1) return jsi::Value::undefined();
                    jobject boxed = boxJsi(e, r, args[0]);
                    if (boxed) {
                        e->CallVoidMethod(state, g_composeCache.stateSetValue, boxed);
                        e->DeleteLocalRef(boxed);
                    }
                    return jsi::Value::undefined();
                });
            return fn;
        }
        return jsi::Value::undefined();
    }

    jobject state() const { return state_; }

private:
    jobject state_; // global ref
};

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject state) {
    auto host = std::make_shared<StateProxyHost>(state);
    return jsi::Object::createFromHostObject(rt, host);
}

jobject stateProxyJObject(jsi::Runtime& rt, const jsi::Value& v) {
    if (!v.isObject()) return nullptr;
    auto obj = v.asObject(rt);
    if (!obj.isHostObject(rt)) return nullptr;
    auto host = std::dynamic_pointer_cast<StateProxyHost>(obj.getHostObject(rt));
    return host ? host->state() : nullptr;
}

class ScopeUpdateScopeProxyHost : public jsi::HostObject {
public:
    ScopeUpdateScopeProxyHost(jobject scope) : scope_(scope) {}
    ~ScopeUpdateScopeProxyHost() override {
        if (scope_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(scope_);
        }
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
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject block = e->NewObject(g_composeCache.scopeBlockClass, g_composeCache.scopeBlockCtor, (jlong)id);
                    if (block) {
                        e->CallVoidMethod(scope, g_composeCache.updateScope, block);
                        e->DeleteLocalRef(block);
                    }
                    return jsi::Value::undefined();
                });
        }
        return jsi::Value::undefined();
    }

private:
    jobject scope_; // global ref
};

jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scope) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    jobject global = env->NewGlobalRef(scope);
    auto host = std::make_shared<ScopeUpdateScopeProxyHost>(global);
    return jsi::Object::createFromHostObject(rt, host);
}

static void setCurrentComposer(jobject composer) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (!env) return;
    if (g_currentComposer) {
        env->DeleteGlobalRef(g_currentComposer);
        g_currentComposer = nullptr;
    }
    if (composer) {
        g_currentComposer = env->NewGlobalRef(composer);
    }
}

void invokeRegisteredContent(jsi::Runtime& rt, jobject composer) {
    if (!g_content) {
        LOGW("No content registered");
        return;
    }
    setCurrentComposer(composer);
    jsi::Object proxy = makeComposerProxy(rt, composer);
    try {
        g_content->call(rt, proxy, 0);
    } catch (const jsi::JSError& e) {
        LOGW("JSError in content: %s\n%s", e.what(), e.getStack().c_str());
    }
}

void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composer, jint changed) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    setCurrentComposer(composer);
    jsi::Object proxy = makeComposerProxy(rt, composer);
    try {
        it->second->call(rt, proxy, changed);
    } catch (const jsi::JSError& e) {
        LOGW("JSError in scope block: %s\n%s", e.what(), e.getStack().c_str());
    }
}

void installRdmaComposeBridge(jsi::Runtime& rt, JavaVM* jvm) {
    g_composeCache.jvm = jvm;
    JNIEnv* env = getEnv(jvm);
    if (!env || !initComposeJniCache(env)) {
        LOGW("Compose bridge init failed");
        return;
    }
    initComposerProxyCache(env);

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
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e || count < 1) return jsi::Value::undefined();
            jobject boxed = boxJsi(e, r, args[0]);
            if (!boxed) return jsi::Value::undefined();
            jobject policy = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.structuralEqualityPolicy);
            jobject state = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.mutableStateOf, boxed, policy);
            e->DeleteLocalRef(boxed);
            e->DeleteLocalRef(policy);
            if (!state) return jsi::Value::undefined();
            jobject global = e->NewGlobalRef(state);
            e->DeleteLocalRef(state);
            return makeStateProxy(r, global);
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

    if (g_userBridge) {
        g_userBridge(rt, jvm, rdma);
    }

    rt.global().setProperty(rt, "RDMA", std::move(rdma));
    LOGI("Compose bridge installed");
}

// -------------------------------------------------------------------- JNI

} // namespace rdma
} // namespace facebook

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContent(
    JNIEnv* env, jclass, jobject composer) {
    using namespace facebook::rdma;
    facebook::jsi::Runtime* rt = getRdmaRuntime();
    if (!rt) return;
    invokeRegisteredContent(*rt, composer);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed) {
    using namespace facebook::rdma;
    facebook::jsi::Runtime* rt = getRdmaRuntime();
    if (!rt) return;
    invokeScopeBlock(*rt, blockId, composer, changed);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeCallback(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args) {
    using namespace facebook::rdma;
    facebook::jsi::Runtime* rt = getRdmaRuntime();
    if (!rt) return;
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    std::vector<facebook::jsi::Value> jsArgs;
    if (args) {
        jsize n = env->GetArrayLength(args);
        jsArgs.reserve(n);
        for (jsize i = 0; i < n; i++) {
            jobject elem = env->GetObjectArrayElement(args, i);
            jsArgs.push_back(unboxJni(env, *rt, elem));
            if (elem) env->DeleteLocalRef(elem);
        }
    }
    const facebook::jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    it->second->call(*rt, callArgs, jsArgs.size());
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeLambda(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args) {
    using namespace facebook::rdma;
    facebook::jsi::Runtime* rt = getRdmaRuntime();
    if (!rt) return nullptr;
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return nullptr;
    std::vector<facebook::jsi::Value> jsArgs;
    if (args) {
        jsize n = env->GetArrayLength(args);
        jsArgs.reserve(n);
        for (jsize i = 0; i < n; i++) {
            jobject elem = env->GetObjectArrayElement(args, i);
            jsArgs.push_back(unboxJni(env, *rt, elem));
            if (elem) env->DeleteLocalRef(elem);
        }
    }
    const facebook::jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    facebook::jsi::Value result = it->second->call(*rt, callArgs, jsArgs.size());
    return boxJsi(env, *rt, result);
}
