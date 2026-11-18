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

// JNI map proxies and their keys/values/entries views on the shared-prototype object
// model (docs/design/runtime_rework.md §3.5), plus the JNI implementation of
// `rdmaPlatformProtoMethods`.

#include "MapHandle.h"
#include "ListHandle.h"
#include "RdmaCompose.h"
#include "RdmaJniUtil.h"
#include "RdmaProxyCore.h"

#include <jni.h>

namespace facebook {
namespace rdma {

namespace {

struct MapJni {
    jclass linkedHashMapClass = nullptr;
    jmethodID linkedHashMapCtor = nullptr;
    jclass collectionsClass = nullptr;
    jmethodID emptyMap = nullptr;
    jmethodID size, get, put, remove, removeKV, clear, containsKey, containsValue, isEmpty, putAll,
        keySet, values, entrySet;
    // java.util.Collection (views)
    jmethodID collToArray, collContains, collRemove, collContainsAll;
    jmethodID toString, equals, hashCode;
};

MapJni makeMapJni(JNIEnv* env) {
    MapJni j{};
    jclass map = env->FindClass("java/util/Map");
    jclass coll = env->FindClass("java/util/Collection");
    jclass object = env->FindClass("java/lang/Object");
    jclass lhm = env->FindClass("java/util/LinkedHashMap");
    jclass collections = env->FindClass("java/util/Collections");
    j.linkedHashMapClass = (jclass)env->NewGlobalRef(lhm);
    j.linkedHashMapCtor = env->GetMethodID(lhm, "<init>", "()V");
    j.collectionsClass = (jclass)env->NewGlobalRef(collections);
    j.emptyMap = env->GetStaticMethodID(collections, "emptyMap", "()Ljava/util/Map;");
    j.size = env->GetMethodID(map, "size", "()I");
    j.get = env->GetMethodID(map, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
    j.put = env->GetMethodID(map, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    j.remove = env->GetMethodID(map, "remove", "(Ljava/lang/Object;)Ljava/lang/Object;");
    j.removeKV = env->GetMethodID(map, "remove", "(Ljava/lang/Object;Ljava/lang/Object;)Z");
    j.clear = env->GetMethodID(map, "clear", "()V");
    j.containsKey = env->GetMethodID(map, "containsKey", "(Ljava/lang/Object;)Z");
    j.containsValue = env->GetMethodID(map, "containsValue", "(Ljava/lang/Object;)Z");
    j.isEmpty = env->GetMethodID(map, "isEmpty", "()Z");
    j.putAll = env->GetMethodID(map, "putAll", "(Ljava/util/Map;)V");
    j.keySet = env->GetMethodID(map, "keySet", "()Ljava/util/Set;");
    j.values = env->GetMethodID(map, "values", "()Ljava/util/Collection;");
    j.entrySet = env->GetMethodID(map, "entrySet", "()Ljava/util/Set;");
    j.collToArray = env->GetMethodID(coll, "toArray", "()[Ljava/lang/Object;");
    j.collContains = env->GetMethodID(coll, "contains", "(Ljava/lang/Object;)Z");
    j.collRemove = env->GetMethodID(coll, "remove", "(Ljava/lang/Object;)Z");
    j.collContainsAll = env->GetMethodID(coll, "containsAll", "(Ljava/util/Collection;)Z");
    j.toString = env->GetMethodID(object, "toString", "()Ljava/lang/String;");
    j.equals = env->GetMethodID(object, "equals", "(Ljava/lang/Object;)Z");
    j.hashCode = env->GetMethodID(object, "hashCode", "()I");
    env->DeleteLocalRef(map);
    env->DeleteLocalRef(coll);
    env->DeleteLocalRef(object);
    env->DeleteLocalRef(lhm);
    env->DeleteLocalRef(collections);
    return j;
}

const MapJni& mapJni(JNIEnv* env) {
    static const MapJni j = makeMapJni(env); // thread-safe one-time init
    return j;
}

jsi::String jstringToJs(JNIEnv* env, jsi::Runtime& r, jstring s, const char* fallback) {
    if (!s) return jsi::String::createFromUtf8(r, fallback);
    const char* c = env->GetStringUTFChars(s, nullptr);
    jsi::String out = jsi::String::createFromUtf8(r, c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

struct MapCall {
    JNIEnv* env;
    JniLocalFrame frame;
    jsi::Object self;
    std::shared_ptr<MapNativeState> st;
    const MapJni& j;

    MapCall(jsi::Runtime& r, const jsi::Value& thisVal)
        : env(getEnv(g_composeCache.jvm)),
          frame(env),
          self(thisVal.getObject(r)),
          st(rdmaMapStateOf(r, self)),
          j(mapJni(env)) {
        if (!st) throw jsi::JSError(r, "receiver is not a native map proxy");
    }
    jobject ref() const { return st->globalMapRef_; }
    JavaVM* jvm() const { return st->jvm_; }
};

struct ViewCall {
    JNIEnv* env;
    JniLocalFrame frame;
    jsi::Object self;
    std::shared_ptr<MapViewNativeState> st;
    const MapJni& j;

    ViewCall(jsi::Runtime& r, const jsi::Value& thisVal)
        : env(getEnv(g_composeCache.jvm)),
          frame(env),
          self(thisVal.getObject(r)),
          st(rdmaStateAs<MapViewNativeState>(r, self, RdmaStateKind::MapView)),
          j(mapJni(env)) {
        if (!st) throw jsi::JSError(r, "receiver is not a native map view");
    }
    jobject map() const { return st->globalMapRef_; }
    JavaVM* jvm() const { return st->jvm_; }
    // The java.util view (local reference): keySet / values / entrySet.
    jobject javaView(jsi::Runtime& r) {
        jmethodID m = st->mode_ == MapViewNativeState::Keys ? j.keySet
            : st->mode_ == MapViewNativeState::Values ? j.values : j.entrySet;
        jobject v = env->CallObjectMethod(map(), m);
        checkAndThrowJs(env, r);
        return v;
    }
};

void touchCompositionRead(JavaVM* jvm, jobject mapRef) {
    if (!g_inComposition) return;
    rdmaCallUi([jvm, mapRef]() -> RdmaResult {
        JNIEnv* e = getEnv(jvm);
        if (!e) return RdmaResult::undefined();
        e->CallIntMethod(mapRef, mapJni(e).size);
        RdmaResult err;
        if (captureJniError(e, err)) return err;
        return RdmaResult::undefined();
    });
}

int mapKindOf(jsi::Runtime& rt, const jsi::Value& v) {
    jsi::Value rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    jsi::Value fn = rdma.getObject(rt).getProperty(rt, "mapKind");
    if (!fn.isObject() || !fn.getObject(rt).isFunction(rt)) return -1;
    jsi::Value res = fn.getObject(rt).getFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

// Entries of a plugin-side Kotlin map as `[[k, v], …]` (guest bridge `RDMA.mapToEntries`).
jsi::Array pluginEntriesOf(jsi::Runtime& rt, const jsi::Object& obj) {
    jsi::Value rdma = rt.global().getProperty(rt, "RDMA");
    if (rdma.isObject()) {
        jsi::Value fn = rdma.getObject(rt).getProperty(rt, "mapToEntries");
        if (fn.isObject() && fn.getObject(rt).isFunction(rt)) {
            jsi::Value res = fn.getObject(rt).getFunction(rt).call(rt, jsi::Value(rt, obj));
            if (res.isObject() && res.getObject(rt).isArray(rt)) return res.getObject(rt).getArray(rt);
        }
    }
    throw jsi::JSError(rt, "map crossing the runtime boundary: the plugin bridge has no mapToEntries");
}

jobject newJniMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Array& entries, const std::string& keyType, const std::string& valueType) {
    const MapJni& j = mapJni(env);
    jobject map = env->NewObject(j.linkedHashMapClass, j.linkedHashMapCtor);
    size_t n = entries.size(rt);
    for (size_t i = 0; i < n; i++) {
        jsi::Value pair = entries.getValueAtIndex(rt, i);
        if (!pair.isObject() || !pair.getObject(rt).isArray(rt)) continue;
        jsi::Array kv = pair.getObject(rt).getArray(rt);
        if (kv.size(rt) < 2) continue;
        jobject k = rdmaJsToJniTyped(env, rt, jvm, kv.getValueAtIndex(rt, 0), keyType);
        jobject v = rdmaJsToJniTyped(env, rt, jvm, kv.getValueAtIndex(rt, 1), valueType);
        jobject old = env->CallObjectMethod(map, j.put, k, v);
        if (old) env->DeleteLocalRef(old);
        if (k) env->DeleteLocalRef(k);
        if (v) env->DeleteLocalRef(v);
        checkAndThrowJs(env, rt);
    }
    return map;
}

// Converts a java Object[] (local) into a JS array of typed values.
jsi::Array jniArrayToJs(JNIEnv* env, jsi::Runtime& r, JavaVM* jvm, jobjectArray arr, const std::string& desc) {
    jsize n = arr ? env->GetArrayLength(arr) : 0;
    jsi::Array out(r, (size_t)n);
    for (jsize i = 0; i < n; i++) {
        out.setValueAtIndex(r, (size_t)i, rdmaJniToJsTyped(r, jvm, env->GetObjectArrayElement(arr, i), desc));
    }
    return out;
}

// One snapshot of the map as `[k0, v0, k1, v1, …]`: two JNI calls for the whole map
// (keySet and values iterate in the same order on an unmodified map).
jsi::Array flatSnapshot(JNIEnv* env, jsi::Runtime& r, JavaVM* jvm, jobject mapRef, const std::string& keyType, const std::string& valueType) {
    const MapJni& j = mapJni(env);
    jobject keys = env->CallObjectMethod(mapRef, j.keySet);
    checkAndThrowJs(env, r);
    auto karr = (jobjectArray)env->CallObjectMethod(keys, j.collToArray);
    checkAndThrowJs(env, r);
    jobject values = env->CallObjectMethod(mapRef, j.values);
    checkAndThrowJs(env, r);
    auto varr = (jobjectArray)env->CallObjectMethod(values, j.collToArray);
    checkAndThrowJs(env, r);
    jsize n = karr ? env->GetArrayLength(karr) : 0;
    jsize nv = varr ? env->GetArrayLength(varr) : 0;
    if (nv < n) n = nv;
    jsi::Array out(r, (size_t)n * 2);
    for (jsize i = 0; i < n; i++) {
        out.setValueAtIndex(r, (size_t)i * 2, rdmaJniToJsTyped(r, jvm, env->GetObjectArrayElement(karr, i), keyType));
        out.setValueAtIndex(r, (size_t)i * 2 + 1, rdmaJniToJsTyped(r, jvm, env->GetObjectArrayElement(varr, i), valueType));
    }
    return out;
}

jsi::Object newView(jsi::Runtime& r, MapCall& c, MapViewNativeState::Mode mode) {
    auto state = std::make_shared<MapViewNativeState>(c.jvm(), c.env->NewGlobalRef(c.ref()), mode, c.st->keyType_, c.st->valueType_);
    state->stateBacked_ = c.st->stateBacked_;
    RdmaProtoKind kind = mode == MapViewNativeState::Keys ? RdmaProtoKind::KeysView
        : mode == MapViewNativeState::Values ? RdmaProtoKind::ValuesView : RdmaProtoKind::EntriesView;
    return rdmaNewProxy(r, kind, std::move(state));
}

std::vector<RdmaProtoMethod> mapMethods() {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"get", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.st->keyType_);
        jobject v = c.env->CallObjectMethod(c.ref(), c.j.get, k);
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), v, c.st->valueType_);
    }});
    // `set` is the plain name; the stdlib `put` aliases here. Returns the previous value.
    m.push_back({"set", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        if (n < 2) throw jsi::JSError(r, "put: key and value expected");
        rdmaCheckMapMutable(r, c.self);
        jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), a[0], c.st->keyType_);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), a[1], c.st->valueType_);
        jobject old = c.env->CallObjectMethod(c.ref(), c.j.put, k, v);
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), old, c.st->valueType_);
    }});
    m.push_back({"putAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        if (n < 1 || !a[0].isObject()) throw jsi::JSError(r, "putAll: map expected");
        rdmaCheckMapMutable(r, c.self);
        jobject src = rdmaMapJObjectOf(r, a[0]);
        jobject tmp = src ? c.env->NewLocalRef(src)
            : newJniMap(c.env, r, c.jvm(), pluginEntriesOf(r, a[0].getObject(r)), c.st->keyType_, c.st->valueType_);
        c.env->CallVoidMethod(c.ref(), c.j.putAll, tmp);
        checkAndThrowJs(c.env, r);
        return jsi::Value::undefined();
    }});
    // Kotlin `MutableMap.remove(key)` returns the removed value.
    m.push_back({"remove", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        rdmaCheckMapMutable(r, c.self);
        jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.st->keyType_);
        jobject old = c.env->CallObjectMethod(c.ref(), c.j.remove, k);
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), old, c.st->valueType_);
    }});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        rdmaCheckMapMutable(r, c.self);
        c.env->CallVoidMethod(c.ref(), c.j.clear);
        checkAndThrowJs(c.env, r);
        return jsi::Value::undefined();
    }});
    m.push_back({"containsKey", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.st->keyType_);
        jboolean has = c.env->CallBooleanMethod(c.ref(), c.j.containsKey, k);
        checkAndThrowJs(c.env, r);
        return jsi::Value(has == JNI_TRUE);
    }});
    m.push_back({"containsValue", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.st->valueType_);
        jboolean has = c.env->CallBooleanMethod(c.ref(), c.j.containsValue, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value(has == JNI_TRUE);
    }});
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        jint size = c.env->CallIntMethod(c.ref(), c.j.size);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)size);
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        jboolean empty = c.env->CallBooleanMethod(c.ref(), c.j.isEmpty);
        checkAndThrowJs(c.env, r);
        return jsi::Value(empty == JNI_TRUE);
    }});
    m.push_back({"keys", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        return newView(r, c, MapViewNativeState::Keys);
    }});
    m.push_back({"values", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        return newView(r, c, MapViewNativeState::Values);
    }});
    m.push_back({"entries", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        return newView(r, c, MapViewNativeState::Entries);
    }});
    m.push_back({"checkIsMutable", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        rdmaCheckMapMutable(r, t.getObject(r));
        return jsi::Value::undefined();
    }});
    m.push_back({"equals", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        MapCall c(r, t);
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        jobject other = rdmaMapJObjectOf(r, a[0]);
        if (!other) return jsi::Value(rdmaInheritedEquals(r, RdmaProtoKind::Map, c.self, a[0]));
        jboolean eq = c.env->CallBooleanMethod(c.ref(), c.j.equals, other);
        checkAndThrowJs(c.env, r);
        return jsi::Value(eq == JNI_TRUE);
    }});
    m.push_back({"hashCode", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        jint h = c.env->CallIntMethod(c.ref(), c.j.hashCode);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)h);
    }});
    m.push_back({"toString", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        MapCall c(r, t);
        auto s = (jstring)c.env->CallObjectMethod(c.ref(), c.j.toString);
        checkAndThrowJs(c.env, r);
        return jstringToJs(c.env, r, s, "{}");
    }});
    return m;
}

