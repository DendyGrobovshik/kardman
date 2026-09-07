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
#include "RdmaCompose.h"
#include "RdmaComposerProxy.h"
#include "RdmaRuntime.h"

#include <jsi/jsi.h>
#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <cstdio>
#include <android/log.h>

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
jobject g_currentComposer = nullptr; // borrowed global ref; owned by the content/scope task

// True on the Hermes thread while content/scope blocks are executing (i.e. during
// composition). State reads performed in this window rendezvous to the UI thread so
// that Compose's snapshot read observer records the dependency.
static thread_local bool g_inComposition = false;

static UserBridgeInstaller g_userBridge = nullptr;
static UserBridgeJniInit g_userBridgeJniInit = nullptr;

extern "C" void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer) {
    g_userBridge = installer;
}

extern "C" void rdmaSetUserBridgeJniInit(UserBridgeJniInit init) {
    g_userBridgeJniInit = init;
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

    for (int i = 0; i <= 3; i++) {
        char name[128];
        snprintf(name, sizeof(name), "io/github/dendygrobovshik/kardman/runtime/RdmaFunction%d", i);
        g_composeCache.rdmaFunctionClass[i] = cacheClass(env, name);
        g_composeCache.rdmaFunctionCtor[i] =
            env->GetMethodID(g_composeCache.rdmaFunctionClass[i], "<init>", "(J)V");
    }

    return g_composeCache.stateGetValue &&
           g_composeCache.stateSetValue && g_composeCache.mutableStateOf &&
           g_composeCache.structuralEqualityPolicy &&
           g_composeCache.getEmpty && g_composeCache.updateScope &&
           g_composeCache.scopeBlockCtor &&
           g_composeCache.objectCtor &&
           g_composeCache.jsValueHolderCtor && g_composeCache.jsValueHolderGetId &&
           g_composeCache.rdmaFunctionCtor[0] && g_composeCache.rdmaFunctionCtor[1] &&
           g_composeCache.rdmaFunctionCtor[2] && g_composeCache.rdmaFunctionCtor[3];
}

jobject createRdmaFunction(JNIEnv* env, int arity, jlong id) {
    if (arity < 0 || arity > 3) return nullptr;
    jclass cls = g_composeCache.rdmaFunctionClass[arity];
    jmethodID ctor = g_composeCache.rdmaFunctionCtor[arity];
    if (!cls || !ctor) return nullptr;
    return env->NewObject(cls, ctor, id);
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

// Classifies a boxed JVM value into an RdmaResult descriptor (run on the UI thread).
// Handles Integer/Boolean/Double/Long/Float/String/null; anything else -> Null.
RdmaResult classifyBoxedValue(JNIEnv* env, jobject o) {
    if (o == nullptr) return RdmaResult::null();
    jclass integer = env->FindClass("java/lang/Integer");
    jclass boolean = env->FindClass("java/lang/Boolean");
    jclass string = env->FindClass("java/lang/String");
    jclass dbl = env->FindClass("java/lang/Double");
    jclass lng = env->FindClass("java/lang/Long");
    jclass flt = env->FindClass("java/lang/Float");

    RdmaResult r;
    if (env->IsInstanceOf(o, integer)) {
        jmethodID intValue = env->GetMethodID(integer, "intValue", "()I");
        r = RdmaResult::number((double)env->CallIntMethod(o, intValue));
    } else if (env->IsInstanceOf(o, boolean)) {
        jmethodID boolValue = env->GetMethodID(boolean, "booleanValue", "()Z");
        r = RdmaResult::boolean(env->CallBooleanMethod(o, boolValue));
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
            env->DeleteLocalRef(bytes);
            r = RdmaResult::string(std::move(s));
        } else {
            r = RdmaResult::string("");
        }
    } else if (env->IsInstanceOf(o, dbl)) {
        jmethodID doubleValue = env->GetMethodID(dbl, "doubleValue", "()D");
        r = RdmaResult::number(env->CallDoubleMethod(o, doubleValue));
    } else if (env->IsInstanceOf(o, lng)) {
        jmethodID longValue = env->GetMethodID(lng, "longValue", "()J");
        r = RdmaResult::number((double)env->CallLongMethod(o, longValue));
    } else if (env->IsInstanceOf(o, flt)) {
        jmethodID floatValue = env->GetMethodID(flt, "floatValue", "()F");
        r = RdmaResult::number((double)env->CallFloatMethod(o, floatValue));
    } else {
        r = RdmaResult::null();
    }

    env->DeleteLocalRef(integer);
    env->DeleteLocalRef(boolean);
    env->DeleteLocalRef(string);
    env->DeleteLocalRef(dbl);
    env->DeleteLocalRef(lng);
    env->DeleteLocalRef(flt);
    return r;
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
                    if (g_inComposition) {
                        // Rendezvous to the UI thread so Compose's snapshot read
                        // observer records the dependency on this state.
                        RdmaResult res = rdmaCallUi([state]() -> RdmaResult {
                            JNIEnv* e = getEnv(g_composeCache.jvm);
                            if (!e) return RdmaResult::undefined();
                            jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                            RdmaResult r2 = classifyBoxedValue(e, v);
                            if (v) e->DeleteLocalRef(v);
                            return r2;
                        });
                        return rdmaResultToJsi(r, res);
                    }
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                    jsi::Value out = unboxJni(e, r, v);
                    if (v) e->DeleteLocalRef(v);
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

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject stateGlobal) {
    auto host = std::make_shared<StateProxyHost>(stateGlobal);
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
                    // ScopeUpdateScope.updateScope is a Compose-runtime call and must run
                    // on the UI thread; only the JS block registration happens here.
                    rdmaCallUi([scope, id]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::undefined();
                        jobject block = e->NewObject(g_composeCache.scopeBlockClass, g_composeCache.scopeBlockCtor, (jlong)id);
                        if (block) {
                            e->CallVoidMethod(scope, g_composeCache.updateScope, block);
                            e->DeleteLocalRef(block);
                        }
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
        }
        return jsi::Value::undefined();
    }

