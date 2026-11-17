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
#include "MapHandle.h"
#include "ListHandle.h"
#include "RdmaCompose.h"
#include <jni.h>

namespace facebook {
namespace rdma {

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

static bool isListElementType(const std::string& t) {
    return t.rfind("list:", 0) == 0;
}
static bool isMapElementType(const std::string& t) {
    return t.rfind("map:", 0) == 0;
}

// Converts a single JS value into a JVM object using a type descriptor. Nested
// lists/maps materialize recursively; @RDMA objects reuse their held jobject;
// primitives/strings are boxed.
static jobject jsiToJobjectMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm,
                               const jsi::Value& v, const std::string& type) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (isListElementType(type)) {
        jsi::Object obj = v.asObject(rt);
        return materializeArray(env, rt, jvm, obj, type.substr(5));
    }
    if (isMapElementType(type)) {
        auto kv = parseMapBody(type.substr(4));
        jsi::Object obj = v.asObject(rt);
        return materializeMap(env, rt, jvm, obj, kv.first, kv.second);
    }
    // Scalar/enum leaves follow the descriptor's declared type (the `?`/`!` marks
    // stripped), NOT the value's shape — same descriptor-first order as the C-ABI
    // `jsiToHandle`, so the two backends cannot drift. An enum-marked leaf
    // (`MyEnum!`) rebuilds via `valueOf`; without this the name string would
    // silently become a `java.lang.String` rather than the enum entry (types.md).
    std::string fqn = type.substr(0, type.find_first_of("?!"));
    bool isEnum = type.find('!') != std::string::npos;
    if (isEnum) return rdmaBoxEnumValue(env, rt, v, fqn);
    if (fqn == "kotlin.Int" || fqn == "kotlin.Long" || fqn == "kotlin.Double" ||
        fqn == "kotlin.Float" || fqn == "kotlin.Boolean" || fqn == "kotlin.String") {
        return rdmaBoxLeafValue(env, rt, v, fqn);
    }
    // `@RDMA` class leaf: reuse the kernel object behind the plugin-side wrapper.
    if (v.isObject()) {
        jsi::Object eo = v.asObject(rt);
        if (eo.hasNativeState(rt)) {
            auto ns = eo.getNativeState(rt);
            if (ns) {
                auto objState = std::static_pointer_cast<RdmaObjectNativeState>(ns);
                return objState->getObject();
            }
        }
        return nullptr;
    }
    // Unknown leaves keep the runtime-dispatch boxer (pre-descriptor behavior,
    // e.g. callback-id lists) — the same fallback as the C-ABI `jsiToHandle`.
    return boxJsi(env, rt, v);
}

// Wraps a JVM object into a JS value using a type descriptor. **Consumes** the
// caller's local reference `obj` (every branch releases it) — callers must not
// delete it.
static jsi::Value wrapTyped(jsi::Runtime& r, JavaVM* jvm, jobject obj, const std::string& type) {
    if (!obj) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (isListElementType(type)) {
        jobject global = nullptr;
        if (env) global = env->NewGlobalRef(obj);
        if (env) env->DeleteLocalRef(obj);
        return createListHandle(r, jvm, global, type.substr(5));
    }
    if (isMapElementType(type)) {
        auto kv = parseMapBody(type.substr(4));
        jobject global = nullptr;
        if (env) global = env->NewGlobalRef(obj);
        if (env) env->DeleteLocalRef(obj);
        return createMapHandle(r, jvm, global, kv.first, kv.second);
    }
    jsi::Value result = wrapAny(r, jvm, obj);
    if (env) env->DeleteLocalRef(obj);
    return result;
}

static std::shared_ptr<MapNativeState> mapStateOf(jsi::Runtime& r, const jsi::Object& obj) {
    return std::static_pointer_cast<MapNativeState>(obj.getNativeState(r));
}

