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
#include "RdmaTypeDesc.h"
#include "ListHandle.h"
#include "MapHandle.h"
#include "RdmaNativeState.h"

#include <jsi/jsi.h>
#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <atomic>
#include <cstdio>
#include <android/log.h>

#define LOG_TAG "RdmaCompose"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

ComposeJniCache g_composeCache;

static std::shared_ptr<jsi::Function> g_content;
static std::string g_contentPluginId;
std::unordered_map<int64_t, std::string> g_blockPluginId;
std::shared_ptr<jsi::Object> g_empty;
// Monotonic counter bumped every time a new content is registered. The host
// polls it to detect "content changed" and recompose the UI (dynamic plugins).
static std::atomic<int> g_contentVersion{0};
static std::unordered_map<int64_t, std::shared_ptr<jsi::Function>> g_scopeBlocks;
static int64_t g_nextScopeBlockId = 1;
std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
int64_t g_nextJsValueId = 1;
jobject g_currentComposer = nullptr; // borrowed global ref; owned by the content/scope task

// Suspend completions: id -> the two registered JS continuation callbacks (result
// and error) plus the return type descriptor registered by the generated
// `_<name>Async` host function. All entries are created and consumed on the Hermes
// thread; the JVM side only posts back a (id, result/throwable) pair.
struct SuspendContinuation {
    std::shared_ptr<jsi::Function> onResult;
    std::shared_ptr<jsi::Function> onError;
    std::string typeDesc;
};
static std::unordered_map<int64_t, SuspendContinuation> g_suspendContinuations;
static int64_t g_nextContinuationId = 1;

// True on the Hermes thread while content/scope blocks are executing (i.e. during
// composition). State reads performed in this window rendezvous to the UI thread so
// that Compose's snapshot read observer records the dependency.
thread_local bool g_inComposition = false;

static UserBridgeInstaller g_userBridge = nullptr;
static UserBridgeJniInit g_userBridgeJniInit = nullptr;
static ObjectWrapper g_objectWrapper = nullptr;

extern "C" void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer) {
    g_userBridge = installer;
}

extern "C" void rdmaSetUserBridgeJniInit(UserBridgeJniInit init) {
    g_userBridgeJniInit = init;
}

extern "C" void rdmaSetObjectWrapper(ObjectWrapper wrapper) {
    g_objectWrapper = wrapper;
}

jsi::Value wrapAny(jsi::Runtime& rt, HostContext jvm, jobject obj) {
    JNIEnv* env = getEnv(jvm);
    if (!env) return jsi::Value::undefined();
    jsi::Value unboxed = unboxJni(env, rt, obj);
    if (!unboxed.isUndefined()) return unboxed;
    if (g_objectWrapper) {
        return g_objectWrapper(rt, jvm, obj);
    }
    return jsi::Value::undefined();
}

// -------------------------------------------------------- suspend completion

// Kernel-side keep of a plugin value for one `@RDMA` class leaf: the handle points
// at the kernel object itself (docs/design/type_descriptors.md — state holds the
// object, no new identity implied).
static jobject rdmaBoxObjectLeaf(jsi::Runtime& rt, const jsi::Value& v);

[[noreturn]] static void throwTypeError(jsi::Runtime& rt, const std::string& message);

// Strict scalar/`@RDMA` boxing for one descriptor leaf (flags already stripped):
// `Int`/`Long`/`Double`/`Float`/`Boolean`/`String` and `@RDMA` handles. A value
// that does not fit the leaf raises a cross-runtime error — no silent coercion.
// Used by state writes and collection element materialization (which carries the
// same leaves inside its `elementType` strings). `@RDMA enum` leaves are handled by
// the channel-specific paths (an enum needs its `valueOf`, see boxStateValue).
jobject rdmaBoxLeafValue(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v, const std::string& fqn) {
    if (fqn == "kotlin.Long") {
        // The plugin's `Long(low, high)` object (or an integral JS number), exact
        // in all 64 bits (docs/design/type_descriptors.md#long-policy).
        long long lv = rdmaCheckedJLong(rt, v);
        jclass cls = env->FindClass("java/lang/Long");
        jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(J)Ljava/lang/Long;");
        jobject r = env->CallStaticObjectMethod(cls, valueOf, (jlong)lv);
        env->DeleteLocalRef(cls);
        return r;
    }
    if (fqn == "kotlin.Int" || fqn == "kotlin.Double" || fqn == "kotlin.Float") {
        if (!v.isNumber()) throwTypeError(rt, "expected a number (" + fqn + ") at the boundary");
        double dv = v.getNumber();
        if (fqn == "kotlin.Int") {
            if (!(dv >= -2147483648.0 && dv <= 2147483647.0) || (double)(long long)dv != dv) {
                throwTypeError(rt, "value " + std::to_string(dv) + " does not fit " + fqn);
            }
            jclass cls = env->FindClass("java/lang/Integer");
            jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(I)Ljava/lang/Integer;");
            jobject r = env->CallStaticObjectMethod(cls, valueOf, (jint)dv);
            env->DeleteLocalRef(cls);
            return r;
        }
        if (fqn == "kotlin.Double") {
            jclass cls = env->FindClass("java/lang/Double");
            jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(D)Ljava/lang/Double;");
            jobject r = env->CallStaticObjectMethod(cls, valueOf, (jdouble)dv);
            env->DeleteLocalRef(cls);
            return r;
        }
        jclass cls = env->FindClass("java/lang/Float");
        jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(F)Ljava/lang/Float;");
        jobject r = env->CallStaticObjectMethod(cls, valueOf, (jfloat)dv);
        env->DeleteLocalRef(cls);
        return r;
    }
    if (fqn == "kotlin.Boolean") {
        if (!v.isBool()) throwTypeError(rt, "expected a Boolean value at the boundary");
        jclass cls = env->FindClass("java/lang/Boolean");
        jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", "(Z)Ljava/lang/Boolean;");
        jobject r = env->CallStaticObjectMethod(cls, valueOf, (jboolean)v.getBool());
        env->DeleteLocalRef(cls);
        return r;
    }
    if (fqn == "kotlin.String") {
        if (!v.isString()) throwTypeError(rt, "expected a String value at the boundary");
        std::string s = v.getString(rt).utf8(rt);
        return env->NewStringUTF(s.c_str());
    }
    // `@RDMA` class leaf (and unknown leafs): the plugin-side value is the object's
    // handle; the caller stores the kernel object itself.
    return rdmaBoxObjectLeaf(rt, v);
}