private:
    jobject scope_; // global ref
};

jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scopeGlobal) {
    auto host = std::make_shared<ScopeUpdateScopeProxyHost>(scopeGlobal);
    return jsi::Object::createFromHostObject(rt, host);
}

static void setCurrentComposer(jobject composer) {
    g_currentComposer = composer; // borrowed global ref, owned by the content/scope task
}

// ------------------------------------------------------------------- result

jsi::Value rdmaResultToJsi(jsi::Runtime& rt, RdmaResult& result) {
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
            if (!result.ref) return jsi::Value::null();
            jobject ref = result.ref;
            result.ref = nullptr; // ownership transferred to the proxy
            return makeStateProxy(rt, ref);
        }
        case RdmaResultKind::ScopeRef: {
            if (!result.ref) return jsi::Value::null();
            jobject ref = result.ref;
            result.ref = nullptr; // ownership transferred to the proxy
            return makeScopeUpdateScopeProxy(rt, ref);
        }
    }
    return jsi::Value::undefined();
}

// ------------------------------------------------------------- content/scope

void invokeRegisteredContent(jsi::Runtime& rt, jobject composerGlobal) {
    if (!g_content) {
        LOGW("No content registered");
        return;
    }
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt, composerGlobal);
    try {
        g_content->call(rt, proxy, 0);
    } catch (const jsi::JSError& e) {
        LOGW("JSError in content: %s\n%s", e.what(), e.getStack().c_str());
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
}

void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt, composerGlobal);
    try {
        it->second->call(rt, proxy, changed);
    } catch (const jsi::JSError& e) {
        LOGW("JSError in scope block: %s\n%s", e.what(), e.getStack().c_str());
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
}

// ------------------------------------------------------------ init (JNI/JSI)

void initRdmaComposeJniCache(JNIEnv* env) {
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    g_composeCache.jvm = jvm;
    if (!initComposeJniCache(env)) {
        LOGW("Compose bridge JNI cache init failed");
        return;
    }
    initComposerProxyCache(env);
    if (g_userBridgeJniInit) {
        g_userBridgeJniInit(env);
    }
    LOGI("Compose + user bridge JNI caches initialized");
}

