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

// JNI list proxies on the shared-prototype object model
// (docs/design/runtime_rework.md §3.4). The host functions below are installed ONCE
// per runtime on the list prototype by RdmaProxyCore; every call resolves its state
// from `this`.

#include "ListHandle.h"
#include "MapHandle.h"
#include "RdmaCompose.h"
#include "RdmaJniUtil.h"
#include "RdmaProxyCore.h"

#include <jni.h>

namespace facebook {
namespace rdma {

namespace {

bool isListDesc(const std::string& d) { return d.rfind("list:", 0) == 0; }
bool isMapDesc(const std::string& d) { return d.rfind("map:", 0) == 0; }

// Parses a `map:<key>:<value>` body (after `map:`) into key/value descriptors.
std::pair<std::string, std::string> parseMapBody(const std::string& s) {
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

// `java.util.List` / `java.util.Collections` members, resolved once (boot classes are
// visible on every thread). Interface method ids work for every implementation.
struct ListJni {
    jclass arrayListClass = nullptr;
    jmethodID arrayListCtor = nullptr;
    jclass collectionsClass = nullptr;
    jmethodID emptyList = nullptr;
    jmethodID size, get, set, add, addAt, removeAt, removeObj, subList, clear, isEmpty, contains,
        indexOf, lastIndexOf, addAll, addAllAt, removeAll, retainAll, containsAll, toArray,
        toString, equals, hashCode;
};

ListJni makeListJni(JNIEnv* env) {
    ListJni j{};
    jclass list = env->FindClass("java/util/List");
    jclass object = env->FindClass("java/lang/Object");
    jclass arrayList = env->FindClass("java/util/ArrayList");
    jclass collections = env->FindClass("java/util/Collections");
    j.arrayListClass = (jclass)env->NewGlobalRef(arrayList);
    j.arrayListCtor = env->GetMethodID(arrayList, "<init>", "(I)V");
    j.collectionsClass = (jclass)env->NewGlobalRef(collections);
    j.emptyList = env->GetStaticMethodID(collections, "emptyList", "()Ljava/util/List;");
    j.size = env->GetMethodID(list, "size", "()I");
    j.get = env->GetMethodID(list, "get", "(I)Ljava/lang/Object;");
    j.set = env->GetMethodID(list, "set", "(ILjava/lang/Object;)Ljava/lang/Object;");
    j.add = env->GetMethodID(list, "add", "(Ljava/lang/Object;)Z");
    j.addAt = env->GetMethodID(list, "add", "(ILjava/lang/Object;)V");
    j.removeAt = env->GetMethodID(list, "remove", "(I)Ljava/lang/Object;");
    j.removeObj = env->GetMethodID(list, "remove", "(Ljava/lang/Object;)Z");
    j.subList = env->GetMethodID(list, "subList", "(II)Ljava/util/List;");
    j.clear = env->GetMethodID(list, "clear", "()V");
    j.isEmpty = env->GetMethodID(list, "isEmpty", "()Z");
    j.contains = env->GetMethodID(list, "contains", "(Ljava/lang/Object;)Z");
    j.indexOf = env->GetMethodID(list, "indexOf", "(Ljava/lang/Object;)I");
    j.lastIndexOf = env->GetMethodID(list, "lastIndexOf", "(Ljava/lang/Object;)I");
    j.addAll = env->GetMethodID(list, "addAll", "(Ljava/util/Collection;)Z");
    j.addAllAt = env->GetMethodID(list, "addAll", "(ILjava/util/Collection;)Z");
    j.removeAll = env->GetMethodID(list, "removeAll", "(Ljava/util/Collection;)Z");
    j.retainAll = env->GetMethodID(list, "retainAll", "(Ljava/util/Collection;)Z");
    j.containsAll = env->GetMethodID(list, "containsAll", "(Ljava/util/Collection;)Z");
    j.toArray = env->GetMethodID(list, "toArray", "()[Ljava/lang/Object;");
    j.toString = env->GetMethodID(object, "toString", "()Ljava/lang/String;");
    j.equals = env->GetMethodID(object, "equals", "(Ljava/lang/Object;)Z");
    j.hashCode = env->GetMethodID(object, "hashCode", "()I");
    env->DeleteLocalRef(list);
    env->DeleteLocalRef(object);
    env->DeleteLocalRef(arrayList);
    env->DeleteLocalRef(collections);
    return j;
}

const ListJni& listJni(JNIEnv* env) {
    static const ListJni j = makeListJni(env); // thread-safe one-time init
    return j;
}

// Resolves `this`, opens a JNI local frame for the call and fails loudly when the
// receiver is not a native list proxy.
struct ListCall {
    JNIEnv* env;
    JniLocalFrame frame;
    jsi::Object self;
    std::shared_ptr<ListNativeState> st;
    const ListJni& j;

    ListCall(jsi::Runtime& r, const jsi::Value& thisVal)
        : env(getEnv(g_composeCache.jvm)),
          frame(env),
          self(thisVal.getObject(r)),
          st(rdmaListStateOf(r, self)),
          j(listJni(env)) {
        if (!st) throw jsi::JSError(r, "receiver is not a native list proxy");
    }

    jobject ref() const { return st->globalListRef_; }
    JavaVM* jvm() const { return st->jvm_; }
    const std::string& elem() const { return st->elementType_; }
};

jsi::String jstringToJs(JNIEnv* env, jsi::Runtime& r, jstring s, const char* fallback) {
    if (!s) return jsi::String::createFromUtf8(r, fallback);
    // UTF-16: JNI's modified UTF-8 would mangle supplementary characters and U+0000.
    jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    jsi::String out = jsi::String::createFromUtf16(r, reinterpret_cast<const char16_t*>(chars), (size_t)len);
    env->ReleaseStringChars(s, chars);
    return out;
}

int listKindOf(jsi::Runtime& rt, const jsi::Value& v) {
    jsi::Value rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    jsi::Value fn = rdma.getObject(rt).getProperty(rt, "listKind");
    if (!fn.isObject() || !fn.getObject(rt).isFunction(rt)) return -1;
    jsi::Value res = fn.getObject(rt).getFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

// The elements of a plugin-side Kotlin collection (or a JS array) as a JS array,
// read through the public `toArray()` — never through stdlib internals.
jsi::Array jsElementsOf(jsi::Runtime& rt, const jsi::Object& obj) {
    if (obj.isArray(rt)) return obj.getArray(rt);
    jsi::Value toArray = obj.getProperty(rt, "toArray");
    if (toArray.isObject() && toArray.getObject(rt).isFunction(rt)) {
        jsi::Value arr = toArray.getObject(rt).getFunction(rt).callWithThis(rt, obj, nullptr, 0);
        if (arr.isObject() && arr.getObject(rt).isArray(rt)) return arr.getObject(rt).getArray(rt);
    }
    throw jsi::JSError(rt, "collection crossing the runtime boundary has no toArray()");
}

jobject newJniArrayList(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Array& elems, const std::string& elemDesc) {
    const ListJni& j = listJni(env);
    size_t n = elems.size(rt);
    jobject list = env->NewObject(j.arrayListClass, j.arrayListCtor, (jint)n);
    for (size_t i = 0; i < n; i++) {
        jobject e = rdmaJsToJniTyped(env, rt, jvm, elems.getValueAtIndex(rt, i), elemDesc);
        env->CallBooleanMethod(list, j.add, e);
        if (e) env->DeleteLocalRef(e);
        checkAndThrowJs(env, rt);
    }
    return list;
}

// Registers the composition read of a state-backed list (on the UI thread, inside
// the active snapshot) so mutations through the proxy schedule a recomposition.
void touchCompositionRead(const std::shared_ptr<ListNativeState>& state) {
    if (!g_inComposition) return;
    rdmaCallUi([state]() -> RdmaResult {
        JNIEnv* e = getEnv(state->jvm_);
        if (!e) return RdmaResult::undefined();
        e->CallIntMethod(state->globalListRef_, listJni(e).size);
        RdmaResult err;
        if (captureJniError(e, err)) return err;
        return RdmaResult::undefined();
    });
}

jsi::Array snapshotOf(jsi::Runtime& r, ListCall& c) {
    auto arr = (jobjectArray)c.env->CallObjectMethod(c.ref(), c.j.toArray);
    checkAndThrowJs(c.env, r);
    jsize n = arr ? c.env->GetArrayLength(arr) : 0;
    jsi::Array out(r, (size_t)n);
    for (jsize i = 0; i < n; i++) {
        jobject e = c.env->GetObjectArrayElement(arr, i);
        out.setValueAtIndex(r, (size_t)i, rdmaJniToJsTyped(r, c.jvm(), e, c.elem()));
    }
    return out;
}

using HostFn = jsi::HostFunctionType;

std::vector<RdmaProtoMethod> listMethods() {
    std::vector<RdmaProtoMethod> m;
    m.push_back({"get", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1 || !a[0].isNumber()) throw jsi::JSError(r, "get: index expected");
        jobject e = c.env->CallObjectMethod(c.ref(), c.j.get, (jint)a[0].getNumber());
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), e, c.elem());
    }});
    m.push_back({"set", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 2 || !a[0].isNumber()) throw jsi::JSError(r, "set: index and value expected");
        rdmaCheckListMutable(r, c.self);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), a[1], c.elem());
        jobject old = c.env->CallObjectMethod(c.ref(), c.j.set, (jint)a[0].getNumber(), v);
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), old, c.elem());
    }});
    // add(element): Boolean; add(index, element): Unit — dispatched by arity, as the
    // two stdlib spellings both alias here.
    m.push_back({"add", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        rdmaCheckListMutable(r, c.self);
        if (n >= 2 && a[0].isNumber() && !a[1].isUndefined()) {
            jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), a[1], c.elem());
            c.env->CallVoidMethod(c.ref(), c.j.addAt, (jint)a[0].getNumber(), v);
            checkAndThrowJs(c.env, r);
            return jsi::Value::undefined();
        }
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.elem());
        jboolean added = c.env->CallBooleanMethod(c.ref(), c.j.add, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value(added == JNI_TRUE);
    }});
    m.push_back({"addAll", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1) throw jsi::JSError(r, "addAll: collection expected");
        rdmaCheckListMutable(r, c.self);
        jobject coll = rdmaTempJniCollection(c.env, r, c.jvm(), a[n - 1], c.elem());
        jboolean changed = (n >= 2 && a[0].isNumber())
            ? c.env->CallBooleanMethod(c.ref(), c.j.addAllAt, (jint)a[0].getNumber(), coll)
            : c.env->CallBooleanMethod(c.ref(), c.j.addAll, coll);
        checkAndThrowJs(c.env, r);
        return jsi::Value(changed == JNI_TRUE);
    }});
    m.push_back({"removeAt", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1 || !a[0].isNumber()) throw jsi::JSError(r, "removeAt: index expected");
        rdmaCheckListMutable(r, c.self);
        jobject removed = c.env->CallObjectMethod(c.ref(), c.j.removeAt, (jint)a[0].getNumber());
        checkAndThrowJs(c.env, r);
        return rdmaJniToJsTyped(r, c.jvm(), removed, c.elem());
    }});
    m.push_back({"remove", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        rdmaCheckListMutable(r, c.self);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.elem());
        jboolean removed = c.env->CallBooleanMethod(c.ref(), c.j.removeObj, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value(removed == JNI_TRUE);
    }});
    m.push_back({"removeRange", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 2) throw jsi::JSError(r, "removeRange: range expected");
        rdmaCheckListMutable(r, c.self);
        jobject sub = c.env->CallObjectMethod(c.ref(), c.j.subList, (jint)a[0].getNumber(), (jint)a[1].getNumber());
        checkAndThrowJs(c.env, r);
        c.env->CallVoidMethod(sub, c.j.clear);
        checkAndThrowJs(c.env, r);
        return jsi::Value::undefined();
    }});
    m.push_back({"removeAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1) throw jsi::JSError(r, "removeAll: collection expected");
        rdmaCheckListMutable(r, c.self);
        jobject coll = rdmaTempJniCollection(c.env, r, c.jvm(), a[0], c.elem());
        jboolean changed = c.env->CallBooleanMethod(c.ref(), c.j.removeAll, coll);
        checkAndThrowJs(c.env, r);
        return jsi::Value(changed == JNI_TRUE);
    }});
    m.push_back({"retainAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1) throw jsi::JSError(r, "retainAll: collection expected");
        rdmaCheckListMutable(r, c.self);
        jobject coll = rdmaTempJniCollection(c.env, r, c.jvm(), a[0], c.elem());
        jboolean changed = c.env->CallBooleanMethod(c.ref(), c.j.retainAll, coll);
        checkAndThrowJs(c.env, r);
        return jsi::Value(changed == JNI_TRUE);
    }});
    m.push_back({"clear", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        rdmaCheckListMutable(r, c.self);
        c.env->CallVoidMethod(c.ref(), c.j.clear);
        checkAndThrowJs(c.env, r);
        return jsi::Value::undefined();
    }});
    m.push_back({"sizeImpl", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        jint size = c.env->CallIntMethod(c.ref(), c.j.size);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)size);
    }});
    m.push_back({"isEmpty", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        jboolean empty = c.env->CallBooleanMethod(c.ref(), c.j.isEmpty);
        checkAndThrowJs(c.env, r);
        return jsi::Value(empty == JNI_TRUE);
    }});
    m.push_back({"contains", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.elem());
        jboolean has = c.env->CallBooleanMethod(c.ref(), c.j.contains, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value(has == JNI_TRUE);
    }});
    m.push_back({"containsAll", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1) return jsi::Value(false);
        jobject coll = rdmaTempJniCollection(c.env, r, c.jvm(), a[0], c.elem());
        jboolean has = c.env->CallBooleanMethod(c.ref(), c.j.containsAll, coll);
        checkAndThrowJs(c.env, r);
        return jsi::Value(has == JNI_TRUE);
    }});
    m.push_back({"indexOf", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.elem());
        jint idx = c.env->CallIntMethod(c.ref(), c.j.indexOf, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)idx);
    }});
    m.push_back({"lastIndexOf", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        jobject v = rdmaJsToJniTyped(c.env, r, c.jvm(), rdmaArg(a, n, 0), c.elem());
        jint idx = c.env->CallIntMethod(c.ref(), c.j.lastIndexOf, v);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)idx);
    }});
    m.push_back({"subList", 2, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 2) throw jsi::JSError(r, "subList: range expected");
        jobject sub = c.env->CallObjectMethod(c.ref(), c.j.subList, (jint)a[0].getNumber(), (jint)a[1].getNumber());
        checkAndThrowJs(c.env, r);
        return createListHandle(r, c.jvm(), c.env->NewGlobalRef(sub), c.elem());
    }});
    // One crossing for the whole list (docs/design/runtime_rework.md §3.6).
    m.push_back({"toArray", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        return snapshotOf(r, c);
    }});
    m.push_back({"iterator", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        return rdmaSnapshotIterator(r, RdmaIterKind::List, c.self, snapshotOf(r, c));
    }});
    m.push_back({"equals", 1, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
        ListCall c(r, t);
        if (n < 1 || !a[0].isObject()) return jsi::Value(false);
        jobject other = rdmaListJObjectOf(r, a[0]);
        if (!other) return jsi::Value(rdmaInheritedEquals(r, RdmaProtoKind::List, c.self, a[0]));
        jboolean eq = c.env->CallBooleanMethod(c.ref(), c.j.equals, other);
        checkAndThrowJs(c.env, r);
        return jsi::Value(eq == JNI_TRUE);
    }});
    m.push_back({"hashCode", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        jint h = c.env->CallIntMethod(c.ref(), c.j.hashCode);
        checkAndThrowJs(c.env, r);
        return jsi::Value((double)h);
    }});
    m.push_back({"toString", 0, [](jsi::Runtime& r, const jsi::Value& t, const jsi::Value*, size_t) -> jsi::Value {
        ListCall c(r, t);
        auto s = (jstring)c.env->CallObjectMethod(c.ref(), c.j.toString);
        checkAndThrowJs(c.env, r);
        return jstringToJs(c.env, r, s, "[]");
    }});
    return m;
}