// Kotlin/JS mangled-name aliasing (implemented below, next to morphHashMap).
static void installMapMangledForwards(jsi::Runtime& rt, jsi::Object& obj, const std::vector<std::string>& names);
static std::vector<std::string> mapMangledNamesOf(jsi::Runtime& rt);

// Invokes the original `checkIsMutable` (recorded during morphing) before a
// mutation.
static void checkIsMutable(jsi::Runtime& r, const jsi::Object& thisObj, const std::string& name) {
    if (name.empty()) return;
    auto fn = thisObj.getProperty(r, name.c_str());
    if (fn.isObject() && fn.asObject(r).isFunction(r)) {
        fn.asObject(r).asFunction(r).callWithThis(r, thisObj, nullptr, 0);
    }
}

// Creates a live view object over the map's keySet/values/entrySet. `mode`:
// 0 = keys, 1 = values, 2 = entries. The view uses entry iteration to reach a
// given index (O(n) per access), keeping it live with respect to the native map.
static jsi::Object createView(jsi::Runtime& rt, JavaVM* jvm, jobject mapRef, int mode, const std::string& elemType) {
    auto view = jsi::Object(rt);
    view.setNativeState(rt, nullptr);

    auto sizeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "size"), 0,
        [jvm, mapRef](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            jclass mapCls = env->GetObjectClass(mapRef);
            jmethodID sizeMethod = env->GetMethodID(mapCls, "size", "()I");
            return jsi::Value((double)env->CallIntMethod(mapRef, sizeMethod));
        });
    view.setProperty(rt, "size", std::move(sizeFn));

    auto getFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "get"), 1,
        [jvm, mapRef, mode, elemType](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t) -> jsi::Value {
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            int index = (int)args[0].getNumber();
            jclass mapCls = env->GetObjectClass(mapRef);
            jmethodID entrySetMethod = env->GetMethodID(mapCls, "entrySet", "()Ljava/util/Set;");
            jobject entrySet = env->CallObjectMethod(mapRef, entrySetMethod);
            jclass setCls = env->GetObjectClass(entrySet);
            jmethodID iteratorMethod = env->GetMethodID(setCls, "iterator", "()Ljava/util/Iterator;");
            jobject it = env->CallObjectMethod(entrySet, iteratorMethod);
            jclass itCls = env->GetObjectClass(it);
            jmethodID hasNextMethod = env->GetMethodID(itCls, "hasNext", "()Z");
            jmethodID nextMethod = env->GetMethodID(itCls, "next", "()Ljava/lang/Object;");
            jobject entry = nullptr;
            int i = 0;
            while (i <= index && env->CallBooleanMethod(it, hasNextMethod)) {
                jobject e = env->CallObjectMethod(it, nextMethod);
                if (i == index) entry = e; else env->DeleteLocalRef(e);
                i++;
            }
            env->DeleteLocalRef(it);
            env->DeleteLocalRef(entrySet);
            if (!entry) return jsi::Value::undefined();
            jclass entryCls = env->GetObjectClass(entry);
            jmethodID getKeyMethod = env->GetMethodID(entryCls, "getKey", "()Ljava/lang/Object;");
            jmethodID getValueMethod = env->GetMethodID(entryCls, "getValue", "()Ljava/lang/Object;");
            if (mode == 0) {
                jobject key = env->CallObjectMethod(entry, getKeyMethod);
                env->DeleteLocalRef(entry);
                return wrapTyped(r, jvm, key, elemType);
            }
            if (mode == 1) {
                jobject value = env->CallObjectMethod(entry, getValueMethod);
                env->DeleteLocalRef(entry);
                return wrapTyped(r, jvm, value, elemType);
            }
            // entries mode
            auto out = jsi::Object(r);
            jobject key = env->CallObjectMethod(entry, getKeyMethod);
            jobject value = env->CallObjectMethod(entry, getValueMethod);
            out.setProperty(r, "key", wrapTyped(r, jvm, key, elemType));
            out.setProperty(r, "value", wrapTyped(r, jvm, value, elemType));
            env->DeleteLocalRef(entry);
            return out;
        });
    view.setProperty(rt, "get", std::move(getFn));
    return view;
}