static jobject rdmaBoxObjectLeaf(jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isObject()) {
        jsi::Object o = v.asObject(rt);
        if (o.hasNativeState(rt)) {
            auto ns = std::dynamic_pointer_cast<RdmaObjectNativeState>(o.getNativeState(rt));
            if (ns) return ns->getObject();
        }
    }
    return nullptr;
}

// Builds the cross-runtime error for a type-marshal violation and throws it into
// JS. See docs/design/type_descriptors.md: a value that does not fit its channel's
// type descriptor is a loud error, never a silent coercion or drop.
[[noreturn]] static void throwTypeError(jsi::Runtime& rt, const std::string& message) {
    rdmaThrowTypeError(rt, message); // shared construction (RdmaJsLong.h) — no drift
}

// Marshals a kernel-side `value` (a JVM object) into a jsi::Value according to the
// type descriptor of its channel (docs/design/type_descriptors.md): leaves go
// through `wrapAny` (primitives/String/enum-as-name/`@RDMA`/Unit), collections
// become native-backed proxies. `Long` values outside the exact JS range are
// rejected by the leaf path instead of rounding.
static jsi::Value marshalByTypeDesc(jsi::Runtime& rt, JavaVM* jvm, jobject value, const std::string& desc) {
    if (!value) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (!env) return jsi::Value::undefined();
    TypeDesc d = TypeDesc::parseOrAny(desc);
    switch (d.kind) {
        case TypeDesc::LIST: {
            jobject global = env->NewGlobalRef(value);
            return createListHandle(rt, jvm, global, d.elem->serialize());
        }
        case TypeDesc::MAP: {
            jobject global = env->NewGlobalRef(value);
            return createMapHandle(rt, jvm, global, d.key->serialize(), d.value->serialize());
        }
        case TypeDesc::LEAF:
        default:
            if (d.fqn == "kotlin.Unit") return jsi::Value::undefined();
            return wrapAny(rt, jvm, value);
    }
}

// Resolves an application class at call time, on any thread. `FindClass` only sees
// app classes on Java-created threads (a native-attached thread resolves with the
// boot loader — see RdmaJni.cpp), so the app's context ClassLoader captured at
// cache init does the lookup. Returns a new local reference, or null when the
// class cannot be resolved.
static jclass findAppClass(JNIEnv* env, const std::string& fqn) {
    if (g_composeCache.appClassLoader && g_composeCache.classLoaderLoadClass) {
        std::string dotted = fqn;
        for (char& c : dotted) if (c == '/') c = '.';
        jstring name = env->NewStringUTF(dotted.c_str());
        jobject cls = env->CallObjectMethod(g_composeCache.appClassLoader, g_composeCache.classLoaderLoadClass, name);
        env->DeleteLocalRef(name);
        if (env->ExceptionCheck() || !cls) {
            env->ExceptionClear();
            if (cls) env->DeleteLocalRef(cls);
            return nullptr;
        }
        return (jclass)cls;
    }
    return env->FindClass(fqn.c_str());
}

// Boxes the enum entry name string into the concrete JVM enum via the enum class's
// `valueOf(String)`. `fqn` should be the enum class FQN (e.g. `com.example.HttpMethod`).
// Returns the entry as a new local reference the caller owns. Raises a cross-runtime
// error on a wrong-shaped input or a missing `valueOf(String)` (docs/types.md).
// Shared by state writes and collection element materialization — no drift between them.
jobject rdmaBoxEnumValue(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v, const std::string& fqn) {
    if (!v.isString()) throwTypeError(rt, "expected the enum entry name string for " + fqn);
    std::string name = v.getString(rt).utf8(rt);
    std::string internal = fqn;
    for (char& c : internal) if (c == '.') c = '/';
    jclass cls = findAppClass(env, internal);
    if (!cls || env->ExceptionCheck()) {
        env->ExceptionClear();
        if (cls) env->DeleteLocalRef(cls);
        throwTypeError(rt, "unknown enum type " + fqn);
    }
    // `valueOf`'s exact return type is the enum class itself, not `java.lang.Enum`
    // — the JNI signature must spell the concrete FQN (`(Ljava/lang/String;)Lcom/example/MyEnum;`),
    // else GetStaticMethodID finds nothing and every enum write throws (audit #24).
    std::string valueOfSig = "(Ljava/lang/String;)L" + internal + ";";
    jmethodID valueOf = env->GetStaticMethodID(cls, "valueOf", valueOfSig.c_str());
    if (!valueOf || env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        throwTypeError(rt, "enum " + fqn + " has no valueOf(String)");
    }
    jstring jname = env->NewStringUTF(name.c_str());
    jobject r = env->CallStaticObjectMethod(cls, valueOf, jname);
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(cls);
    checkAndThrowJs(env, rt);
    return r;
}