// State-backed variant: every operation first registers the composition read.
std::vector<RdmaProtoMethod> stateBacked(std::vector<RdmaProtoMethod> methods) {
    for (auto& m : methods) {
        HostFn inner = std::move(m.fn);
        m.fn = [inner](jsi::Runtime& r, const jsi::Value& t, const jsi::Value* a, size_t n) -> jsi::Value {
            if (g_inComposition) {
                auto st = rdmaListStateOf(r, t);
                if (st) touchCompositionRead(st);
            }
            return inner(r, t, a, n);
        };
    }
    return methods;
}

} // namespace

std::vector<RdmaProtoMethod> rdmaJniListProtoMethods(bool stateBackedProto) {
    return stateBackedProto ? stateBacked(listMethods()) : listMethods();
}

jobject rdmaJsToJniTyped(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Value& v, const std::string& desc) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (isListDesc(desc)) {
        if (!v.isObject()) rdmaThrowTypeError(rt, "expected a List value for " + desc);
        jsi::Object obj = v.getObject(rt);
        return materializeArray(env, rt, jvm, obj, desc.substr(5));
    }
    if (isMapDesc(desc)) {
        if (!v.isObject()) rdmaThrowTypeError(rt, "expected a Map value for " + desc);
        auto kv = parseMapBody(desc.substr(4));
        jsi::Object obj = v.getObject(rt);
        return materializeMap(env, rt, jvm, obj, kv.first, kv.second);
    }
    // Scalar/enum leaves follow the descriptor's declared type (the `?`/`!` marks
    // stripped), never the value's shape (docs/design/type_descriptors.md).
    std::string fqn = desc.substr(0, desc.find_first_of("?!"));
    if (desc.find('!') != std::string::npos) return rdmaBoxEnumValue(env, rt, v, fqn);
    if (fqn == "kotlin.Int" || fqn == "kotlin.Long" || fqn == "kotlin.Double" ||
        fqn == "kotlin.Float" || fqn == "kotlin.Boolean" || fqn == "kotlin.String" ||
        fqn == "rdma.BlockId") {
        return rdmaBoxLeafValue(env, rt, v, fqn);
    }
    // `@RDMA` class leaf: the kernel object behind the plugin-side proxy.
    if (v.isObject()) {
        auto st = rdmaStateAs<RdmaObjectNativeState>(rt, v, RdmaStateKind::Object);
        if (st) return env->NewLocalRef(st->getObject());
        return nullptr;
    }
    // Unknown leaves keep the runtime-dispatch boxer (pre-descriptor behavior, e.g.
    // callback-id lists) — the same fallback as the C ABI backend.
    return boxJsi(env, rt, v);
}