// For a state-backed handle (a kernel `SnapshotStateMap`, see MapNativeState) an
// operation during composition first touches the map's state on the UI thread so
// Compose's snapshot read observer records the dependency (the same contract as the
// list handles — docs/design/type_descriptors.md#state-semantics).
static void touchCompositionReadMap(const std::shared_ptr<MapNativeState>& state) {
    if (!g_inComposition) return;
    rdmaCallUi([state]() -> RdmaResult {
        JNIEnv* e = getEnv(state->jvm_);
        if (!e) return RdmaResult::undefined();
        jclass cls = e->GetObjectClass(state->globalMapRef_);
        jmethodID sizeMethod = e->GetMethodID(cls, "size", "()I");
        e->CallIntMethod(state->globalMapRef_, sizeMethod);
        RdmaResult err;
        if (captureJniError(e, err)) { e->DeleteLocalRef(cls); return err; }
        e->DeleteLocalRef(cls);
        return RdmaResult::undefined();
    });
}

static void wrapStateBackedMapReads(jsi::Runtime& rt, jsi::Object& obj, const std::shared_ptr<MapNativeState>& state) {
    static const char* kNames[] = {
        "get", "set", "remove", "clear", "containsKey", "sizeImpl", "isEmpty",
        "keys", "values", "entries", "toString",
    };
    for (const char* name : kNames) {
        if (!obj.hasProperty(rt, name)) continue;
        jsi::Value fn = obj.getProperty(rt, name);
        if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) continue;
        auto inner = std::make_shared<jsi::Function>(fn.asObject(rt).asFunction(rt));
        auto wrapped = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, name), 0,
            [inner, state](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
                touchCompositionReadMap(state);
                return inner->callWithThis(r, thisVal.asObject(r), args, count);
            });
        obj.setProperty(rt, name, std::move(wrapped));
    }
}

void populateMapHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    auto ns = std::make_shared<MapNativeState>(jvm, globalMapRef, keyType, valueType);
    ns->stateBacked_ = stateBacked;
    obj.setNativeState(rt, ns);

    auto getFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "get"), 1,
        [jvm, keyType, valueType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jobject key = jsiToJobjectMap(env, r, jvm, args[0], keyType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID getMethod = env->GetMethodID(mapCls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
            jobject value = env->CallObjectMethod(state->globalMapRef_, getMethod, key);
            return wrapTyped(r, jvm, value, valueType);
        });
    obj.setProperty(rt, "get", std::move(getFn));

    auto setFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "set"), 2,
        [jvm, keyType, valueType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = mapStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jobject key = jsiToJobjectMap(env, r, jvm, args[0], keyType);
            jobject value = jsiToJobjectMap(env, r, jvm, args[1], valueType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID putMethod = env->GetMethodID(mapCls, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            jobject old = env->CallObjectMethod(state->globalMapRef_, putMethod, key, value);
            // Kotlin `MutableMap.put`/`set` returns the previous value (`V?`) — return it,
            // not undefined (audit #16). `wrapTyped` consumes the local ref.
            return wrapTyped(r, jvm, old, valueType);
        });
    obj.setProperty(rt, "set", std::move(setFn));

    // `putAll` had no plain implementation before (the morph forwarded its mangled
    // spelling into a silent no-op — a quiet correctness hole); the plain handler
    // forwards the source map into this one through java.util.Map.putAll.
    auto putAllFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "putAll"), 1,
        [jvm, keyType, valueType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = mapStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            auto srcObj = args[0].asObject(r);
            jobject srcMap = nullptr;
            bool borrowed = false;
            if (srcObj.hasNativeState(r)) {
                auto s = std::dynamic_pointer_cast<MapNativeState>(srcObj.getNativeState(r));
                if (s) { srcMap = s->globalMapRef_; borrowed = true; }
            }
            if (!srcMap) srcMap = materializeMap(env, r, jvm, srcObj, keyType, valueType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID putAllMethod = env->GetMethodID(mapCls, "putAll", "(Ljava/util/Map;)V");
            env->CallVoidMethod(state->globalMapRef_, putAllMethod, srcMap);
            if (!borrowed && srcMap) env->DeleteGlobalRef(srcMap);
            env->DeleteLocalRef(mapCls);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "putAll", std::move(putAllFn));

    auto removeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
        [jvm, keyType, valueType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::null();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = mapStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jobject key = jsiToJobjectMap(env, r, jvm, args[0], keyType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID removeMethod = env->GetMethodID(mapCls, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;");
            jobject removed = env->CallObjectMethod(state->globalMapRef_, removeMethod, key);
            // Kotlin `MutableMap.remove` returns the removed value (`V?`), not a boolean
            // (audit #16). `wrapTyped` consumes the local ref.
            return wrapTyped(r, jvm, removed, valueType);
        });
    obj.setProperty(rt, "remove", std::move(removeFn));

    auto clearFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = mapStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID clearMethod = env->GetMethodID(mapCls, "clear", "()V");
            env->CallVoidMethod(state->globalMapRef_, clearMethod);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "clear", std::move(clearFn));

    auto containsKeyFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "containsKey"), 1,
        [jvm, keyType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value(false);
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jobject key = jsiToJobjectMap(env, r, jvm, args[0], keyType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID containsKeyMethod = env->GetMethodID(mapCls, "containsKey", "(Ljava/lang/Object;)Z");
            return jsi::Value(env->CallBooleanMethod(state->globalMapRef_, containsKeyMethod, key) == JNI_TRUE);
        });
    obj.setProperty(rt, "containsKey", std::move(containsKeyFn));

    // `containsValue` had no plain implementation (audit #13): delegate to
    // `java.util.Map.containsValue` over the decoded value.
    auto containsValueFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "containsValue"), 1,
        [jvm, valueType](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value(false);
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jobject value = jsiToJobjectMap(env, r, jvm, args[0], valueType);
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID containsValueMethod = env->GetMethodID(mapCls, "containsValue", "(Ljava/lang/Object;)Z");
            return jsi::Value(env->CallBooleanMethod(state->globalMapRef_, containsValueMethod, value) == JNI_TRUE);
        });
    obj.setProperty(rt, "containsValue", std::move(containsValueFn));

    auto sizeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID sizeMethod = env->GetMethodID(mapCls, "size", "()I");
            return jsi::Value((double)env->CallIntMethod(state->globalMapRef_, sizeMethod));
        });
    obj.setProperty(rt, "sizeImpl", std::move(sizeFn));

    auto isEmptyFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID isEmptyMethod = env->GetMethodID(mapCls, "isEmpty", "()Z");
            return jsi::Value(env->CallBooleanMethod(state->globalMapRef_, isEmptyMethod) == JNI_TRUE);
        });
    obj.setProperty(rt, "isEmpty", std::move(isEmptyFn));

    auto keysFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "keys"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            auto state = mapStateOf(r, thisVal.asObject(r));
            return createView(r, jvm, state->globalMapRef_, 0, state->keyType_);
        });
    obj.setProperty(rt, "keys", std::move(keysFn));

    auto valuesFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "values"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            auto state = mapStateOf(r, thisVal.asObject(r));
            return createView(r, jvm, state->globalMapRef_, 1, state->valueType_);
        });
    obj.setProperty(rt, "values", std::move(valuesFn));

    auto entriesFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "entries"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            auto state = mapStateOf(r, thisVal.asObject(r));
            return createView(r, jvm, state->globalMapRef_, 2, state->keyType_);
        });
    obj.setProperty(rt, "entries", std::move(entriesFn));

    auto toStringFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "toString"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = mapStateOf(r, thisVal.asObject(r));
            jclass mapCls = env->GetObjectClass(state->globalMapRef_);
            jmethodID toStringMethod = env->GetMethodID(mapCls, "toString", "()Ljava/lang/String;");
            jstring jstr = (jstring)env->CallObjectMethod(state->globalMapRef_, toStringMethod);
            if (!jstr) return jsi::String::createFromUtf8(r, "{}");
            const char* cstr = env->GetStringUTFChars(jstr, nullptr);
            auto result = jsi::String::createFromUtf8(r, cstr ? cstr : "");
            if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
            env->DeleteLocalRef(jstr);
            return result;
        });
    obj.setProperty(rt, "toString", std::move(toStringFn));

    // Kotlin/JS typed calls arrive as compile-mangled names; the guest bridge knows
    // the exact spellings for this toolchain (docs/design/collections.md).
    if (stateBacked) wrapStateBackedMapReads(rt, obj, ns);
    installMapMangledForwards(rt, obj, mapMangledNamesOf(rt));

    rt.global().setProperty(rt, "__rdma_map", jsi::Value(rt, obj));
    rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(
        "Object.defineProperty(globalThis.__rdma_map, 'size', {"
        "  get: function() { return this.sizeImpl(); },"
        "  configurable: true"
        "})"
    ), "<mapGetter>");
    rt.global().setProperty(rt, "__rdma_map", jsi::Value::undefined());
}