// Converts a plugin-side value into a JVM object according to the channel's type
// descriptor (docs/design/type_descriptors.md). Strict by contract: a value that
// does not fit the descriptor raises a cross-runtime error (`throwTypeError`), and
// `null` is a value only on nullable channels. The kernel side never coerces or
// silently drops a write.
//
// Ownership: every non-null return is a NEW **local** reference the caller owns and
// must release with `DeleteLocalRef` (nullable `nullptr` needs no release). Held
// kernel references (`globalListRef_`/`globalMapRef_`/`RdmaObjectNativeState`) are
// returned as a fresh local so callers never touch a borrowed ref (audit #1/#2).
static jobject boxStateValue(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v, const TypeDesc& d) {
    // `kotlin.Unit` is a channel's "no-value": an `undefined` write or initial is legal
    // (audit #27) and materializes as `null` (the kernel state only holds "unit"-bearing
    // values). Everything else still goes through the strict null-shape rule below.
    if (d.kind == TypeDesc::LEAF && d.fqn == "kotlin.Unit") return nullptr;
    if (v.isNull() || v.isUndefined()) {
        if (d.nullable) return nullptr;
        throwTypeError(rt, "null is not a value of the non-nullable state type " + d.serialize());
    }
    switch (d.kind) {
        case TypeDesc::LIST: {
            if (!v.isObject()) throwTypeError(rt, "expected a List value for " + d.serialize());
            jsi::Object o = v.asObject(rt);
            if (o.hasNativeState(rt)) {
                auto ns = std::dynamic_pointer_cast<ListNativeState>(o.getNativeState(rt));
                if (ns) return env->NewLocalRef(ns->globalListRef_); // kernel list: same object
            }
            // materializeArray returns an owned global; hand back an owned local instead.
            jobject g = materializeArray(env, rt, g_composeCache.jvm, o, d.elem->serialize());
            if (!g) return nullptr;
            jobject local = env->NewLocalRef(g);
            env->DeleteGlobalRef(g);
            return local;
        }
        case TypeDesc::MAP: {
            if (!v.isObject()) throwTypeError(rt, "expected a Map value for " + d.serialize());
            jsi::Object o = v.asObject(rt);
            if (o.hasNativeState(rt)) {
                auto ns = std::dynamic_pointer_cast<MapNativeState>(o.getNativeState(rt));
                if (ns) return env->NewLocalRef(ns->globalMapRef_);
            }
            jobject g = materializeMap(env, rt, g_composeCache.jvm, o, d.key->serialize(), d.value->serialize());
            if (!g) return nullptr;
            jobject local = env->NewLocalRef(g);
            env->DeleteGlobalRef(g);
            return local;
        }
        case TypeDesc::LEAF:
        default:
            break;
    }

    const std::string& fqn = d.fqn;
    if (d.isEnum) {
        return rdmaBoxEnumValue(env, rt, v, fqn);
    }
    if (fqn == "kotlin.Int" || fqn == "kotlin.Long" ||
        fqn == "kotlin.Double" || fqn == "kotlin.Float") {
        // Scalar leaves share the strict boundary boxer with collection elements
        // (shape checks included; Long accepts its Long object, see RdmaJsLong.h).
        return rdmaBoxLeafValue(env, rt, v, fqn);
    }
    if (fqn == "kotlin.Boolean" || fqn == "kotlin.String" || fqn == "kotlin.Unit") {
        return rdmaBoxLeafValue(env, rt, v, fqn);
    }
    // `kotlin.Any` is not reachable on a state channel: the plugin compiler's `stateTypeDescOf`
    // rejects `Any`/`Any?`/function/`dynamic` states at compile time, and `EmptyList`/`EmptyMap`
    // no longer collapse to `list:kotlin.Any`/`map:kotlin.Any:kotlin.Any`. If it somehow leaks
    // in, fall through to the loud type error below: no silent-mismatch dispatch on a typed
    // channel (docs/design/type_descriptors.md).

    // `@RDMA` class leaf: the plugin-side value is the object's handle; the state
    // stores the kernel object itself (identity/lifetime rules stay the crossing
    // default — see docs/design/type_descriptors.md#non-goals).
    if (v.isObject()) {
        jsi::Object o = v.asObject(rt);
        if (o.hasNativeState(rt)) {
            auto ns = std::dynamic_pointer_cast<RdmaObjectNativeState>(o.getNativeState(rt));
            if (ns) return env->NewLocalRef(ns->getObject());
        }
    }
    throwTypeError(rt, "value does not fit the state type " + d.serialize());
}