jsi::Value rdmaJniToJsTyped(jsi::Runtime& rt, JavaVM* jvm, jobject local, const std::string& desc) {
    if (!local) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (isListDesc(desc)) {
        jobject global = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        return createListHandle(rt, jvm, global, desc.substr(5));
    }
    if (isMapDesc(desc)) {
        auto kv = parseMapBody(desc.substr(4));
        jobject global = env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        return createMapHandle(rt, jvm, global, kv.first, kv.second);
    }
    jsi::Value out = wrapAny(rt, jvm, local);
    env->DeleteLocalRef(local);
    return out;
}

jobject rdmaTempJniCollection(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, const jsi::Value& v, const std::string& elemDesc) {
    if (!v.isObject()) rdmaThrowTypeError(rt, "expected a collection argument");
    if (jobject native = rdmaListJObjectOf(rt, v)) return env->NewLocalRef(native);
    return newJniArrayList(env, rt, jvm, jsElementsOf(rt, v.getObject(rt)), elemDesc);
}

void populateListHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalListRef, const std::string& elementType, bool stateBacked) {
    auto state = std::make_shared<ListNativeState>(jvm, globalListRef, elementType);
    state->stateBacked_ = stateBacked;
    rdmaAdoptProxy(rt, obj, stateBacked ? RdmaProtoKind::StateList : RdmaProtoKind::List, std::move(state));
}