// A Kotlin/JS typed call site emits the compile-mangled method name (`add_1x7w2j_k$`).
// Alias it directly to the plain host function already installed on `obj` — no wrapper,
// no per-call `getProperty`, no double-hop. See `aliasMangled` in ListHandle.cpp.
static void aliasMangled(jsi::Runtime& rt, jsi::Object& obj, const std::string& mangledName, const std::string& plainName) {
    jsi::Value target = obj.getProperty(rt, plainName.c_str());
    if (!target.isObject() || !target.asObject(rt).isFunction(rt)) {
        throw jsi::JSError(rt, std::string("collection proxy: no implementation for '") + plainName + "'");
    }
    obj.setProperty(rt, mangledName.c_str(), std::move(target));
}

// Kotlin/JS spells stdlib map methods with compile-mangled suffixes; the mapping
// below forwards them to the plain implementations installed by
// `populateMapHandle`. Same table for the morphed-plugin and kernel-created paths
// so they cannot drift: names come either from a live Kotlin map's prototype
// (morphing) or from the guest bridge (`RDMA.mapMethodNames`, compiled with the
// plugin).
static void installMapMangledForwards(jsi::Runtime& rt, jsi::Object& obj, const std::vector<std::string>& names) {
    static const std::pair<const char*, const char*> kTable[] = {
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
    for (const std::string& name : names) {
        for (const auto& entry : kTable) {
            if (name.rfind(entry.first, 0) == 0) {
                aliasMangled(rt, obj, name, entry.second);
                break;
            }
        }
    }
}

// Mangled map method spellings discovered by the guest bridge
// (`RDMA.mapMethodNames`). Cached per runtime once discovered.
static std::vector<std::string> mapMangledNamesOf(jsi::Runtime& rt) {
    static std::vector<std::string> cached;
    static bool computed = false;
    if (computed) return cached;
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return cached;
    auto fn = rdma.asObject(rt).getProperty(rt, "mapMethodNames");
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
        cached = out;
        computed = true;
    }
    return cached;
}