void rdmaRegisterSuspendResultType(int64_t completionId, const std::string& typeDesc) {
    auto it = g_suspendContinuations.find(completionId);
    if (it != g_suspendContinuations.end()) {
        it->second.typeDesc = typeDesc;
    }
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

    g_composeCache.effectsKt = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/RdmaEffectsKt");
    g_composeCache.stateFactory = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/RdmaStateFactory");
    g_composeCache.stateFactoryNewList = env->GetStaticMethodID(g_composeCache.stateFactory, "newListFrom", "(Ljava/util/ArrayList;)Ljava/lang/Object;");
    g_composeCache.stateFactoryNewMap = env->GetStaticMethodID(g_composeCache.stateFactory, "newMapFrom", "(Ljava/util/LinkedHashMap;)Ljava/lang/Object;");
    g_composeCache.sideEffect = env->GetStaticMethodID(g_composeCache.effectsKt, "sideEffect", "(JLandroidx/compose/runtime/Composer;I)V");
    g_composeCache.disposableEffect = env->GetStaticMethodID(g_composeCache.effectsKt, "disposableEffect", "([Ljava/lang/Object;JLandroidx/compose/runtime/Composer;I)Z");

    g_composeCache.updateScope = env->GetMethodID(g_composeCache.scopeUpdateScopeClass, "updateScope", "(Lkotlin/jvm/functions/Function2;)V");
    g_composeCache.scopeBlockCtor = env->GetMethodID(g_composeCache.scopeBlockClass, "<init>", "(J)V");

    g_composeCache.objectClass = cacheClass(env, "java/lang/Object");
    g_composeCache.objectCtor = env->GetMethodID(g_composeCache.objectClass, "<init>", "()V");

    // Capture the app's context ClassLoader for call-time lookups of app classes
    // on native-attached threads (see ComposeJniCache::appClassLoader).
    if (!g_composeCache.appClassLoader) {
        jclass threadCls = env->FindClass("java/lang/Thread");
        jclass loaderCls = env->FindClass("java/lang/ClassLoader");
        if (threadCls && loaderCls) {
            jmethodID currentThread = env->GetStaticMethodID(threadCls, "currentThread", "()Ljava/lang/Thread;");
            jmethodID getContextLoader = env->GetMethodID(threadCls, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
            jobject thread = currentThread ? env->CallStaticObjectMethod(threadCls, currentThread) : nullptr;
            jobject loader = thread ? env->CallObjectMethod(thread, getContextLoader) : nullptr;
            if (loader && !env->ExceptionCheck()) {
                g_composeCache.appClassLoader = env->NewGlobalRef(loader);
                g_composeCache.classLoaderLoadClass =
                    env->GetMethodID(loaderCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (loader) env->DeleteLocalRef(loader);
            if (thread) env->DeleteLocalRef(thread);
        }
        if (threadCls) env->DeleteLocalRef(threadCls);
        if (loaderCls) env->DeleteLocalRef(loaderCls);
    }

    g_composeCache.jsValueHolderClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/JsValueHolder");
    g_composeCache.jsValueHolderCtor = env->GetMethodID(g_composeCache.jsValueHolderClass, "<init>", "(J)V");
    g_composeCache.jsValueHolderGetId = env->GetMethodID(g_composeCache.jsValueHolderClass, "getId", "()J");

    g_composeCache.rdmaExceptionClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/RdmaException");
    g_composeCache.rdmaExceptionCtor = env->GetMethodID(
        g_composeCache.rdmaExceptionClass, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lio/github/dendygrobovshik/kardman/runtime/RdmaException;)V");
    g_composeCache.bundleLoadExceptionClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/BundleLoadException");
    g_composeCache.bundleLoadExceptionCtor = env->GetMethodID(
        g_composeCache.bundleLoadExceptionClass, "<init>",
        "(Ljava/lang/String;Lio/github/dendygrobovshik/kardman/runtime/RdmaException;)V");

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
           g_composeCache.effectsKt && g_composeCache.sideEffect &&
           g_composeCache.disposableEffect &&
           g_composeCache.rdmaExceptionCtor &&
           g_composeCache.bundleLoadExceptionCtor &&
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

// Boxes a `remember` key for the DisposableEffect bridge. Primitives are boxed
// faithfully (compared structurally by the kernel `remember`); null/undefined stay
// null (stable); object keys are boxed as identity-unique holders, so they are
// always treated as "changed" (the plugin normalizes `Unit` itself).
static jobject boxKeyForRemember(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v) {
    if (v.isObject()) {
        return env->NewObject(g_composeCache.objectClass, g_composeCache.objectCtor);
    }
    return boxJsi(env, rt, v);
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
    jclass enumCls = env->FindClass("java/lang/Enum");

    // Enum entries cross kernel -> plugin as their name string (any @RDMA enum).
    jsi::Value enumResult = jsi::Value::undefined();
    if (enumCls && env->IsInstanceOf(o, enumCls)) {
        jmethodID enumName = env->GetMethodID(enumCls, "name", "()Ljava/lang/String;");
        jstring jname = (jstring)env->CallObjectMethod(o, enumName);
        const char* nameCstr = env->GetStringUTFChars(jname, nullptr);
        std::string nameStr(nameCstr);
        env->ReleaseStringUTFChars(jname, nameCstr);
        env->DeleteLocalRef(jname);
        enumResult = jsi::String::createFromUtf8(rt, nameStr);
    }

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
        jlong lv = env->CallLongMethod(o, longValue);
        result = rdmaLongToJs(rt, (long long)lv); // plugin's Long object, all 64 bits
    } else if (env->IsInstanceOf(o, flt)) {
        jmethodID floatValue = env->GetMethodID(flt, "floatValue", "()F");
        result = jsi::Value((double)env->CallFloatMethod(o, floatValue));
    }

    if (result.isUndefined() && !enumResult.isUndefined()) {
        result = std::move(enumResult);
    }

    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(integer);
    env->DeleteLocalRef(boolean);
    env->DeleteLocalRef(string);
    env->DeleteLocalRef(dbl);
    env->DeleteLocalRef(lng);
    env->DeleteLocalRef(flt);
    if (enumCls) env->DeleteLocalRef(enumCls);
    return result;
}

// Classifies a boxed JVM value into an RdmaResult descriptor (run on the UI thread).
// Handles Integer/Boolean/Double/Long/Float/String/enum/null; anything else -> Null.
RdmaResult classifyBoxedValue(JNIEnv* env, jobject o) {
    if (o == nullptr) return RdmaResult::null();
    jclass integer = env->FindClass("java/lang/Integer");
    jclass boolean = env->FindClass("java/lang/Boolean");
    jclass string = env->FindClass("java/lang/String");
    jclass dbl = env->FindClass("java/lang/Double");
    jclass lng = env->FindClass("java/lang/Long");
    jclass flt = env->FindClass("java/lang/Float");
    jclass enumCls = env->FindClass("java/lang/Enum");

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
        jlong lv = env->CallLongMethod(o, longValue);
        r = RdmaResult::i64Value((long long)lv); // full 64 bits, never squeezed through a double
    } else if (env->IsInstanceOf(o, flt)) {
        jmethodID floatValue = env->GetMethodID(flt, "floatValue", "()F");
        r = RdmaResult::number((double)env->CallFloatMethod(o, floatValue));
    } else if (enumCls && env->IsInstanceOf(o, enumCls)) {
        jmethodID enumName = env->GetMethodID(enumCls, "name", "()Ljava/lang/String;");
        jstring jname = (jstring)env->CallObjectMethod(o, enumName);
        const char* nameCstr = env->GetStringUTFChars(jname, nullptr);
        r = RdmaResult::string(std::string(nameCstr));
        env->ReleaseStringUTFChars(jname, nameCstr);
        env->DeleteLocalRef(jname);
    } else {
        r = RdmaResult::null();
    }

    env->DeleteLocalRef(integer);
    env->DeleteLocalRef(boolean);
    env->DeleteLocalRef(string);
    env->DeleteLocalRef(dbl);
    env->DeleteLocalRef(lng);
    env->DeleteLocalRef(flt);
    if (enumCls) env->DeleteLocalRef(enumCls);
    return r;
}

jthrowable makeRdmaException(JNIEnv* env, const RdmaExceptionInfo& info) {
    if (!env || !g_composeCache.rdmaExceptionClass || !g_composeCache.rdmaExceptionCtor) {
        return nullptr;
    }
    jthrowable cause = nullptr;
    if (info.cause) {
        cause = makeRdmaException(env, *info.cause);
    }
    jstring type = env->NewStringUTF(info.originalExceptionType.c_str());
    jstring msg = env->NewStringUTF(info.message.c_str());
    jstring stack = env->NewStringUTF(info.stack.c_str());
    jthrowable ex = (jthrowable)env->NewObject(
        g_composeCache.rdmaExceptionClass, g_composeCache.rdmaExceptionCtor,
        type, msg, stack, cause);
    if (type) env->DeleteLocalRef(type);
    if (msg) env->DeleteLocalRef(msg);
    if (stack) env->DeleteLocalRef(stack);
    if (cause) env->DeleteLocalRef(cause);
    return ex;
}

jthrowable makeBundleLoadException(JNIEnv* env, const RdmaExceptionInfo& info) {
    if (!env || !g_composeCache.bundleLoadExceptionClass || !g_composeCache.bundleLoadExceptionCtor) {
        return nullptr;
    }
    jthrowable cause = makeRdmaException(env, info);
    jstring msg = env->NewStringUTF(info.message.c_str());
    jthrowable ex = (jthrowable)env->NewObject(
        g_composeCache.bundleLoadExceptionClass, g_composeCache.bundleLoadExceptionCtor,
        msg, cause);
    if (msg) env->DeleteLocalRef(msg);
    if (cause) env->DeleteLocalRef(cause);
    return ex;
}

jobject g_uncaughtHandlerRef = nullptr;
jmethodID g_uncaughtHandlerInvoke = nullptr;

void androidUncaughtHandler(const RdmaExceptionInfo& info, const std::string& pluginId) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (!env) return;
    jthrowable ex = makeRdmaException(env, info);
    if (!ex) return;
    if (g_uncaughtHandlerRef && g_uncaughtHandlerInvoke) {
        jstring jpid = env->NewStringUTF(pluginId.c_str());
        jobject ret = env->CallObjectMethod(g_uncaughtHandlerRef, g_uncaughtHandlerInvoke, jpid, ex);
        if (jpid) env->DeleteLocalRef(jpid);
        if (ret) env->DeleteLocalRef(ret);
    }
    env->DeleteLocalRef(ex);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

static std::string jstringToStd(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

RdmaExceptionInfo fromJniThrowable(JNIEnv* env, jthrowable t) {
    RdmaExceptionInfo info;
    if (!env || !t) return info;

    jclass throwableClass = env->FindClass("java/lang/Throwable");
    jclass classClass = env->FindClass("java/lang/Class");

    jclass objClass = env->GetObjectClass(t);
    jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    jstring jname = (jstring)env->CallObjectMethod(objClass, getName);
    info.originalExceptionType = jstringToStd(env, jname);

    jmethodID getMessage = env->GetMethodID(throwableClass, "getMessage", "()Ljava/lang/String;");
    jstring jmsg = (jstring)env->CallObjectMethod(t, getMessage);
    info.message = jstringToStd(env, jmsg);

    jmethodID getStackTrace = env->GetMethodID(throwableClass, "getStackTrace", "()[Ljava/lang/StackTraceElement;");
    jobjectArray arr = (jobjectArray)env->CallObjectMethod(t, getStackTrace);
    if (arr) {
        jclass steClass = env->FindClass("java/lang/StackTraceElement");
        jmethodID toString = env->GetMethodID(steClass, "toString", "()Ljava/lang/String;");
        jsize n = env->GetArrayLength(arr);
        std::string stack;
        for (jsize i = 0; i < n; i++) {
            jobject el = env->GetObjectArrayElement(arr, i);
            jstring s = (jstring)env->CallObjectMethod(el, toString);
            stack += jstringToStd(env, s);
            stack += "\n";
            if (s) env->DeleteLocalRef(s);
            if (el) env->DeleteLocalRef(el);
        }
        info.stack = stack;
        env->DeleteLocalRef(steClass);
        env->DeleteLocalRef(arr);
    }

    jmethodID getCause = env->GetMethodID(throwableClass, "getCause", "()Ljava/lang/Throwable;");
    jthrowable cause = (jthrowable)env->CallObjectMethod(t, getCause);
    if (cause && cause != t) {
        info.cause = std::make_shared<RdmaExceptionInfo>(fromJniThrowable(env, cause));
    }
    if (cause) env->DeleteLocalRef(cause);

    if (jname) env->DeleteLocalRef(jname);
    if (jmsg) env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(objClass);
    env->DeleteLocalRef(throwableClass);
    env->DeleteLocalRef(classClass);
    return info;
}

void checkAndThrowJs(JNIEnv* env, jsi::Runtime& rt) {
    if (!env || !env->ExceptionCheck()) return;
    jthrowable t = env->ExceptionOccurred();
    env->ExceptionClear();
    if (!t) return;
    RdmaExceptionInfo info = fromJniThrowable(env, t);
    env->DeleteLocalRef(t);
    throwJsException(rt, info);
}

// Captures a pending JNI exception into an Error result, for UI-thread compose ops
// that return their result through RdmaResult (Native -> JS, §5).
bool captureJniError(JNIEnv* e, RdmaResult& out) {
    if (!e || !e->ExceptionCheck()) return false;
    jthrowable t = e->ExceptionOccurred();
    e->ExceptionClear();
    RdmaExceptionInfo info = t ? fromJniThrowable(e, t) : RdmaExceptionInfo{};
    if (t) e->DeleteLocalRef(t);
    out = RdmaResult::fromError(std::move(info));
    return true;
}

// ----------------------------------------------------------------- proxies

class StateProxyHost : public jsi::HostObject {
public:
    StateProxyHost(jobject state, std::string typeDesc)
        : state_(state), descRaw_(std::move(typeDesc)), desc_(TypeDesc::parseOrAny(descRaw_)) {}
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
                [state = state_, descRaw = descRaw_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    if (g_inComposition) {
                        // Rendezvous to the UI thread so Compose's snapshot read
                        // observer records the dependency on this state, and carry
                        // the value back as a descriptor-typed handle: the JS side
                        // marshals it by the channel's type descriptor.
                        RdmaResult res = rdmaCallUi([state, descRaw]() -> RdmaResult {
                            JNIEnv* e = getEnv(g_composeCache.jvm);
                            if (!e) return RdmaResult::undefined();
                            jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                            RdmaResult err;
                            if (captureJniError(e, err)) { if (v) e->DeleteLocalRef(v); return err; }
                            jobject global = v ? e->NewGlobalRef(v) : nullptr;
                            if (v) e->DeleteLocalRef(v);
                            return RdmaResult::hostValue(global, descRaw);
                        });
                        return rdmaResultToJsi(r, res);
                    }
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                    checkAndThrowJs(e, r);
                    jsi::Value out = marshalByTypeDesc(r, g_composeCache.jvm, v, descRaw);
                    if (v) e->DeleteLocalRef(v);
                    return out;
                });
        }
        if (n.rfind("set_value", 0) == 0) {
            auto fn = jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "set_value"), 1,
                [state = state_, desc = desc_](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    if (count < 1) return jsi::Value::undefined();
                    // Descriptor-typed and strict: a foreign-typed value raises
                    // instead of being coerced or silently dropped, and `null` is
                    // written as a value only on nullable states.
                    jobject boxed = boxStateValue(e, r, args[0], desc);
                    if (g_inComposition) {
                        // Rendezvous write during composition: the write runs inside
                        // the UI thread's active composition snapshot, so it is visible
                        // to the same pass' reads and Compose's snapshot system observes
                        // the change (docs/bridges.md §state). A direct write from the
                        // Hermes thread would land in the global snapshot that the open
                        // composition snapshot cannot see.
                        jobject boxedG = boxed ? e->NewGlobalRef(boxed) : nullptr;
                        if (boxed) e->DeleteLocalRef(boxed);
                        RdmaResult res = rdmaCallUi([state, boxedG]() -> RdmaResult {
                            JNIEnv* e2 = getEnv(g_composeCache.jvm);
                            if (!e2) return RdmaResult::undefined();
                            e2->CallVoidMethod(state, g_composeCache.stateSetValue, boxedG);
                            RdmaResult err;
                            if (captureJniError(e2, err)) {
                                if (boxedG) e2->DeleteGlobalRef(boxedG);
                                return err;
                            }
                            if (boxedG) e2->DeleteGlobalRef(boxedG);
                            return RdmaResult::undefined();
                        });
                        return rdmaResultToJsi(r, res); // Error kind rethrows into JS
                    }
                    e->CallVoidMethod(state, g_composeCache.stateSetValue, boxed);
                    if (boxed) e->DeleteLocalRef(boxed);
                    checkAndThrowJs(e, r);
                    return jsi::Value::undefined();
                });
            return fn;
        }
        return jsi::Value::undefined();
    }

    jobject state() const { return state_; }