void installRdmaComposeBridge(jsi::Runtime& rt, JavaVM* jvm) {
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
            jobject boxedG = e->NewGlobalRef(boxed);
            e->DeleteLocalRef(boxed);
            // SnapshotMutableState must be created on the UI thread inside the active
            // snapshot; creating it on the Hermes thread would bind it to the wrong
            // snapshot (and reads during composition would throw).
            RdmaResult result = rdmaCallUi([boxedG]() -> RdmaResult {
                JNIEnv* e = getEnv(g_composeCache.jvm);
                if (!e) return RdmaResult::undefined();
                jobject policy = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.structuralEqualityPolicy);
                jobject state = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.mutableStateOf, boxedG, policy);
                e->DeleteLocalRef(policy);
                if (boxedG) e->DeleteGlobalRef(boxedG);
                if (!state) return RdmaResult::undefined();
                jobject global = e->NewGlobalRef(state);
                e->DeleteLocalRef(state);
                return RdmaResult::stateRef(global);
            });
            return rdmaResultToJsi(r, result);
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

// ---------------------------------------------------- callback/lambda (JS side)

static void runCallback(jsi::Runtime& rt, jlong blockId, jobjectArray argsGlobal) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    std::vector<jsi::Value> jsArgs;
    if (argsGlobal) {
        JNIEnv* env = getEnv(g_composeCache.jvm);
        if (env) {
            jsize n = env->GetArrayLength(argsGlobal);
            jsArgs.reserve(n);
            for (jsize i = 0; i < n; i++) {
                jobject elem = env->GetObjectArrayElement(argsGlobal, i);
                jsArgs.push_back(unboxJni(env, rt, elem));
                if (elem) env->DeleteLocalRef(elem);
            }
        }
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    it->second->call(rt, callArgs, jsArgs.size());
}

static void runLambda(jsi::Runtime& rt, jlong blockId, jobjectArray argsGlobal) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    std::vector<jsi::Value> jsArgs;
    if (argsGlobal) {
        JNIEnv* env = getEnv(g_composeCache.jvm);
        if (env) {
            jsize n = env->GetArrayLength(argsGlobal);
            jsArgs.reserve(n);
            for (jsize i = 0; i < n; i++) {
                jobject elem = env->GetObjectArrayElement(argsGlobal, i);
                jsArgs.push_back(unboxJni(env, rt, elem));
                if (elem) env->DeleteLocalRef(elem);
            }
        }
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    // Return value intentionally ignored: service lambdas are Unit-returning and
    // execute asynchronously.
    it->second->call(rt, callArgs, jsArgs.size());
}

static void deleteGlobalRef(jobject ref) {
    if (!ref) return;
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (env) env->DeleteGlobalRef(ref);
}

} // namespace rdma
} // namespace facebook

// -------------------------------------------------------------------- JNI

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContent(
    JNIEnv* env, jclass, jobject composer) {
    using namespace facebook::rdma;
    if (!rdmaIsReady()) return;
    jobject composerGlobal = env->NewGlobalRef(composer);
    rdmaCallJs([composerGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeRegisteredContent(*rt, composerGlobal);
        }
        deleteGlobalRef(composerGlobal);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed) {
    using namespace facebook::rdma;
    jobject composerGlobal = env->NewGlobalRef(composer);
    rdmaCallJs([blockId, composerGlobal, changed] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeScopeBlock(*rt, blockId, composerGlobal, changed);
        }
        deleteGlobalRef(composerGlobal);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeCallback(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args) {
    using namespace facebook::rdma;
    jobjectArray argsGlobal = args ? (jobjectArray)env->NewGlobalRef(args) : nullptr;
    rdmaPostJs([blockId, argsGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runCallback(*rt, blockId, argsGlobal);
        }
        deleteGlobalRef(argsGlobal);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeLambda(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args) {
    using namespace facebook::rdma;
    jobjectArray argsGlobal = args ? (jobjectArray)env->NewGlobalRef(args) : nullptr;
    rdmaPostJs([blockId, argsGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runLambda(*rt, blockId, argsGlobal);
        }
        deleteGlobalRef(argsGlobal);
    });
    return nullptr; // async: no result
}
