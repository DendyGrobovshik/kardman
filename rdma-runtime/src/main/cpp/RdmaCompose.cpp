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
#include "RdmaBlocks.h"
#include "RdmaIdentity.h"
#include "RdmaVtable.h"

#include <jsi/jsi.h>
#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstdio>
#include <android/log.h>

#define LOG_TAG "RdmaCompose"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

ComposeJniCache g_composeCache;

std::shared_ptr<jsi::Object> g_empty;
// Monotonic counter bumped every time a component is registered. The host
// polls it to detect "content changed" and recompose the UI (dynamic plugins).
static std::atomic<int> g_contentVersion{0};
// Plugin JS blocks referenced by id live in the shared registry (RdmaBlocks.h).
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

// Boxed java.lang types and their accessors, resolved once (boot classes are visible
// on every thread). Every value crossing used to do several FindClass/GetMethodID
// lookups (docs/design/runtime_rework.md §3.9).
namespace {
struct BoxJni {
    jclass integer, boolean, string, dbl, lng, flt, enumCls;
    jmethodID intValue, booleanValue, doubleValue, longValue, floatValue, enumName, stringGetBytes;
    jmethodID integerValueOf, booleanValueOf, doubleValueOf, longValueOf, floatValueOf;
    jstring utf8; // "UTF-8" (global)
};
jclass globalClass(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    jclass global = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}
BoxJni makeBoxJni(JNIEnv* env) {
    BoxJni b{};
    b.integer = globalClass(env, "java/lang/Integer");
    b.boolean = globalClass(env, "java/lang/Boolean");
    b.string = globalClass(env, "java/lang/String");
    b.dbl = globalClass(env, "java/lang/Double");
    b.lng = globalClass(env, "java/lang/Long");
    b.flt = globalClass(env, "java/lang/Float");
    b.enumCls = globalClass(env, "java/lang/Enum");
    b.intValue = env->GetMethodID(b.integer, "intValue", "()I");
    b.booleanValue = env->GetMethodID(b.boolean, "booleanValue", "()Z");
    b.doubleValue = env->GetMethodID(b.dbl, "doubleValue", "()D");
    b.longValue = env->GetMethodID(b.lng, "longValue", "()J");
    b.floatValue = env->GetMethodID(b.flt, "floatValue", "()F");
    b.enumName = env->GetMethodID(b.enumCls, "name", "()Ljava/lang/String;");
    b.stringGetBytes = env->GetMethodID(b.string, "getBytes", "(Ljava/lang/String;)[B");
    b.integerValueOf = env->GetStaticMethodID(b.integer, "valueOf", "(I)Ljava/lang/Integer;");
    b.booleanValueOf = env->GetStaticMethodID(b.boolean, "valueOf", "(Z)Ljava/lang/Boolean;");
    b.doubleValueOf = env->GetStaticMethodID(b.dbl, "valueOf", "(D)Ljava/lang/Double;");
    b.longValueOf = env->GetStaticMethodID(b.lng, "valueOf", "(J)Ljava/lang/Long;");
    b.floatValueOf = env->GetStaticMethodID(b.flt, "valueOf", "(F)Ljava/lang/Float;");
    jstring u = env->NewStringUTF("UTF-8");
    b.utf8 = (jstring)env->NewGlobalRef(u);
    env->DeleteLocalRef(u);
    return b;
}
const BoxJni& boxJni(JNIEnv* env) {
    static const BoxJni b = makeBoxJni(env); // thread-safe one-time init
    return b;
}
// A Java String as standard UTF-8 (not JNI's modified UTF-8).
std::string javaStringUtf8(JNIEnv* env, jobject s) {
    const BoxJni& b = boxJni(env);
    auto bytes = (jbyteArray)env->CallObjectMethod(s, b.stringGetBytes, b.utf8);
    if (!bytes) return "";
    jsize len = env->GetArrayLength(bytes);
    std::string out((size_t)len, '\0');
    if (len > 0) env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(&out[0]));
    env->DeleteLocalRef(bytes);
    return out;
}
} // namespace

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
    const BoxJni& b = boxJni(env);
    if (fqn == "rdma.BlockId") {
        // A plugin block id (`RDMA.registerBlock`): a plain non-negative integral JS
        // number, boxed as the kernel's `Long` (e.g. `List<() -> Unit>` widget args).
        if (!v.isNumber()) throwTypeError(rt, "expected a block id (number) at the boundary");
        double dv = v.getNumber();
        if (dv < 0 || (double)(long long)dv != dv) throwTypeError(rt, "invalid block id " + std::to_string(dv));
        return env->CallStaticObjectMethod(b.lng, b.longValueOf, (jlong)dv);
    }
    if (fqn == "kotlin.Long") {
        // The plugin's `Long(low, high)` object (or an integral JS number), exact
        // in all 64 bits (docs/design/type_descriptors.md#long-policy).
        long long lv = rdmaCheckedJLong(rt, v);
        return env->CallStaticObjectMethod(b.lng, b.longValueOf, (jlong)lv);
    }
    if (fqn == "kotlin.Int" || fqn == "kotlin.Double" || fqn == "kotlin.Float") {
        if (!v.isNumber()) throwTypeError(rt, "expected a number (" + fqn + ") at the boundary");
        double dv = v.getNumber();
        if (fqn == "kotlin.Int") {
            if (!(dv >= -2147483648.0 && dv <= 2147483647.0) || (double)(long long)dv != dv) {
                throwTypeError(rt, "value " + std::to_string(dv) + " does not fit " + fqn);
            }
            return env->CallStaticObjectMethod(b.integer, b.integerValueOf, (jint)dv);
        }
        if (fqn == "kotlin.Double") return env->CallStaticObjectMethod(b.dbl, b.doubleValueOf, (jdouble)dv);
        return env->CallStaticObjectMethod(b.flt, b.floatValueOf, (jfloat)dv);
    }
    if (fqn == "kotlin.Boolean") {
        if (!v.isBool()) throwTypeError(rt, "expected a Boolean value at the boundary");
        return env->CallStaticObjectMethod(b.boolean, b.booleanValueOf, (jboolean)v.getBool());
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
// error on a wrong-shaped input or a missing `valueOf(String)` (docs/guide/types.md).
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
            // materializeArray returns an owned local (the kernel list behind a proxy, or a
            // freshly materialized one) — exactly this function's contract.
            return materializeArray(env, rt, g_composeCache.jvm, o, d.elem->serialize());
        }
        case TypeDesc::MAP: {
            if (!v.isObject()) throwTypeError(rt, "expected a Map value for " + d.serialize());
            jsi::Object o = v.asObject(rt);
            return materializeMap(env, rt, g_composeCache.jvm, o, d.key->serialize(), d.value->serialize());
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
    g_composeCache.composeHostClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/RdmaComposeHost");
    g_composeCache.onComponentsChanged =
        env->GetStaticMethodID(g_composeCache.composeHostClass, "onComponentsChanged", "()V");
    g_composeCache.suspendClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/RdmaSuspend");
    g_composeCache.suspendCancel = env->GetStaticMethodID(g_composeCache.suspendClass, "cancel", "(J)V");

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
    g_composeCache.jsIdentityKeyClass = cacheClass(env, "io/github/dendygrobovshik/kardman/runtime/JsIdentityKey");
    g_composeCache.jsIdentityKeyCtor = env->GetMethodID(g_composeCache.jsIdentityKeyClass, "<init>", "(J)V");

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
        g_composeCache.rdmaFunctionBlockId[i] =
            env->GetMethodID(g_composeCache.rdmaFunctionClass[i], "getBlockId", "()J");
        snprintf(name, sizeof(name), "kotlin/jvm/functions/Function%d", i);
        g_composeCache.functionClass[i] = cacheClass(env, name);
        std::string sig = "(";
        for (int k = 0; k < i; k++) sig += "Ljava/lang/Object;";
        sig += ")Ljava/lang/Object;";
        g_composeCache.functionInvoke[i] = env->GetMethodID(g_composeCache.functionClass[i], "invoke", sig.c_str());
    }

    return g_composeCache.stateGetValue &&
           g_composeCache.stateSetValue && g_composeCache.mutableStateOf &&
           g_composeCache.structuralEqualityPolicy &&
           g_composeCache.getEmpty && g_composeCache.updateScope &&
           g_composeCache.scopeBlockCtor &&
           g_composeCache.objectCtor &&
           g_composeCache.jsValueHolderCtor && g_composeCache.jsValueHolderGetId &&
           g_composeCache.jsIdentityKeyCtor &&
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