private:
    jobject state_;       // global ref
    std::string descRaw_; // type descriptor of the state's value channel
    TypeDesc desc_;       // parsed form, used on writes
};

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject stateGlobal, const std::string& typeDesc) {
    auto host = std::make_shared<StateProxyHost>(stateGlobal, typeDesc);
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
        case RdmaResultKind::I64: return rdmaLongToJs(rt, result.i64); // plugin's Long object, exact
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
            jobject ref = (jobject)result.ref;
            result.ref = nullptr; // ownership transferred to the proxy
            return makeStateProxy(rt, ref, result.str);
        }
        case RdmaResultKind::ScopeRef: {
            if (!result.ref) return jsi::Value::null();
            jobject ref = (jobject)result.ref;
            result.ref = nullptr; // ownership transferred to the proxy
            return makeScopeUpdateScopeProxy(rt, ref);
        }
        case RdmaResultKind::HostValue: {
            if (!result.ref) return jsi::Value::null();
            jobject ref = (jobject)result.ref;
            result.ref = nullptr; // marshal keeps ownership with us
            jsi::Value out = marshalByTypeDesc(rt, g_composeCache.jvm, ref, result.str);
            JNIEnv* e2 = getEnv(g_composeCache.jvm);
            if (e2) e2->DeleteGlobalRef(ref);
            return out;
        }
        case RdmaResultKind::Error: {
            throwJsException(rt, result.error);
        }
    }
    return jsi::Value::undefined();
}