void morphArrayList(jsi::Runtime&, JavaVM*, jsi::Object&, const std::string&) {
    // The prototype swap in populateListHandle already shadows every storage method.
}

jsi::Object createListHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalListRef, const std::string& elementType, bool stateBacked) {
    auto state = std::make_shared<ListNativeState>(jvm, globalListRef, elementType);
    state->stateBacked_ = stateBacked;
    return rdmaNewProxy(rt, stateBacked ? RdmaProtoKind::StateList : RdmaProtoKind::List, std::move(state));
}

jobject materializeArray(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& elementType) {
    // Already a proxy (kernel list, or a plugin list that crossed before): the same native list.
    if (auto st = rdmaListStateOf(rt, jsObj)) return env->NewLocalRef(st->globalListRef_);

    // A raw JS array (e.g. a list of block ids) is copied, never adopted.
    if (jsObj.isArray(rt)) return newJniArrayList(env, rt, jvm, jsObj.getArray(rt), elementType);

    int kind = listKindOf(rt, jsi::Value(rt, jsObj));
    if (kind == 2) {
        throw jsi::JSError(rt, "unsupported List implementation crossing the runtime boundary; only ArrayList and EmptyList are supported");
    }
    if (kind == 1) {
        // EmptyList: the immutable native empty list; the JS singleton is not adopted.
        const ListJni& j = listJni(env);
        return env->CallStaticObjectMethod(j.collectionsClass, j.emptyList);
    }

    jobject list = newJniArrayList(env, rt, jvm, jsElementsOf(rt, jsObj), elementType);
    // The proxy owns its own global reference; the caller owns the returned local.
    populateListHandle(rt, jvm, jsObj, env->NewGlobalRef(list), elementType);
    return list;
}

} // namespace rdma
} // namespace facebook