namespace {
// Owns the global ref of a kernel lambda wrapped as a JS host function; the host function
// (and with it this holder) is destroyed when Hermes collects the JS function.
struct NativeLambdaHolder {
    JavaVM* jvm;
    jobject fn;
    NativeLambdaHolder(JavaVM* j, jobject f) : jvm(j), fn(f) {}
    NativeLambdaHolder(const NativeLambdaHolder&) = delete;
    NativeLambdaHolder& operator=(const NativeLambdaHolder&) = delete;
    ~NativeLambdaHolder() {
        if (JNIEnv* e = getEnv(jvm)) e->DeleteGlobalRef(fn);
    }
};

std::vector<std::string> splitLambdaDescs(const std::string& joined) {
    std::vector<std::string> out;
    if (joined.empty()) return out;
    size_t start = 0;
    while (true) {
        size_t comma = joined.find(',', start);
        out.push_back(joined.substr(start, comma == std::string::npos ? std::string::npos : comma - start));
        if (comma == std::string::npos) break;
        start = comma + 1;
    }
    return out;
}
} // namespace

jsi::Value rdmaWrapNativeFunction(jsi::Runtime& rt, JavaVM* jvm, jobject fn,
                                  const std::string& paramDescs, const std::string& resultDesc) {
    if (!fn) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (!env) return jsi::Value::undefined();
    // A plugin lambda that went through the kernel comes back as the plugin's function.
    for (int i = 0; i <= 3; i++) {
        jclass cls = g_composeCache.rdmaFunctionClass[i];
        if (cls && g_composeCache.rdmaFunctionBlockId[i] && env->IsInstanceOf(fn, cls)) {
            jlong id = env->CallLongMethod(fn, g_composeCache.rdmaFunctionBlockId[i]);
            if (auto js = rdmaBlockGet(id)) return jsi::Value(rt, *js);
        }
    }
    std::vector<std::string> descs = splitLambdaDescs(paramDescs);
    const int arity = (int)descs.size();
    if (arity > 3 || !g_composeCache.functionInvoke[arity]) {
        throw jsi::JSError(rt, "a kernel lambda with " + std::to_string(arity) + " parameters cannot cross (at most 3)");
    }
    auto holder = std::make_shared<NativeLambdaHolder>(jvm, env->NewGlobalRef(fn));
    return jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "rdmaKernelLambda"), (unsigned)arity,
        [holder, descs, resultDesc, arity](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            JNIEnv* e = getEnv(holder->jvm);
            if (!e) throw jsi::JSError(r, "no JNI environment on the Hermes thread");
            JniLocalFrame frame(e);
            jobject a[3] = {nullptr, nullptr, nullptr};
            for (int i = 0; i < arity; i++) {
                a[i] = (size_t)i < count ? rdmaJsToJniTyped(e, r, holder->jvm, args[i], descs[(size_t)i]) : nullptr;
                checkAndThrowJs(e, r);
            }
            jmethodID invoke = g_composeCache.functionInvoke[arity];
            jobject res = nullptr;
            switch (arity) {
                case 0: res = e->CallObjectMethod(holder->fn, invoke); break;
                case 1: res = e->CallObjectMethod(holder->fn, invoke, a[0]); break;
                case 2: res = e->CallObjectMethod(holder->fn, invoke, a[0], a[1]); break;
                default: res = e->CallObjectMethod(holder->fn, invoke, a[0], a[1], a[2]); break;
            }
            checkAndThrowJs(e, r);
            if (resultDesc.empty() || resultDesc == "kotlin.Unit") return jsi::Value::undefined();
            return marshalByTypeDesc(r, holder->jvm, res, resultDesc);
        });
}