// ------------------------------------------------------------- content/scope

int getContentVersion() {
    return g_contentVersion.load(std::memory_order_acquire);
}

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
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
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
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
}

void invokeScopeBlock1(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed, jint p1) {
    auto it = g_scopeBlocks.find(blockId);
    if (it == g_scopeBlocks.end()) return;
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt, composerGlobal);
    try {
        it->second->call(rt, jsi::Value((double)p1), proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
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

void installRdmaComposeBridge(jsi::Runtime& rt, HostContext jvm) {
    jsi::Object rdma(rt);

    auto registerFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "registerContent"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count > 0 && args[0].isObject() && args[0].asObject(r).isFunction(r)) {
                g_content = std::make_shared<jsi::Function>(args[0].asObject(r).asFunction(r));
                g_contentPluginId = (count > 1 && args[1].isString()) ? args[1].getString(r).utf8(r) : "";
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
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e || count < 1) return jsi::Value::undefined();
            // The channel's value type descriptor is baked in by the plugin bridge
            // (docs/design/type_descriptors.md). Pre-descriptor bundles call with one
            // argument and keep their old Int/Boolean/String contract.
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "kotlin.Any";
            TypeDesc desc = TypeDesc::parseOrAny(descRaw);
            jobject boxed = boxStateValue(e, r, args[0], desc);
            // On typed channels a `null` initial is legal (nullable states).
            jobject boxedG = boxed ? e->NewGlobalRef(boxed) : nullptr;
            if (boxed) e->DeleteLocalRef(boxed);
            // SnapshotMutableState must be created on the UI thread inside the active
            // snapshot; creating it on the Hermes thread would bind it to the wrong
            // snapshot (and reads during composition would throw).
            RdmaResult result = rdmaCallUi([boxedG, descRaw]() -> RdmaResult {
                JNIEnv* e = getEnv(g_composeCache.jvm);
                if (!e) return RdmaResult::undefined();
                jobject policy = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.structuralEqualityPolicy);
                RdmaResult err;
                if (captureJniError(e, err)) {
                    if (policy) e->DeleteLocalRef(policy);
                    if (boxedG) e->DeleteGlobalRef(boxedG);
                    return err;
                }
                jobject state = e->CallStaticObjectMethod(g_composeCache.snapshotStateKt, g_composeCache.mutableStateOf, boxedG, policy);
                e->DeleteLocalRef(policy);
                if (captureJniError(e, err)) {
                    if (boxedG) e->DeleteGlobalRef(boxedG);
                    return err;
                }
                if (boxedG) e->DeleteGlobalRef(boxedG);
                if (!state) return RdmaResult::undefined();
                jobject global = e->NewGlobalRef(state);
                e->DeleteLocalRef(state);
                return RdmaResult::stateRef(global, descRaw);
            });
            return rdmaResultToJsi(r, result);
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
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e || count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "list:kotlin.Any";
            TypeDesc d = TypeDesc::parseOrAny(descRaw);
            std::string elemDesc = (d.kind == TypeDesc::LIST && d.elem) ? d.elem->serialize() : "kotlin.Any";
            jsi::Object values = args[0].asObject(r);
            // materializeArray returns either an OWNED global (fresh materialization) or a
            // BORROWED `globalListRef_` (reusing a kernel handle) — only free what we own
            // (same pattern as `putAll`: audit #2).
            bool borrowed = false;
            if (values.hasNativeState(r)) {
                auto ns = std::dynamic_pointer_cast<ListNativeState>(values.getNativeState(r));
                if (ns) borrowed = true;
            }
            jobject arrayList = materializeArray(e, r, g_composeCache.jvm, values, elemDesc);
            checkAndThrowJs(e, r);
            jobject snap = e->CallStaticObjectMethod(g_composeCache.stateFactory, g_composeCache.stateFactoryNewList, arrayList);
            if (arrayList && !borrowed) e->DeleteGlobalRef(arrayList);
            checkAndThrowJs(e, r);
            if (!snap) return jsi::Value::undefined();
            jobject global = e->NewGlobalRef(snap);
            e->DeleteLocalRef(snap);
            return createListHandle(r, g_composeCache.jvm, global, elemDesc, /*stateBacked=*/true);
        });
    rdma.setProperty(rt, "mutableStateListOf", std::move(mutableStateListOfFn));

    auto mutableStateMapOfFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "mutableStateMapOf"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e || count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            std::string descRaw = (count > 1 && args[1].isString())
                ? args[1].getString(r).utf8(r)
                : "map:kotlin.Any:kotlin.Any";
            TypeDesc d = TypeDesc::parseOrAny(descRaw);
            std::string keyDesc = (d.kind == TypeDesc::MAP && d.key) ? d.key->serialize() : "kotlin.Any";
            std::string valueDesc = (d.kind == TypeDesc::MAP && d.value) ? d.value->serialize() : "kotlin.Any";
            jsi::Object values = args[0].asObject(r);
            // Same borrowed/owned split as `mutableStateListOf` (audit #2).
            bool borrowed = false;
            if (values.hasNativeState(r)) {
                auto ns = std::dynamic_pointer_cast<MapNativeState>(values.getNativeState(r));
                if (ns) borrowed = true;
            }
            jobject javaMap = materializeMap(e, r, g_composeCache.jvm, values, keyDesc, valueDesc);
            checkAndThrowJs(e, r);
            jobject snap = e->CallStaticObjectMethod(g_composeCache.stateFactory, g_composeCache.stateFactoryNewMap, javaMap);
            if (javaMap && !borrowed) e->DeleteGlobalRef(javaMap);
            checkAndThrowJs(e, r);
            if (!snap) return jsi::Value::undefined();
            jobject global = e->NewGlobalRef(snap);
            e->DeleteLocalRef(snap);
            return createMapHandle(r, g_composeCache.jvm, global, keyDesc, valueDesc, /*stateBacked=*/true);
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

    // Registers the two JS callbacks (result and error) that resume a plugin
    // suspend continuation. Returns the completion id; the generated `_<name>Async`
    // host function later attaches the return type descriptor to the same id.
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
            jlong blockId = (jlong)args[0].getNumber();
            jobject composer = g_currentComposer;
            RdmaResult result = rdmaCallUi([blockId, composer]() -> RdmaResult {
                JNIEnv* e = getEnv(g_composeCache.jvm);
                if (!e) return RdmaResult::undefined();
                e->CallStaticVoidMethod(g_composeCache.effectsKt, g_composeCache.sideEffect, blockId, composer, 0);
                RdmaResult err;
                if (captureJniError(e, err)) return err;
                return RdmaResult::undefined();
            });
            if (result.kind == RdmaResultKind::Error) return rdmaResultToJsi(r, result);
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

            // Register the (single-use) effect body, then let the kernel decide
            // whether it is adopted by a freshly created observer.
            auto fn = std::make_shared<jsi::Function>(args[1].asObject(r).asFunction(r));
            int64_t id = g_nextScopeBlockId++;
            g_scopeBlocks[id] = fn;

            auto keysArr = args[0].asObject(r).asArray(r);
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e) {
                g_scopeBlocks.erase(id);
                return jsi::Value::undefined();
            }
            jsize n = (jsize)keysArr.size(r);
            jobjectArray jkeys = e->NewObjectArray(n, g_composeCache.objectClass, nullptr);
            for (jsize i = 0; i < n; i++) {
                jsi::Value kv = keysArr.getValueAtIndex(r, (size_t)i);
                jobject boxed = boxKeyForRemember(e, r, kv);
                e->SetObjectArrayElement(jkeys, i, boxed);
                if (boxed) e->DeleteLocalRef(boxed);
            }
            jobject jkeysGlobal = e->NewGlobalRef(jkeys);
            e->DeleteLocalRef(jkeys);
            jobject composer = g_currentComposer;

            RdmaResult result = rdmaCallUi([id, composer, jkeysGlobal]() -> RdmaResult {
                JNIEnv* e = getEnv(g_composeCache.jvm);
                if (!e) {
                    if (jkeysGlobal) {
                        // Best-effort: without an env we cannot delete the ref here;
                        // the global ref is cleaned up by the UI thread teardown.
                    }
                    return RdmaResult::boolean(false);
                }
                jboolean used = e->CallStaticBooleanMethod(
                    g_composeCache.effectsKt, g_composeCache.disposableEffect,
                    jkeysGlobal, (jlong)id, composer, 0);
                RdmaResult err;
                if (captureJniError(e, err)) {
                    if (jkeysGlobal) e->DeleteGlobalRef(jkeysGlobal);
                    return err;
                }
                if (jkeysGlobal) e->DeleteGlobalRef(jkeysGlobal);
                return RdmaResult::boolean((bool)used);
            });

            // If the kernel reused the previous observer (keys unchanged), this
            // freshly registered block is unused and can be released immediately.
            if (result.kind == RdmaResultKind::Error) return rdmaResultToJsi(r, result);
            if (!result.b) {
                g_scopeBlocks.erase(id);
            }
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "disposableEffect", std::move(disposableEffectFn));

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
                jsArgs.push_back(wrapAny(rt, g_composeCache.jvm, elem));
                if (elem) env->DeleteLocalRef(elem);
            }
        }
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    try {
        it->second->call(rt, callArgs, jsArgs.size());
    } catch (const jsi::JSError& e) {
        auto pid = g_blockPluginId.find(blockId);
        rdmaUncaughtException(fromJSError(rt, e), pid != g_blockPluginId.end() ? pid->second : "");
    }
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
                jsArgs.push_back(wrapAny(rt, g_composeCache.jvm, elem));
                if (elem) env->DeleteLocalRef(elem);
            }
        }
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    // Return value intentionally ignored: service lambdas are Unit-returning and
    // execute asynchronously.
    try {
        it->second->call(rt, callArgs, jsArgs.size());
    } catch (const jsi::JSError& e) {
        auto pid = g_blockPluginId.find(blockId);
        rdmaUncaughtException(fromJSError(rt, e), pid != g_blockPluginId.end() ? pid->second : "");
    }
}