// The JNI value used to test/remove an element of a view: a key, a value, or (for
// entries) the entry's key and value.
std::vector<RdmaProtoMethod> viewMethods(MapViewNativeState::Mode mode) {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ViewCall c(r, t);
        jint size = c.env->CallIntMethod(c.map(), c.j.size);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)size);
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ViewCall c(r, t);
        jboolean empty = c.env->CallBooleanMethod(c.map(), c.j.isEmpty);
        checkAndThrowJs(c.env, r);
        return jsi::Value(empty == JNI_TRUE);
    }});
    m.push_back({"iterator", 0, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ViewCall c(r, t);
        if (mode == MapViewNativeState::Entries) {
            jsi::Array flat = flatSnapshot(c.env, r, c.jvm(), c.map(), c.st->keyType_, c.st->valueType_);
            // Entries write through the owning map: rebuild a map proxy over the same native map.
            auto mapState = std::make_shared<MapNativeState>(c.jvm(), c.env->NewGlobalRef(c.map()), c.st->keyType_, c.st->valueType_);
            mapState->stateBacked_ = c.st->stateBacked_;
            jsi::Object owner = rdmaNewProxy(r, c.st->stateBacked_ ? RdmaProtoKind::StateMap : RdmaProtoKind::Map, std::move(mapState));
            return rdmaSnapshotIterator(r, RdmaIterKind::Entries, c.self, rdmaMakeEntries(r, owner, flat));
        }
        jobject view = c.javaView(r);
        auto arr = (jobjectArray)c.env->CallObjectMethod(view, c.j.collToArray);
        checkAndThrowJs(c.env, r);
        const std::string& desc = mode == MapViewNativeState::Keys ? c.st->keyType_ : c.st->valueType_;
        return rdmaSnapshotIterator(r, mode == MapViewNativeState::Keys ? RdmaIterKind::Keys : RdmaIterKind::Values,
            c.self, jniArrayToJs(c.env, r, c.jvm(), arr, desc));
    }});
    m.push_back({"contains", 1, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ViewCall c(r, t);
        jsi::Value x = n > 0 ? jsi::Value(r, a[0]) : jsi::Value::undefined();
        if (mode == MapViewNativeState::Keys) {
            jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), x, c.st->keyType_);
            jboolean has = c.env->CallBooleanMethod(c.map(), c.j.containsKey, k);
            checkAndThrowJs(c.env, r);
            return jsi::Value(has == JNI_TRUE);
        }
        if (mode == MapViewNativeState::Values) {
            jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), x, c.st->valueType_);
            jboolean has = c.env->CallBooleanMethod(c.map(), c.j.containsValue, v);
            checkAndThrowJs(c.env, r);
            return jsi::Value(has == JNI_TRUE);
        }
        // Entry: present when the map maps its key to an equal value.
        if (!x.isObject()) return jsi::Value(false);
        jsi::Object e = x.getObject(r);
        jsi::Value keyOf = e.getProperty(r, "$keyOf");
        if (!keyOf.isObject()) return jsi::Value(false);
        jsi::Value key = keyOf.getObject(r).getFunction(r).callWithThis(r, e, nullptr, 0);
        jsi::Value value = e.getPropertyAsFunction(r, "$valueOf").callWithThis(r, e, nullptr, 0);
        jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), key, c.st->keyType_);
        jobject cur = c.env->CallObjectMethod(c.map(), c.j.get, k);
        checkAndThrowJs(c.env, r);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), value, c.st->valueType_);
        bool eq = cur ? (c.env->CallBooleanMethod(cur, c.j.equals, v) == JNI_TRUE) : (v == nullptr && c.env->CallBooleanMethod(c.map(), c.j.containsKey, k) == JNI_TRUE);
        checkAndThrowJs(c.env, r);
        return jsi::Value(eq);
    }});
    m.push_back({"remove", 1, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ViewCall c(r, t);
        jsi::Value x = n > 0 ? jsi::Value(r, a[0]) : jsi::Value::undefined();
        if (mode == MapViewNativeState::Entries) {
            if (!x.isObject()) return jsi::Value(false);
            jsi::Object e = x.getObject(r);
            jsi::Value keyOf = e.getProperty(r, "$keyOf");
            if (!keyOf.isObject()) return jsi::Value(false);
            jsi::Value key = keyOf.getObject(r).getFunction(r).callWithThis(r, e, nullptr, 0);
            jsi::Value value = e.getPropertyAsFunction(r, "$valueOf").callWithThis(r, e, nullptr, 0);
            jobject k = rdmaJsToJniTyped(c.env, r, c.jvm(), key, c.st->keyType_);
            jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), value, c.st->valueType_);
            jboolean removed = c.env->CallBooleanMethod(c.map(), c.j.removeKV, k, v);
            checkAndThrowJs(c.env, r);
            return jsi::Value(removed == JNI_TRUE);
        }
        const std::string& desc = mode == MapViewNativeState::Keys ? c.st->keyType_ : c.st->valueType_;
        jobject el = rdmaJsToJniTyped(c.env, r, c.jvm(), x, desc);
        jobject view = c.javaView(r);
        jboolean removed = c.env->CallBooleanMethod(view, c.j.collRemove, el);
        checkAndThrowJs(c.env, r);
        return jsi::Value(removed == JNI_TRUE);
    }});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ViewCall c(r, t);
        c.env->CallVoidMethod(c.map(), c.j.clear);
        checkAndThrowJs(c.env, r);
        return jsi::Value::undefined();
    }});
    m.push_back({"containsAll", 1, [mode](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        // Generic over `contains`: one check per element of the argument.
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        jsi::Object self = t.getObject(r);
        jsi::Function contains = self.getPropertyAsFunction(r, "contains");
        jsi::Object arg = a[0].getObject(r);
        jsi::Value toArray = arg.getProperty(r, "toArray");
        if (!toArray.isObject()) return jsi::Value(false);
        jsi::Array elems = toArray.getObject(r).getFunction(r).callWithThis(r, arg, nullptr, 0).getObject(r).getArray(r);
        for (size_t i = 0; i < elems.size(r); i++) {
            jsi::Value hit = contains.callWithThis(r, self, elems.getValueAtIndex(r, i));
            if (!hit.isBool() || !hit.getBool()) return jsi::Value(false);
        }
        (void)mode;
        return jsi::Value(true);
    }});
    // Kotlin map views do not support adding.
    auto unsupported = [](jsi::Runtime& r, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
        throw jsi::JSError(r, "UnsupportedOperationException: a map view does not support adding elements");
    };
    m.push_back({"add", 1, unsupported});
    m.push_back({"addAll", 1, unsupported});
    m.push_back({"checkIsMutable", 0, [](jsi::Runtime&, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
        return jsi::Value::undefined();
    }});
    m.push_back({"toString", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ViewCall c(r, t);
        jobject view = c.javaView(r);
        auto s = (jstring)c.env->CallObjectMethod(view, c.j.toString);
        checkAndThrowJs(c.env, r);
        return jstringToJs(c.env, r, s, "[]");
    }});
    return m;
}