JNIEnv* getEnv(JavaVM* jvm) {
    JNIEnv* env = nullptr;
    if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
    return nullptr;
}

// ------------------------------------------------- value conversion (JVM <-> JSI)

jobject boxJsi(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v) {
    const BoxJni& b = boxJni(env);
    if (v.isNumber()) {
        // A JS number is a Kotlin `Int` or `Double` alike. Integral values in Int range
        // keep their historical Integer box (untyped channels rely on it); anything else
        // is boxed losslessly as Double (an Integer box truncated `remember { 1.5 }` to 1).
        double d = v.getNumber();
        if (d >= -2147483648.0 && d <= 2147483647.0 && (double)(int32_t)d == d) {
            return env->CallStaticObjectMethod(b.integer, b.integerValueOf, (jint)d);
        }
        return env->CallStaticObjectMethod(b.dbl, b.doubleValueOf, (jdouble)d);
    }
    if (v.isString()) {
        std::string s = v.getString(rt).utf8(rt);
        return env->NewStringUTF(s.c_str());
    }
    if (v.isBool()) {
        return env->CallStaticObjectMethod(b.boolean, b.booleanValueOf, (jboolean)v.getBool());
    }
    return nullptr;
}

// Boxes a `remember` key for the DisposableEffect bridge. Primitives are boxed
// faithfully (compared structurally by the kernel `remember`); null/undefined stay
// null (stable); object keys are boxed as identity-unique holders, so they are
// always treated as "changed" (the plugin normalizes `Unit` itself).
// A Compose key (docs/internals/compose_protocol.md#object-keys): primitives are boxed and
// compare by value; a kernel object or a state is the native object itself (its `equals`);
// any other JS object crosses as its identity, so the same object is "unchanged".
jobject rdmaBoxComposeKey(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v) {
    if (!v.isObject()) return boxJsi(env, rt, v);
    if (jobject state = stateProxyJObject(rt, v)) return env->NewLocalRef(state);
    jsi::Object o = v.asObject(rt);
    if (o.hasNativeState(rt)) {
        auto ns = std::dynamic_pointer_cast<RdmaObjectNativeState>(o.getNativeState(rt));
        if (ns && ns->getObject()) return env->NewLocalRef(ns->getObject());
    }
    return env->NewObject(g_composeCache.jsIdentityKeyClass, g_composeCache.jsIdentityKeyCtor, (jlong)rdmaJsIdentity(rt, o));
}

static jobject boxKeyForRemember(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v) {
    return rdmaBoxComposeKey(env, rt, v);
}

jsi::Value unboxJni(JNIEnv* env, jsi::Runtime& rt, jobject o) {
    if (o == nullptr) return jsi::Value::null();
    const BoxJni& b = boxJni(env);
    if (env->IsInstanceOf(o, b.integer)) return jsi::Value((double)env->CallIntMethod(o, b.intValue));
    if (env->IsInstanceOf(o, b.string)) return jsi::String::createFromUtf8(rt, javaStringUtf8(env, o));
    if (env->IsInstanceOf(o, b.boolean)) return jsi::Value(env->CallBooleanMethod(o, b.booleanValue) == JNI_TRUE);
    if (env->IsInstanceOf(o, b.dbl)) return jsi::Value(env->CallDoubleMethod(o, b.doubleValue));
    if (env->IsInstanceOf(o, b.lng)) {
        return rdmaLongToJs(rt, (long long)env->CallLongMethod(o, b.longValue)); // plugin's Long object, all 64 bits
    }
    if (env->IsInstanceOf(o, b.flt)) return jsi::Value((double)env->CallFloatMethod(o, b.floatValue));
    // Enum entries cross kernel -> plugin as their name string (any @RDMA enum).
    if (env->IsInstanceOf(o, b.enumCls)) {
        auto name = (jstring)env->CallObjectMethod(o, b.enumName);
        std::string s = name ? javaStringUtf8(env, name) : "";
        if (name) env->DeleteLocalRef(name);
        return jsi::String::createFromUtf8(rt, s);
    }
    return jsi::Value::undefined();
}