static void deleteGlobalRef(jobject ref) {
    if (!ref) return;
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (env) env->DeleteGlobalRef(ref);
}

static void throwIfError(JNIEnv* env, const RdmaExceptionInfo& info) {
    if (info.empty()) return;
    jthrowable ex = makeRdmaException(env, info);
    if (ex) {
        env->Throw(ex);
        env->DeleteLocalRef(ex);
    }
}

// Resumes the plugin suspend continuation with a marshalled result. Runs on the
// Hermes thread; `valueGlobal` is a global ref consumed here.
static void runSuspendResult(jsi::Runtime& rt, jlong completionId, jobject valueGlobal) {
    auto it = g_suspendContinuations.find(completionId);
    if (it == g_suspendContinuations.end()) {
        deleteGlobalRef(valueGlobal);
        return;
    }
    auto onResult = it->second.onResult;
    std::string desc = it->second.typeDesc;
    g_suspendContinuations.erase(it);
    try {
        jsi::Value v = marshalByTypeDesc(rt, g_composeCache.jvm, valueGlobal, desc);
        const jsi::Value* callArgs = &v;
        onResult->call(rt, callArgs, static_cast<size_t>(1));
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), "");
    }
    deleteGlobalRef(valueGlobal);
}

// Resumes the plugin suspend continuation with an error. Runs on the Hermes
// thread; `throwableGlobal` is a global ref consumed here.
static void runSuspendError(jsi::Runtime& rt, jlong completionId, jobject throwableGlobal) {
    auto it = g_suspendContinuations.find(completionId);
    if (it == g_suspendContinuations.end()) {
        deleteGlobalRef(throwableGlobal);
        return;
    }
    auto onError = it->second.onError;
    g_suspendContinuations.erase(it);
    try {
        JNIEnv* env = getEnv(g_composeCache.jvm);
        if (env) {
            RdmaExceptionInfo info = fromJniThrowable(env, (jthrowable)throwableGlobal);
            jsi::Object err = makeJsErrorObject(rt, info);
            jsi::Value errValue(rt, err);
            const jsi::Value* callArgs = &errValue;
            onError->call(rt, callArgs, static_cast<size_t>(1));
        }
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), "");
    }
    deleteGlobalRef(throwableGlobal);
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
    RdmaExceptionInfo err = rdmaCallJs([composerGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeRegisteredContent(*rt, composerGlobal);
        }
        deleteGlobalRef(composerGlobal);
    });
    throwIfError(env, err);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed) {
    using namespace facebook::rdma;
    jobject composerGlobal = env->NewGlobalRef(composer);
    RdmaExceptionInfo err = rdmaCallJs([blockId, composerGlobal, changed] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeScopeBlock(*rt, blockId, composerGlobal, changed);
        }
        deleteGlobalRef(composerGlobal);
    });
    throwIfError(env, err);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock1(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed, jint p1) {
    using namespace facebook::rdma;
    jobject composerGlobal = env->NewGlobalRef(composer);
    RdmaExceptionInfo err = rdmaCallJs([blockId, composerGlobal, changed, p1] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeScopeBlock1(*rt, blockId, composerGlobal, changed, p1);
        }
        deleteGlobalRef(composerGlobal);
    });
    throwIfError(env, err);
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