std::vector<RdmaProtoMethod> stateBackedMap(std::vector<RdmaProtoMethod> methods) {
    for (auto& m : methods) {
        jsi::HostFunctionType inner = std::move(m.fn);
        m.fn = [inner](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
            if (g_inComposition) {
                if (auto st = rdmaMapStateOf(r, t)) touchCompositionRead(st->jvm_, st->globalMapRef_);
            }
            return inner(r, t, a, n);
        };
    }
    return methods;
}

} // namespace

std::vector<RdmaProtoMethod> rdmaPlatformProtoMethods(jsi::Runtime&, RdmaProtoKind kind) {
    switch (kind) {
        case RdmaProtoKind::List: return rdmaJniListProtoMethods(false);
        case RdmaProtoKind::StateList: return rdmaJniListProtoMethods(true);
        case RdmaProtoKind::Map: return mapMethods();
        case RdmaProtoKind::StateMap: return stateBackedMap(mapMethods());
        case RdmaProtoKind::KeysView: return viewMethods(MapViewNativeState::Keys);
        case RdmaProtoKind::ValuesView: return viewMethods(MapViewNativeState::Values);
        case RdmaProtoKind::EntriesView: return viewMethods(MapViewNativeState::Entries);
        default: return {};
    }
}

void populateMapHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    auto state = std::make_shared<MapNativeState>(jvm, globalMapRef, keyType, valueType);
    state->stateBacked_ = stateBacked;
    rdmaAdoptProxy(rt, obj, stateBacked ? RdmaProtoKind::StateMap : RdmaProtoKind::Map, std::move(state));
}