// Classifies a boxed JVM value into an RdmaResult descriptor (run on the UI thread).
// Handles Integer/Boolean/Double/Long/Float/String/enum/null; anything else -> Null.
RdmaResult classifyBoxedValue(JNIEnv* env, jobject o) {
    if (o == nullptr) return RdmaResult::null();
    const BoxJni& b = boxJni(env);
    if (env->IsInstanceOf(o, b.integer)) return RdmaResult::number((double)env->CallIntMethod(o, b.intValue));
    if (env->IsInstanceOf(o, b.boolean)) return RdmaResult::boolean(env->CallBooleanMethod(o, b.booleanValue));
    if (env->IsInstanceOf(o, b.string)) return RdmaResult::string(javaStringUtf8(env, o));
    if (env->IsInstanceOf(o, b.dbl)) return RdmaResult::number(env->CallDoubleMethod(o, b.doubleValue));
    if (env->IsInstanceOf(o, b.lng)) {
        return RdmaResult::i64Value((long long)env->CallLongMethod(o, b.longValue)); // full 64 bits
    }
    if (env->IsInstanceOf(o, b.flt)) return RdmaResult::number((double)env->CallFloatMethod(o, b.floatValue));
    if (env->IsInstanceOf(o, b.enumCls)) {
        auto name = (jstring)env->CallObjectMethod(o, b.enumName);
        std::string s = name ? javaStringUtf8(env, name) : "";
        if (name) env->DeleteLocalRef(name);
        return RdmaResult::string(std::move(s));
    }
    return RdmaResult::null();
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

// MutableState proxy: a NativeState object over a shared lazy prototype
// (docs/design/runtime_rework.md §3.8). `get_value`/`set_value` are resolved once
// per runtime by their compile-mangled names and read the state from `this`.
struct StateProxyState : public RdmaTaggedState {
    StateProxyState(jobject state, std::string typeDesc)
        : RdmaTaggedState(RdmaStateKind::State), state_(state), descRaw_(std::move(typeDesc)), desc_(TypeDesc::parseOrAny(descRaw_)) {}
    ~StateProxyState() override {
        if (state_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(state_);
        }
    }
    jobject state_;       // global ref
    std::string descRaw_; // type descriptor of the state's value channel
    TypeDesc desc_;       // parsed form, used on writes
};

static std::shared_ptr<StateProxyState> stateProxyStateOf(jsi::Runtime& r, const jsi::Value& thisVal) {
    auto st = rdmaStateAs<StateProxyState>(r, thisVal, RdmaStateKind::State);
    if (!st) throw jsi::JSError(r, "receiver is not a MutableState proxy");
    return st;
}

static jsi::Value resolveStateMethod(jsi::Runtime& rt, const std::string& n) {
    if (n.rfind("get_value", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "get_value"), 0,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value*, size_t) -> jsi::Value {
                auto st = stateProxyStateOf(r, thisVal);
                jobject state = st->state_;
                std::string descRaw = st->descRaw_;
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
                JniLocalFrame frame(e);
                jobject v = e->CallObjectMethod(state, g_composeCache.stateGetValue);
                checkAndThrowJs(e, r);
                return marshalByTypeDesc(r, g_composeCache.jvm, v, descRaw);
            });
    }
    if (n.rfind("set_value", 0) == 0) {
        return jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "set_value"), 1,
            [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                auto st = stateProxyStateOf(r, thisVal);
                jobject state = st->state_;
                JNIEnv* e = getEnv(g_composeCache.jvm);
                if (!e) return jsi::Value::undefined();
                if (count < 1) return jsi::Value::undefined();
                JniLocalFrame frame(e);
                // Descriptor-typed and strict: a foreign-typed value raises
                // instead of being coerced or silently dropped, and `null` is
                // written as a value only on nullable states.
                jobject boxed = boxStateValue(e, r, args[0], st->desc_);
                if (g_inComposition) {
                    // Rendezvous write during composition: the write runs inside
                    // the UI thread's active composition snapshot, so it is visible
                    // to the same pass' reads and Compose's snapshot system observes
                    // the change (docs/internals/compose_protocol.md#state). A direct write from the
                    // Hermes thread would land in the global snapshot that the open
                    // composition snapshot cannot see.
                    jobject boxedG = boxed ? e->NewGlobalRef(boxed) : nullptr;
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
                checkAndThrowJs(e, r);
                return jsi::Value::undefined();
            });
    }
    return jsi::Value::undefined();
}

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject stateGlobal, const std::string& typeDesc) {
    static jsi::Object* proto = nullptr; // shared, intentionally leaked (lives as long as the runtime)
    if (!proto) proto = new jsi::Object(rdmaLazyProto(rt, resolveStateMethod));
    jsi::Object obj = jsi::Object::create(rt, jsi::Value(rt, *proto));
    obj.setNativeState(rt, std::make_shared<StateProxyState>(stateGlobal, typeDesc));
    return obj;
}

jobject stateProxyJObject(jsi::Runtime& rt, const jsi::Value& v) {
    auto st = rdmaStateAs<StateProxyState>(rt, v, RdmaStateKind::State);
    return st ? st->state_ : nullptr;
}

// ScopeUpdateScope proxy (same object model as the state proxy).
struct ScopeProxyState : public RdmaTaggedState {
    explicit ScopeProxyState(jobject scope) : RdmaTaggedState(RdmaStateKind::Scope), scope_(scope) {}
    ~ScopeProxyState() override {
        if (scope_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(scope_);
        }
    }
    jobject scope_; // global ref
};