// ----------------------------------------------------------- DisposableEffect

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeEffectBody(
    JNIEnv* env, jclass, jlong blockId) {
    using namespace facebook::rdma;
    int64_t resultId = 0;
    RdmaExceptionInfo err = rdmaCallJs([blockId, &resultId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        auto it = g_scopeBlocks.find(blockId);
        if (it == g_scopeBlocks.end()) return;
        auto fn = it->second;
        // The block is single-use: release it before running so reentrant calls
        // cannot observe a half-consumed block.
        g_scopeBlocks.erase(it);
        facebook::jsi::Value result = fn->call(*rt, nullptr, 0);
        if (result.isObject()) {
            auto obj = std::make_shared<facebook::jsi::Object>(result.asObject(*rt));
            resultId = g_nextJsValueId++;
            g_jsValues[resultId] = obj;
        }
    });
    throwIfError(env, err);
    return resultId;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeDispose(
    JNIEnv* env, jclass, jlong resultId) {
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
    throwIfError(env, err);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeFreeBlock(
    JNIEnv* env, jclass, jlong blockId) {
    using namespace facebook::rdma;
    rdmaCallJs([blockId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        g_scopeBlocks.erase(blockId);
    });
}

// -------------------------------------------------------------- suspend resume

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContinuation(
    JNIEnv* env, jclass, jlong completionId, jobject value) {
    using namespace facebook::rdma;
    jobject valueGlobal = value ? env->NewGlobalRef(value) : nullptr;
    rdmaPostJs([completionId, valueGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runSuspendResult(*rt, completionId, valueGlobal);
        } else {
            deleteGlobalRef(valueGlobal);
        }
    });
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContinuationError(
    JNIEnv* env, jclass, jlong completionId, jthrowable throwable) {
    using namespace facebook::rdma;
    jobject throwableGlobal = throwable ? env->NewGlobalRef(throwable) : nullptr;
    rdmaPostJs([completionId, throwableGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            runSuspendError(*rt, completionId, throwableGlobal);
        } else {
            deleteGlobalRef(throwableGlobal);
        }
    });
}