void morphHashMap(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, const std::string& keyType, const std::string& valueType) {
    (void)jvm; (void)keyType; (void)valueType;
    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);

    auto state = mapStateOf(rt, obj);
    auto nameArray = proto.getPropertyNames(rt);
    size_t n = nameArray.size(rt);
    std::vector<std::string> names;
    names.reserve(n);
    for (size_t i = 0; i < n; i++) {
        names.push_back(nameArray.getValueAtIndex(rt, i).getString(rt).utf8(rt));
    }
    for (const std::string& name : names) {
        if (name.rfind("checkIsMutable_", 0) == 0) {
            state->checkIsMutable_ = name;
        }
    }
    // Same mangled-alias table as kernel-created proxies (see
    // installMapMangledForwards) — the two paths cannot drift.
    installMapMangledForwards(rt, obj, names);
}

jsi::Object createMapHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    auto mapObj = jsi::Object(rt);
    populateMapHandle(rt, jvm, mapObj, globalMapRef, keyType, valueType, stateBacked);
    return mapObj;
}

// Queries the plugin-side classifier (`RDMA.mapKind`): 0 = LinkedHashMap,
// 1 = HashMap, 2 = emptyMap, 3 = unsupported. Returns -1 when not installed.
static int mapKindOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    auto fn = rdma.asObject(rt).getProperty(rt, "mapKind");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return -1;
    auto res = fn.asObject(rt).asFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

// Extracts entries of a plugin-side Kotlin/JS Map into a JS array of [key, value].
static jsi::Array mapToEntries(jsi::Runtime& rt, const jsi::Object& obj) {
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return jsi::Array(rt, 0);
    auto fn = rdma.asObject(rt).getProperty(rt, "mapToEntries");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return jsi::Array(rt, 0);
    auto res = fn.asObject(rt).asFunction(rt).call(rt, jsi::Value(rt, obj));
    if (res.isObject() && res.asObject(rt).isArray(rt)) return res.asObject(rt).asArray(rt);
    return jsi::Array(rt, 0);
}

jobject materializeMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType) {
    int kind = mapKindOf(rt, jsi::Value(rt, jsObj));
    if (kind == 3) {
        throw jsi::JSError(rt, "unsupported Map implementation crossing the runtime boundary; only LinkedHashMap, HashMap and EmptyMap are supported");
    }
    if (kind == 2) {
        jclass collectionsCls = env->FindClass("java/util/Collections");
        jmethodID emptyMapMethod = env->GetStaticMethodID(collectionsCls, "emptyMap", "()Ljava/util/Map;");
        jobject empty = env->CallStaticObjectMethod(collectionsCls, emptyMapMethod);
        return env->NewGlobalRef(empty);
    }
    // Already a map handle we produced earlier.
    if (jsObj.hasNativeState(rt)) {
        auto ns = std::dynamic_pointer_cast<MapNativeState>(jsObj.getNativeState(rt));
        if (ns) return ns->globalMapRef_;
    }

    jclass lhmCls = env->FindClass("java/util/LinkedHashMap");
    jmethodID ctor = env->GetMethodID(lhmCls, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(lhmCls, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject localMap = env->NewObject(lhmCls, ctor);

    auto entries = mapToEntries(rt, jsObj);
    size_t n = entries.size(rt);
    for (size_t i = 0; i < n; i++) {
        auto pair = entries.getValueAtIndex(rt, i);
        if (!pair.isObject() || !pair.asObject(rt).isArray(rt)) continue;
        auto arr = pair.asObject(rt).asArray(rt);
        if (arr.size(rt) < 2) continue;
        jobject key = jsiToJobjectMap(env, rt, jvm, arr.getValueAtIndex(rt, 0), keyType);
        jobject value = jsiToJobjectMap(env, rt, jvm, arr.getValueAtIndex(rt, 1), valueType);
        env->CallObjectMethod(localMap, putMethod, key, value);
    }

    jobject globalMap = env->NewGlobalRef(localMap);
    env->DeleteLocalRef(localMap);

    populateMapHandle(rt, jvm, jsObj, globalMap, keyType, valueType);
    morphHashMap(rt, jvm, jsObj, keyType, valueType);

    return globalMap;
}

} // namespace rdma
} // namespace facebook