void morphHashMap(jsi::Runtime&, JavaVM*, jsi::Object&, const std::string&, const std::string&) {
    // The prototype swap in populateMapHandle already shadows every storage method.
}

jsi::Object createMapHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalMapRef, const std::string& keyType, const std::string& valueType, bool stateBacked) {
    auto state = std::make_shared<MapNativeState>(jvm, globalMapRef, keyType, valueType);
    state->stateBacked_ = stateBacked;
    return rdmaNewProxy(rt, stateBacked ? RdmaProtoKind::StateMap : RdmaProtoKind::Map, std::move(state));
}

jobject materializeMap(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& keyType, const std::string& valueType) {
    // Already a proxy (kernel map, or a plugin map that crossed before).
    if (auto st = rdmaMapStateOf(rt, jsObj)) return env->NewLocalRef(st->globalMapRef_);

    int kind = mapKindOf(rt, jsi::Value(rt, jsObj));
    if (kind == 3) {
        throw jsi::JSError(rt, "unsupported Map implementation crossing the runtime boundary; only LinkedHashMap, HashMap and EmptyMap are supported");
    }
    if (kind == 2) {
        // emptyMap: the immutable native empty map; the JS singleton is not adopted.
        const MapJni& j = mapJni(env);
        return env->CallStaticObjectMethod(j.collectionsClass, j.emptyMap);
    }
    jobject map = newJniMap(env, rt, jvm, pluginEntriesOf(rt, jsObj), keyType, valueType);
    // The proxy owns its own global reference; the caller owns the returned local.
    populateMapHandle(rt, jvm, jsObj, env->NewGlobalRef(map), keyType, valueType);
    return map;
}

} // namespace rdma
} // namespace facebook