static jsi::Value resolveScopeMethod(jsi::Runtime& rt, const std::string& n) {
    if (n.rfind("updateScope", 0) != 0) return jsi::Value::undefined();
    return jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "updateScope"), 1,
        [](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            auto st = rdmaStateAs<ScopeProxyState>(r, thisVal, RdmaStateKind::Scope);
            if (!st) throw jsi::JSError(r, "receiver is not a ScopeUpdateScope proxy");
            if (count < 1 || !args[0].isObject() || !args[0].asObject(r).isFunction(r)) {
                return jsi::Value::undefined();
            }
            jobject scope = st->scope_;
            // Held by the kernel `ComposerScopeBlock` (retained on construction below).
            int64_t id = rdmaBlockRegisterPlugin(r, args[0].asObject(r).asFunction(r), "");
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

jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scopeGlobal) {
    static jsi::Object* proto = nullptr; // shared, intentionally leaked
    if (!proto) proto = new jsi::Object(rdmaLazyProto(rt, resolveScopeMethod));
    jsi::Object obj = jsi::Object::create(rt, jsi::Value(rt, *proto));
    obj.setNativeState(rt, std::make_shared<ScopeProxyState>(scopeGlobal));
    return obj;
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

void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt, composerGlobal);
    try {
        block->call(rt, proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
}

void invokeScopeBlock1(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed, jint p1) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    jsi::Object proxy = makeComposerProxy(rt, composerGlobal);
    try {
        block->call(rt, jsi::Value((double)p1), proxy, changed);
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
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

void notifyComponentsChanged() {
    JNIEnv* e = getEnv(g_composeCache.jvm);
    if (!e || !g_composeCache.composeHostClass || !g_composeCache.onComponentsChanged) return;
    e->CallStaticVoidMethod(g_composeCache.composeHostClass, g_composeCache.onComponentsChanged);
    if (e->ExceptionCheck()) e->ExceptionClear();
}
} // namespace

int64_t rdmaComponentBlock(const std::string& name) {
    auto& reg = componentRegistry();
    std::lock_guard<std::mutex> lk(reg.mutex);
    auto it = reg.ids.find(name);
    return it == reg.ids.end() ? 0 : it->second;
}

// Registers `fn` as the component `name` (replacing an earlier one) and tells the host,
// so call sites still waiting for it compose. JS thread.
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
    notifyComponentsChanged();
}

void invokeComponent(jsi::Runtime& rt, int64_t blockId, jobject composerGlobal, jobjectArray args) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (!env) return;
    std::vector<std::string> descs = rdmaBlockParamDescs(blockId);
    std::vector<jsi::Value> callArgs;
    {
        JniLocalFrame frame(env);
        const jsize n = args ? env->GetArrayLength(args) : 0;
        for (jsize i = 0; i < n; i++) {
            jobject elem = env->GetObjectArrayElement(args, i);
            const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
            callArgs.push_back(typed ? marshalByTypeDesc(rt, g_composeCache.jvm, elem, descs[(size_t)i])
                                     : wrapAny(rt, g_composeCache.jvm, elem));
            if (elem) env->DeleteLocalRef(elem);
        }
    }
    jobject prevComposer = g_currentComposer;
    bool prevInComposition = g_inComposition;
    setCurrentComposer(composerGlobal);
    g_inComposition = true;
    callArgs.push_back(jsi::Value(rt, makeComposerProxy(rt, composerGlobal)));
    callArgs.push_back(jsi::Value(0));
    try {
        block->call(rt, static_cast<const jsi::Value*>(callArgs.data()), callArgs.size());
    } catch (...) {
        g_inComposition = prevInComposition;
        setCurrentComposer(prevComposer);
        throw;
    }
    g_inComposition = prevInComposition;
    setCurrentComposer(prevComposer);
}

// ------------------------------------------------- hotfix slots (rework §6)

namespace {
// Slot cells by callable FQN (docs/design/runtime_rework.md §6). A cell is a generated
// `RdmaSlotCell` of its kernel module; it registers itself from its static initializer,
// and `RDMA.installSlot` writes its volatile `blockId`. Either may come first: an
// install for a not-yet-registered cell waits in `pending`.
struct SlotRegistry {
    std::mutex mutex;
    std::unordered_map<std::string, jobject> cells;      // global refs
    std::unordered_map<std::string, int64_t> installed;  // fqn -> block id
};
SlotRegistry& slotRegistry() {
    static auto* r = new SlotRegistry(); // never destroyed: used until process exit
    return *r;
}

void writeCellBlockId(JNIEnv* env, jobject cell, int64_t blockId) {
    jclass cls = env->GetObjectClass(cell);
    jfieldID fid = env->GetFieldID(cls, "blockId", "J");
    env->DeleteLocalRef(cls);
    if (!fid) {
        env->ExceptionClear();
        return;
    }
    env->SetLongField(cell, fid, (jlong)blockId);
}

std::vector<std::string> splitDescs(const std::string& s) {
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

std::string jstringUtf8(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
} // namespace

static jobject callJsTyped(jsi::Runtime& rt, const std::shared_ptr<jsi::Function>& fn, jobjectArray args,
                          const std::string& paramDescs, const std::string& resultDesc);

static jobject runSlot(jsi::Runtime& rt, int64_t blockId, jobjectArray args,
                       const std::string& paramDescs, const std::string& resultDesc) {
    auto fn = rdmaBlockGet(blockId);
    if (!fn) throw jsi::JSError(rt, "hotfix implementation is no longer registered");
    return callJsTyped(rt, fn, args, paramDescs, resultDesc);
}

// Calls a JS function with kernel arguments marshalled by `paramDescs` and converts the
// result by `resultDesc`. Returns a NEW GLOBAL reference (null for Unit/null). Throws
// jsi::JSError. Runs on the Hermes thread.
static jobject callJsTyped(jsi::Runtime& rt, const std::shared_ptr<jsi::Function>& fn, jobjectArray args,
                          const std::string& paramDescs, const std::string& resultDesc) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    if (!env) throw jsi::JSError(rt, "no JNI environment on the Hermes thread");
    JniLocalFrame frame(env);
    std::vector<std::string> descs = splitDescs(paramDescs);
    std::vector<jsi::Value> jsArgs;
    const jsize n = args ? env->GetArrayLength(args) : 0;
    jsArgs.reserve((size_t)n);
    for (jsize i = 0; i < n; i++) {
        jobject elem = env->GetObjectArrayElement(args, i);
        const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
        jsArgs.push_back(typed ? marshalByTypeDesc(rt, g_composeCache.jvm, elem, descs[(size_t)i])
                               : wrapAny(rt, g_composeCache.jvm, elem));
        if (elem) env->DeleteLocalRef(elem);
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    jsi::Value result = fn->call(rt, callArgs, jsArgs.size());
    if (resultDesc.empty() || resultDesc == "kotlin.Unit") return nullptr;
    jobject local = rdmaJsToJniTyped(env, rt, g_composeCache.jvm, result, resultDesc);
    jobject global = local ? env->NewGlobalRef(local) : nullptr;
    if (local) env->DeleteLocalRef(local);
    return global;
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
            // materializeArray returns an owned local reference.
            jobject arrayList = materializeArray(e, r, g_composeCache.jvm, values, elemDesc);
            checkAndThrowJs(e, r);
            jobject snap = e->CallStaticObjectMethod(g_composeCache.stateFactory, g_composeCache.stateFactoryNewList, arrayList);
            if (arrayList) e->DeleteLocalRef(arrayList);
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
            // materializeMap returns an owned local reference.
            jobject javaMap = materializeMap(e, r, g_composeCache.jvm, values, keyDesc, valueDesc);
            checkAndThrowJs(e, r);
            jobject snap = e->CallStaticObjectMethod(g_composeCache.stateFactory, g_composeCache.stateFactoryNewMap, javaMap);
            if (javaMap) e->DeleteLocalRef(javaMap);
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
            // Owned by the kernel holders of the id (docs/design/runtime_rework.md §4);
            // the same function registered again keeps its id.
            std::string pluginId = (count > 1 && args[1].isString()) ? args[1].getString(r).utf8(r) : "";
            int64_t id = rdmaBlockRegisterPlugin(r, args[0].asObject(r).asFunction(r), pluginId);
            return jsi::Value((double)id);
        });
    rdma.setProperty(rt, "registerBlock", std::move(registerBlockFn));

    // Hotfix slot registration (docs/design/runtime_rework.md §6.3): a polyfill bundle
    // calls `RDMA.installSlot?.("<fqn>", fn)` next to `RDMA.<name> = fn`, so native
    // callers of the callable run the JS implementation too. The block lives as long
    // as the slot holds it (a later install of the same callable replaces it).
    auto installSlotFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "installSlot"), 2,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isString() || !args[1].isObject() || !args[1].asObject(r).isFunction(r)) {
                return jsi::Value(false);
            }
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e) return jsi::Value(false);
            std::string fqn = args[0].getString(r).utf8(r);
            int64_t id = rdmaBlockRegisterExplicit(
                std::make_shared<jsi::Function>(args[1].asObject(r).asFunction(r)), "hotfix");
            rdmaBlockRetain(id);
            int64_t previous = 0;
            {
                auto& reg = slotRegistry();
                std::lock_guard<std::mutex> lk(reg.mutex);
                auto it = reg.installed.find(fqn);
                if (it != reg.installed.end()) previous = it->second;
                reg.installed[fqn] = id;
                auto cell = reg.cells.find(fqn);
                if (cell != reg.cells.end()) writeCellBlockId(e, cell->second, id);
            }
            if (previous != 0) rdmaBlockRelease(previous);
            return jsi::Value(true);
        });
    rdma.setProperty(rt, "installSlot", std::move(installSlotFn));

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

    // The plugin cancelled the coroutine waiting for completion `id`: cancel the kernel call.
    auto cancelContinuationFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forAscii(rt, "cancelContinuation"), 1,
        [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) return jsi::Value::undefined();
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e || !g_composeCache.suspendClass || !g_composeCache.suspendCancel) return jsi::Value::undefined();
            e->CallStaticVoidMethod(g_composeCache.suspendClass, g_composeCache.suspendCancel, (jlong)args[0].getNumber());
            if (e->ExceptionCheck()) e->ExceptionClear();
            return jsi::Value::undefined();
        });
    rdma.setProperty(rt, "cancelContinuation", std::move(cancelContinuationFn));

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
            int64_t id = rdmaBlockRegisterExplicit(fn);

            auto keysArr = args[0].asObject(r).asArray(r);
            JNIEnv* e = getEnv(g_composeCache.jvm);
            if (!e) {
                rdmaBlockErase(id);
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
                rdmaBlockErase(id);
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

// Android hands JS no transient native buffers: nothing to reclaim after a task.
void rdmaPlatformAfterJsTask() {}
void rdmaPlatformAfterUiTask() {}

// ---------------------------------------------------- callback/lambda (JS side)

// Invokes a plugin callback/lambda block with kernel arguments. Arguments are
// marshalled by the block's parameter descriptors when the bridge registered them
// (a `List`/`Map` argument becomes a collection proxy), else by runtime shape.
// The return value is ignored: these invocations are asynchronous.
static void runBlockWithArgs(jsi::Runtime& rt, jlong blockId, jobjectArray argsGlobal) {
    auto block = rdmaBlockGet(blockId);
    if (!block) return;
    std::vector<jsi::Value> jsArgs;
    if (argsGlobal) {
        JNIEnv* env = getEnv(g_composeCache.jvm);
        if (env) {
            JniLocalFrame frame(env);
            std::vector<std::string> descs = rdmaBlockParamDescs(blockId);
            jsize n = env->GetArrayLength(argsGlobal);
            jsArgs.reserve(n);
            for (jsize i = 0; i < n; i++) {
                jobject elem = env->GetObjectArrayElement(argsGlobal, i);
                const bool typed = (size_t)i < descs.size() && !descs[(size_t)i].empty();
                jsArgs.push_back(typed ? marshalByTypeDesc(rt, g_composeCache.jvm, elem, descs[(size_t)i])
                                       : wrapAny(rt, g_composeCache.jvm, elem));
                if (elem) env->DeleteLocalRef(elem);
            }
        }
    }
    const jsi::Value* callArgs = jsArgs.empty() ? nullptr : jsArgs.data();
    try {
        block->call(rt, callArgs, jsArgs.size());
    } catch (const jsi::JSError& e) {
        rdmaUncaughtException(fromJSError(rt, e), rdmaBlockPluginId(blockId));
    }
}

static void runCallback(jsi::Runtime& rt, jlong blockId, jobjectArray argsGlobal) {
    runBlockWithArgs(rt, blockId, argsGlobal);
}

static void runLambda(jsi::Runtime& rt, jlong blockId, jobjectArray argsGlobal) {
    runBlockWithArgs(rt, blockId, argsGlobal);
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

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeComponentBlock(
    JNIEnv* env, jclass, jstring name) {
    using namespace facebook::rdma;
    return (jlong)rdmaComponentBlock(jstringUtf8(env, name));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeComponent(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args, jobject composer) {
    using namespace facebook::rdma;
    if (!rdmaIsReady()) return;
    jobject composerGlobal = env->NewGlobalRef(composer);
    jobject argsGlobal = args ? env->NewGlobalRef(args) : nullptr;
    RdmaExceptionInfo err = rdmaCallJs([blockId, composerGlobal, argsGlobal] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (rt) {
            invokeComponent(*rt, blockId, composerGlobal, (jobjectArray)argsGlobal);
        }
        deleteGlobalRef(composerGlobal);
        if (argsGlobal) deleteGlobalRef(argsGlobal);
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

namespace facebook { namespace rdma {
jobject rdmaLambdaCallJni(JNIEnv* env, jlong blockId, jobjectArray args, const std::string& resultDesc);
} }

extern "C" JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeLambda(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args) {
    using namespace facebook::rdma;
    // A lambda with a result: the caller waits for the value (rework §6.4 routing).
    const std::string resultDesc = rdmaBlockResultDesc(blockId);
    if (!resultDesc.empty()) return rdmaLambdaCallJni(env, blockId, args, resultDesc);
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

namespace facebook {
namespace rdma {

void rdmaSlotRegisterJni(JNIEnv* env, jobject cell, jstring fqnJ) {
    const std::string fqn = jstringUtf8(env, fqnJ);
    auto& reg = slotRegistry();
    std::lock_guard<std::mutex> lk(reg.mutex);
    auto old = reg.cells.find(fqn);
    if (old != reg.cells.end()) env->DeleteGlobalRef(old->second);
    reg.cells[fqn] = env->NewGlobalRef(cell);
    auto it = reg.installed.find(fqn);
    if (it != reg.installed.end()) writeCellBlockId(env, cell, it->second);
}

// The FQN a block was installed for (diagnostics only).
static std::string slotFqnOf(int64_t blockId) {
    auto& reg = slotRegistry();
    std::lock_guard<std::mutex> lk(reg.mutex);
    for (const auto& [fqn, id] : reg.installed) {
        if (id == blockId) return fqn;
    }
    return "hotfix #" + std::to_string(blockId);
}

namespace {
// One native → JS call executed on the Hermes thread on behalf of a waiting caller.
// Lives on the heap: a timed-out caller may return before the task runs or finishes.
struct JniJsCall {
    JavaVM* jvm = nullptr;
    jobject argsGlobal = nullptr;   // consumed by the task
    jobject resultGlobal = nullptr; // produced by the task
    RdmaExceptionInfo error;        // a JS exception, set by the task
    bool ran = false;

    void releaseArgs() {
        if (!argsGlobal) return;
        if (JNIEnv* e = getEnv(jvm)) e->DeleteGlobalRef(argsGlobal);
        argsGlobal = nullptr;
    }
    void releaseAll() {
        releaseArgs();
        if (resultGlobal) {
            if (JNIEnv* e = getEnv(jvm)) e->DeleteGlobalRef(resultGlobal);
            resultGlobal = nullptr;
        }
    }
};

// Runs `run` (on the Hermes thread) for the caller and turns the outcome into the JNI
// return value: the result as a new local reference, `fallthrough` when the runtime is
// not up yet, or a thrown `RdmaException` (JS exception or timeout).
jobject callJsForJni(JNIEnv* env, jobjectArray args, jobject fallthrough, const std::string& what,
                     std::function<jobject(jsi::Runtime&, jobjectArray)> run) {
    auto call = std::make_shared<JniJsCall>();
    call->jvm = g_composeCache.jvm;
    call->argsGlobal = args ? env->NewGlobalRef(args) : nullptr;
    RdmaExceptionInfo waitErr = rdmaCallJsBlocking(
        [call, run] {
            facebook::jsi::Runtime* rt = getRdmaRuntime();
            if (rt) {
                call->ran = true;
                try {
                    call->resultGlobal = run(*rt, (jobjectArray)call->argsGlobal);
                } catch (const facebook::jsi::JSError& e) {
                    call->error = fromJSError(*rt, e);
                }
            }
            call->releaseArgs();
        },
        [call] { call->releaseAll(); },
        what);
    if (!waitErr.empty()) {
        throwIfError(env, waitErr);
        return nullptr;
    }
    // Finished: the task no longer touches `call`.
    if (!call->error.empty()) {
        call->releaseAll();
        throwIfError(env, call->error);
        return nullptr;
    }
    if (!call->ran) return fallthrough;
    jobject local = call->resultGlobal ? env->NewLocalRef(call->resultGlobal) : nullptr;
    call->releaseAll();
    return local;
}
} // namespace

// A native call of an `open` method overridden by a plugin subclass (the vtable): the
// override runs on the Hermes thread from any calling thread (§6.4); its result is
// converted by the method's result descriptor; a JS exception or a timeout is thrown as
// `RdmaException` (§2.8). `fallthrough` when the method is not overridden.
jobject rdmaVtableInvokeJni(JNIEnv* env, jlong vtablePtr, jint vtableId, jobjectArray args,
                            jstring paramDescs, jstring resultDesc, jobject fallthrough) {
    if (vtablePtr == 0) return fallthrough;
    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    if (vtableId < 0 || (size_t)vtableId >= vt->entries.size() || !vt->entries[(size_t)vtableId]) return fallthrough;
    std::shared_ptr<jsi::Function> fn = vt->entries[(size_t)vtableId];
    const std::string pd = jstringUtf8(env, paramDescs);
    const std::string rd = jstringUtf8(env, resultDesc);
    return callJsForJni(env, args, fallthrough, "override #" + std::to_string(vtableId),
                        [fn, pd, rd](jsi::Runtime& rt, jobjectArray argsGlobal) {
                            return callJsTyped(rt, fn, argsGlobal, pd, rd);
                        });
}

// A native caller of a hotfixed callable (rework §6.4, D5 = B): routed to the Hermes
// thread from any thread; a JS exception or a timeout is thrown as `RdmaException`.
jobject rdmaSlotInvokeJni(JNIEnv* env, jlong blockId, jobjectArray args, jstring paramDescs, jstring resultDesc, jobject fallthrough) {
    const std::string pd = jstringUtf8(env, paramDescs);
    const std::string rd = jstringUtf8(env, resultDesc);
    return callJsForJni(env, args, fallthrough, slotFqnOf(blockId),
                        [blockId, pd, rd](jsi::Runtime& rt, jobjectArray argsGlobal) {
                            return runSlot(rt, blockId, argsGlobal, pd, rd);
                        });
}

// A native call of a plugin lambda that returns a value: runs on the Hermes thread from
// any calling thread, like a hotfix slot; arguments and the result are converted by the
// block's descriptors; a JS exception or a timeout is thrown as `RdmaException`.
jobject rdmaLambdaCallJni(JNIEnv* env, jlong blockId, jobjectArray args, const std::string& resultDesc) {
    std::string pd;
    for (const auto& d : rdmaBlockParamDescs(blockId)) {
        if (!pd.empty()) pd += ",";
        pd += d;
    }
    return callJsForJni(env, args, nullptr, "lambda #" + std::to_string(blockId),
                        [blockId, pd, resultDesc](jsi::Runtime& rt, jobjectArray argsGlobal) {
                            auto fn = rdmaBlockGet(blockId);
                            if (!fn) throw jsi::JSError(rt, "the plugin lambda is no longer registered");
                            return callJsTyped(rt, fn, argsGlobal, pd, resultDesc);
                        });
}

} // namespace rdma
} // namespace facebook

// ----------------------------------------------------------- DisposableEffect

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeEffectBody(
    JNIEnv* env, jclass, jlong blockId) {
    using namespace facebook::rdma;
    int64_t resultId = 0;
    RdmaExceptionInfo err = rdmaCallJs([blockId, &resultId] {
        facebook::jsi::Runtime* rt = getRdmaRuntime();
        if (!rt) return;
        auto fn = rdmaBlockGet(blockId);
        if (!fn) return;
        // The block is single-use: release it before running so reentrant calls
        // cannot observe a half-consumed block.
        rdmaBlockErase(blockId);
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
        rdmaBlockErase(blockId);
    });
}

// ------------------------------------------------- block holders (runtime_rework §4)

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeRetainBlock(
    JNIEnv*, jclass, jlong blockId) {
    facebook::rdma::rdmaBlockRetain(blockId);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeReleaseBlock(
    JNIEnv*, jclass, jlong blockId) {
    facebook::rdma::rdmaBlockRelease(blockId);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeSetBlockParamDescs(
    JNIEnv* env, jclass, jlong blockId, jstring descs) {
    const char* c = descs ? env->GetStringUTFChars(descs, nullptr) : nullptr;
    facebook::rdma::rdmaBlockSetParamDescs(blockId, std::string(c ? c : ""));
    if (c) env->ReleaseStringUTFChars(descs, c);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeBlockCount(JNIEnv*, jclass) {
    return (jint)facebook::rdma::rdmaBlockCount();
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeReleaseJsValue(
    JNIEnv*, jclass, jlong valueId) {
    using namespace facebook::rdma;
    // Released by a JsValueHolder leaving the composition; erased on the JS thread.
    rdmaPostJs([valueId] { g_jsValues.erase(valueId); });
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
