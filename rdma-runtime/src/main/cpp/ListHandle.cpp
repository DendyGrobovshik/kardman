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
#include "ListHandle.h"
#include "RdmaCompose.h"
#include <jni.h>

namespace facebook {
namespace rdma {

// Element type is a recursive descriptor: primitives / @RDMA FQN, or a nested
// `list:<elem>` for a list-of-lists.
static bool isListElementType(const std::string& elementType) {
    return elementType.rfind("list:", 0) == 0;
}

static std::string nestedElementType(const std::string& elementType) {
    return elementType.substr(5);
}

// Converts a single JS value into a JVM object using the element type. Nested
// lists materialize recursively; @RDMA objects reuse their held jobject;
// primitives/strings are boxed.
static jobject jsiToJobject(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm,
                            const jsi::Value& v, const std::string& elementType) {
    if (v.isNull() || v.isUndefined()) return nullptr;
    if (isListElementType(elementType)) {
        jsi::Object obj = v.asObject(rt);
        return materializeArray(env, rt, jvm, obj, nestedElementType(elementType));
    }
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
    return boxJsi(env, rt, v);
}

// Wraps a JVM list element into a JS value. Caller owns the local reference `element`.
static jsi::Value wrapListElement(jsi::Runtime& r, JavaVM* jvm, jobject element, const std::string& elementType) {
    if (!element) return jsi::Value::null();
    JNIEnv* env = getEnv(jvm);
    if (isListElementType(elementType)) {
        jobject globalElem = nullptr;
        if (env) globalElem = env->NewGlobalRef(element);
        if (env) env->DeleteLocalRef(element);
        return createListHandle(r, jvm, globalElem, nestedElementType(elementType));
    }
    jsi::Value result = wrapAny(r, jvm, element);
    if (env) env->DeleteLocalRef(element);
    return result;
}

// Invokes the original `checkIsMutable` (recorded during morphing) before a
// mutation. Returns false if the list is read-only and the JS error must stop the
// operation (the call itself throws).
static void checkIsMutable(jsi::Runtime& r, const jsi::Object& thisObj, const std::string& name) {
    if (name.empty()) return;
    auto fn = thisObj.getProperty(r, name.c_str());
    if (fn.isObject() && fn.asObject(r).isFunction(r)) {
        fn.asObject(r).asFunction(r).callWithThis(r, thisObj, nullptr, 0);
    }
}

static std::shared_ptr<ListNativeState> listStateOf(jsi::Runtime& r, const jsi::Object& obj) {
    return std::static_pointer_cast<ListNativeState>(obj.getNativeState(r));
}

void populateListHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalListRef, const std::string& elementType) {
    auto ns = std::make_shared<ListNativeState>(jvm, globalListRef, elementType);
    obj.setNativeState(rt, ns);

    auto getFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "get"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            int index = (int)args[0].getNumber();
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID getMethod = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
            jobject element = env->CallObjectMethod(state->globalListRef_, getMethod, index);
            return wrapListElement(r, jvm, element, state->elementType_);
        });
    obj.setProperty(rt, "get", std::move(getFn));

    auto setFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "set"), 2,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            int index = (int)args[0].getNumber();
            jobject value = jsiToJobject(env, r, jvm, args[1], state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID setMethod = env->GetMethodID(listCls, "set", "(ILjava/lang/Object;)Ljava/lang/Object;");
            env->CallObjectMethod(state->globalListRef_, setMethod, index, value);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "set", std::move(setFn));

    auto addFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "add"), 2,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            if (count >= 2 && args[0].isNumber() && !args[1].isUndefined()) {
                int index = (int)args[0].getNumber();
                jobject value = jsiToJobject(env, r, jvm, args[1], state->elementType_);
                jmethodID addAtMethod = env->GetMethodID(listCls, "add", "(ILjava/lang/Object;)V");
                env->CallVoidMethod(state->globalListRef_, addAtMethod, index, value);
            } else {
                jobject value = jsiToJobject(env, r, jvm, args[0], state->elementType_);
                jmethodID addMethod = env->GetMethodID(listCls, "add", "(Ljava/lang/Object;)Z");
                env->CallBooleanMethod(state->globalListRef_, addMethod, value);
            }
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "add", std::move(addFn));

    auto removeAtFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "removeAt"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            int index = (int)args[0].getNumber();
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID removeMethod = env->GetMethodID(listCls, "remove", "(I)Ljava/lang/Object;");
            jobject removed = env->CallObjectMethod(state->globalListRef_, removeMethod, index);
            if (removed) env->DeleteLocalRef(removed);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "removeAt", std::move(removeAtFn));

    auto removeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "remove"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jobject value = jsiToJobject(env, r, jvm, args[0], state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID removeMethod = env->GetMethodID(listCls, "remove", "(Ljava/lang/Object;)Z");
            jboolean removed = env->CallBooleanMethod(state->globalListRef_, removeMethod, value);
            return jsi::Value(removed == JNI_TRUE);
        });
    obj.setProperty(rt, "remove", std::move(removeFn));

    auto removeRangeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "removeRange"), 2,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            int from = (int)args[0].getNumber();
            int to = (int)args[1].getNumber();
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID subListMethod = env->GetMethodID(listCls, "subList", "(II)Ljava/util/List;");
            jobject sub = env->CallObjectMethod(state->globalListRef_, subListMethod, from, to);
            jclass subCls = env->GetObjectClass(sub);
            jmethodID clearMethod = env->GetMethodID(subCls, "clear", "()V");
            env->CallVoidMethod(sub, clearMethod);
            env->DeleteLocalRef(sub);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "removeRange", std::move(removeRangeFn));

    auto clearFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "clear"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID clearMethod = env->GetMethodID(listCls, "clear", "()V");
            env->CallVoidMethod(state->globalListRef_, clearMethod);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "clear", std::move(clearFn));

    auto sizeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID sizeMethod = env->GetMethodID(listCls, "size", "()I");
            jint size = env->CallIntMethod(state->globalListRef_, sizeMethod);
            return jsi::Value((double)size);
        });
    obj.setProperty(rt, "sizeImpl", std::move(sizeFn));

    auto isEmptyFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "isEmpty"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID isEmptyMethod = env->GetMethodID(listCls, "isEmpty", "()Z");
            return jsi::Value(env->CallBooleanMethod(state->globalListRef_, isEmptyMethod) == JNI_TRUE);
        });
    obj.setProperty(rt, "isEmpty", std::move(isEmptyFn));

    auto containsFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "contains"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jobject value = jsiToJobject(env, r, jvm, args[0], state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID containsMethod = env->GetMethodID(listCls, "contains", "(Ljava/lang/Object;)Z");
            return jsi::Value(env->CallBooleanMethod(state->globalListRef_, containsMethod, value) == JNI_TRUE);
        });
    obj.setProperty(rt, "contains", std::move(containsFn));

    auto indexOfFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "indexOf"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jobject value = jsiToJobject(env, r, jvm, args[0], state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID indexOfMethod = env->GetMethodID(listCls, "indexOf", "(Ljava/lang/Object;)I");
            jint index = env->CallIntMethod(state->globalListRef_, indexOfMethod, value);
            return jsi::Value((double)index);
        });
    obj.setProperty(rt, "indexOf", std::move(indexOfFn));

    auto lastIndexOfFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "lastIndexOf"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jobject value = jsiToJobject(env, r, jvm, args[0], state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID lastIndexOfMethod = env->GetMethodID(listCls, "lastIndexOf", "(Ljava/lang/Object;)I");
            jint index = env->CallIntMethod(state->globalListRef_, lastIndexOfMethod, value);
            return jsi::Value((double)index);
        });
    obj.setProperty(rt, "lastIndexOf", std::move(lastIndexOfFn));

    auto toArrayFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "toArray"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID sizeMethod = env->GetMethodID(listCls, "size", "()I");
            jmethodID getMethod = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
            jint size = env->CallIntMethod(state->globalListRef_, sizeMethod);
            auto arr = jsi::Array(r, size);
            for (jint i = 0; i < size; i++) {
                jobject element = env->CallObjectMethod(state->globalListRef_, getMethod, i);
                arr.setValueAtIndex(r, i, wrapListElement(r, jvm, element, state->elementType_));
            }
            return arr;
        });
    obj.setProperty(rt, "toArray", std::move(toArrayFn));

    auto toStringFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "toString"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID toStringMethod = env->GetMethodID(listCls, "toString", "()Ljava/lang/String;");
            jstring jstr = (jstring)env->CallObjectMethod(state->globalListRef_, toStringMethod);
            if (!jstr) return jsi::String::createFromUtf8(r, "[]");
            const char* cstr = env->GetStringUTFChars(jstr, nullptr);
            auto result = jsi::String::createFromUtf8(r, cstr ? cstr : "");
            if (cstr) env->ReleaseStringUTFChars(jstr, cstr);
            env->DeleteLocalRef(jstr);
            return result;
        });
    obj.setProperty(rt, "toString", std::move(toStringFn));

    auto iteratorFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "iterator"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jobject listRef = env->NewGlobalRef(state->globalListRef_);
            auto index = std::make_shared<int>(0);
            auto last = std::make_shared<int>(-1);
            std::string elemType = state->elementType_;

            auto it = jsi::Object(r);
            auto hasNextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "hasNext"), 0,
                [jvm, listRef, index](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                    JNIEnv* e = getEnv(jvm);
                    if (!e) return jsi::Value::undefined();
                    jclass listCls = e->GetObjectClass(listRef);
                    jmethodID sizeMethod = e->GetMethodID(listCls, "size", "()I");
                    jint size = e->CallIntMethod(listRef, sizeMethod);
                    return jsi::Value(*index < size);
                });
            it.setProperty(r, "hasNext", std::move(hasNextFn));

            auto nextFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "next"), 0,
                [jvm, listRef, index, last, elemType](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                    JNIEnv* e = getEnv(jvm);
                    if (!e) return jsi::Value::undefined();
                    jclass listCls = e->GetObjectClass(listRef);
                    jmethodID sizeMethod = e->GetMethodID(listCls, "size", "()I");
                    jmethodID getMethod = e->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
                    jint size = e->CallIntMethod(listRef, sizeMethod);
                    if (*index >= size) return jsi::Value::undefined();
                    int i = (*index)++;
                    *last = i;
                    jobject element = e->CallObjectMethod(listRef, getMethod, i);
                    return wrapListElement(rr, jvm, element, elemType);
                });
            it.setProperty(r, "next", std::move(nextFn));

            auto removeFn = jsi::Function::createFromHostFunction(r, jsi::PropNameID::forAscii(r, "remove"), 0,
                [jvm, listRef, index, last](jsi::Runtime& rr, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
                    JNIEnv* e = getEnv(jvm);
                    if (!e) return jsi::Value::undefined();
                    if (*last < 0) return jsi::Value::undefined();
                    jclass listCls = e->GetObjectClass(listRef);
                    jmethodID removeMethod = e->GetMethodID(listCls, "remove", "(I)Ljava/lang/Object;");
                    jobject removed = e->CallObjectMethod(listRef, removeMethod, *last);
                    if (removed) e->DeleteLocalRef(removed);
                    *index = *last;
                    *last = -1;
                    return jsi::Value::undefined();
                });
            it.setProperty(r, "remove", std::move(removeFn));

            // The iterator keeps the JVM list alive; free the ref when the JS
            // iterator object is collected.
            struct ItState : public jsi::NativeState {
                JavaVM* jvm; jobject ref;
                ItState(JavaVM* j, jobject r) : jvm(j), ref(r) {}
                ~ItState() {
                    if (ref && jvm) {
                        JNIEnv* e = getEnv(jvm);
                        if (e) e->DeleteGlobalRef(ref);
                    }
                }
            };
            it.setNativeState(r, std::make_shared<ItState>(jvm, listRef));

            return it;
        });
    obj.setProperty(rt, "iterator", std::move(iteratorFn));

    auto equalsFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "equals"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value(false);
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto otherObj = args[0].asObject(r);
            auto state = listStateOf(r, thisObj);
            if (!otherObj.hasNativeState(r)) return jsi::Value(false);
            auto otherState = std::dynamic_pointer_cast<ListNativeState>(otherObj.getNativeState(r));
            if (!otherState) return jsi::Value(false);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID equalsMethod = env->GetMethodID(listCls, "equals", "(Ljava/lang/Object;)Z");
            jboolean eq = env->CallBooleanMethod(state->globalListRef_, equalsMethod, otherState->globalListRef_);
            return jsi::Value(eq == JNI_TRUE);
        });
    obj.setProperty(rt, "equals", std::move(equalsFn));

    auto hashCodeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "hashCode"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID hashCodeMethod = env->GetMethodID(listCls, "hashCode", "()I");
            return jsi::Value((double)env->CallIntMethod(state->globalListRef_, hashCodeMethod));
        });
    obj.setProperty(rt, "hashCode", std::move(hashCodeFn));

    auto subListFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "subList"), 2,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto state = listStateOf(r, thisVal.asObject(r));
            int from = (int)args[0].getNumber();
            int to = (int)args[1].getNumber();
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID subListMethod = env->GetMethodID(listCls, "subList", "(II)Ljava/util/List;");
            jobject sub = env->CallObjectMethod(state->globalListRef_, subListMethod, from, to);
            jobject globalSub = env->NewGlobalRef(sub);
            env->DeleteLocalRef(sub);
            return createListHandle(r, jvm, globalSub, state->elementType_);
        });
    obj.setProperty(rt, "subList", std::move(subListFn));

    auto addAllFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "addAll"), 2,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[count - 1].isObject()) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            auto srcObj = args[count - 1].asObject(r);
            jobject collection = nullptr;
            bool borrowed = false;
            if (srcObj.hasNativeState(r)) {
                auto s = std::dynamic_pointer_cast<ListNativeState>(srcObj.getNativeState(r));
                if (s) { collection = s->globalListRef_; borrowed = true; }
            }
            if (!collection) collection = materializeArray(env, r, jvm, srcObj, state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            if (count >= 2) {
                int index = (int)args[0].getNumber();
                jmethodID addAllAtMethod = env->GetMethodID(listCls, "addAll", "(ILjava/util/Collection;)Z");
                env->CallBooleanMethod(state->globalListRef_, addAllAtMethod, index, collection);
            } else {
                jmethodID addAllMethod = env->GetMethodID(listCls, "addAll", "(Ljava/util/Collection;)Z");
                env->CallBooleanMethod(state->globalListRef_, addAllMethod, collection);
            }
            if (!borrowed) env->DeleteGlobalRef(collection);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "addAll", std::move(addAllFn));

    auto removeAllFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "removeAll"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            auto srcObj = args[0].asObject(r);
            jobject collection = nullptr;
            bool borrowed = false;
            if (srcObj.hasNativeState(r)) {
                auto s = std::dynamic_pointer_cast<ListNativeState>(srcObj.getNativeState(r));
                if (s) { collection = s->globalListRef_; borrowed = true; }
            }
            if (!collection) collection = materializeArray(env, r, jvm, srcObj, state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID removeAllMethod = env->GetMethodID(listCls, "removeAll", "(Ljava/util/Collection;)Z");
            env->CallBooleanMethod(state->globalListRef_, removeAllMethod, collection);
            if (!borrowed) env->DeleteGlobalRef(collection);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "removeAll", std::move(removeAllFn));

    auto retainAllFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "retainAll"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isObject()) return jsi::Value::undefined();
            JNIEnv* env = getEnv(jvm);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = listStateOf(r, thisObj);
            checkIsMutable(r, thisObj, state->checkIsMutable_);
            auto srcObj = args[0].asObject(r);
            jobject collection = nullptr;
            bool borrowed = false;
            if (srcObj.hasNativeState(r)) {
                auto s = std::dynamic_pointer_cast<ListNativeState>(srcObj.getNativeState(r));
                if (s) { collection = s->globalListRef_; borrowed = true; }
            }
            if (!collection) collection = materializeArray(env, r, jvm, srcObj, state->elementType_);
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID retainAllMethod = env->GetMethodID(listCls, "retainAll", "(Ljava/util/Collection;)Z");
            env->CallBooleanMethod(state->globalListRef_, retainAllMethod, collection);
            if (!borrowed) env->DeleteGlobalRef(collection);
            return jsi::Value::undefined();
        });
    obj.setProperty(rt, "retainAll", std::move(retainAllFn));

    rt.global().setProperty(rt, "__rdma_list", jsi::Value(rt, obj));
    rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(
        "Object.defineProperty(globalThis.__rdma_list, 'size', {"
        "  get: function() { return this.sizeImpl(); },"
        "  configurable: true"
        "})"
    ), "<listGetter>");
    rt.global().setProperty(rt, "__rdma_list", jsi::Value::undefined());
}

// Installs a wrapper on `obj` under `mangledName` that forwards to `plainName`.
static void forwardMangled(jsi::Runtime& rt, jsi::Object& obj, const std::string& mangledName, const std::string& plainName) {
    auto fn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, mangledName.c_str()), 2,
        [plainName](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            auto target = thisVal.asObject(r).getProperty(r, plainName.c_str());
            if (!target.isObject() || !target.asObject(r).isFunction(r)) return jsi::Value::undefined();
            return target.asObject(r).asFunction(r).callWithThis(r, thisVal.asObject(r), args, count);
        });
    obj.setProperty(rt, mangledName.c_str(), std::move(fn));
}

void morphArrayList(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, const std::string& elementType) {
    (void)jvm; (void)elementType;
    auto objectCtor = rt.global().getPropertyAsObject(rt, "Object");
    auto getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
    jsi::Object proto = getPrototypeOf.call(rt, jsi::Value(rt, obj)).asObject(rt);

    auto state = listStateOf(rt, obj);
    auto names = proto.getPropertyNames(rt);
    size_t n = names.size(rt);
    for (size_t i = 0; i < n; i++) {
        std::string name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
        if (name.rfind("checkIsMutable_", 0) == 0) {
            state->checkIsMutable_ = name;
        } else if (name.rfind("get_size_", 0) == 0) {
            forwardMangled(rt, obj, name, "sizeImpl");
        } else if (name.rfind("get_", 0) == 0) {
            forwardMangled(rt, obj, name, "get");
        } else if (name.rfind("set_", 0) == 0) {
            forwardMangled(rt, obj, name, "set");
        } else if (name.rfind("add_", 0) == 0) {
            forwardMangled(rt, obj, name, "add");
        } else if (name.rfind("removeAt_", 0) == 0) {
            forwardMangled(rt, obj, name, "removeAt");
        } else if (name.rfind("removeRange_", 0) == 0) {
            forwardMangled(rt, obj, name, "removeRange");
        } else if (name.rfind("clear_", 0) == 0) {
            forwardMangled(rt, obj, name, "clear");
        } else if (name.rfind("toArray", 0) == 0) {
            forwardMangled(rt, obj, name, "toArray");
        }
    }
}

jsi::Object createListHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalListRef, const std::string& elementType) {
    auto listObj = jsi::Object(rt);
    populateListHandle(rt, jvm, listObj, globalListRef, elementType);
    return listObj;
}

// Queries the plugin-side classifier (`RDMA.listKind`): 0 = ArrayList, 1 =
// EmptyList, 2 = unsupported. Returns -1 when the classifier is not installed.
static int listKindOf(jsi::Runtime& rt, const jsi::Value& v) {
    auto rdma = rt.global().getProperty(rt, "RDMA");
    if (!rdma.isObject()) return -1;
    auto fn = rdma.asObject(rt).getProperty(rt, "listKind");
    if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return -1;
    auto res = fn.asObject(rt).asFunction(rt).call(rt, v);
    return res.isNumber() ? (int)res.getNumber() : -1;
}

jobject materializeArray(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& elementType) {
    int kind = listKindOf(rt, jsi::Value(rt, jsObj));
    if (kind == 2) {
        throw jsi::JSError(rt, "unsupported List implementation crossing the runtime boundary; only ArrayList and EmptyList are supported");
    }
    if (kind == 1) {
        // EmptyList: materialize to the immutable JVM empty list without morphing
        // the JS singleton.
        jclass collectionsCls = env->FindClass("java/util/Collections");
        jmethodID emptyListMethod = env->GetStaticMethodID(collectionsCls, "emptyList", "()Ljava/util/List;");
        jobject empty = env->CallStaticObjectMethod(collectionsCls, emptyListMethod);
        return env->NewGlobalRef(empty);
    }

    jclass alCls = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(alCls, "<init>", "()V");
    jmethodID addMethod = env->GetMethodID(alCls, "add", "(Ljava/lang/Object;)Z");
    jobject localList = env->NewObject(alCls, ctor);

    size_t len = 0;
    jsi::Array backingArray(rt, 0);
    int path = 0; // 0=none, 1=jsArray, 2=length/size, 3=backing array (Kotlin List)
    if (jsObj.isArray(rt)) {
        backingArray = jsObj.asArray(rt);
        len = backingArray.size(rt);
        path = 1;
    } else {
        auto lp = jsObj.getProperty(rt, "length");
        if (!lp.isNumber()) {
            lp = jsObj.getProperty(rt, "size");
        }
        if (lp.isNumber()) {
            len = (size_t)lp.getNumber();
            path = 2;
        } else {
            auto array1 = jsObj.getProperty(rt, "array_1");
            if (array1.isObject() && array1.asObject(rt).isArray(rt)) {
                backingArray = array1.asObject(rt).asArray(rt);
                len = backingArray.size(rt);
                path = 3;
            } else {
                auto names = jsObj.getPropertyNames(rt);
                size_t n = names.size(rt);
                for (size_t i = 0; i < n; i++) {
                    auto name = names.getValueAtIndex(rt, i).getString(rt).utf8(rt);
                    auto val = jsObj.getProperty(rt, name.c_str());
                    if (val.isObject() && val.asObject(rt).isArray(rt)) {
                        backingArray = val.asObject(rt).asArray(rt);
                        len = backingArray.size(rt);
                        path = 3;
                        break;
                    }
                }
            }
        }
        if (path == 0) {
            // No backing array: only a Kotlin/JS `EmptyList` or an unsupported
            // implementation. Treat it as empty; non-ArrayList implementations are
            // rejected by the plugin-side classifier before this point.
            jobject globalList = env->NewGlobalRef(localList);
            env->DeleteLocalRef(localList);
            populateListHandle(rt, jvm, jsObj, globalList, elementType);
            return globalList;
        }
    }

    for (size_t i = 0; i < len; i++) {
        jsi::Value elem;
        if (path == 1 || path == 3) {
            elem = backingArray.getValueAtIndex(rt, i);
        } else {
            elem = jsObj.getProperty(rt, std::to_string(i).c_str());
        }
        jobject converted = jsiToJobject(env, rt, jvm, elem, elementType);
        if (converted) {
            env->CallBooleanMethod(localList, addMethod, converted);
        }
    }

    jobject globalList = env->NewGlobalRef(localList);
    env->DeleteLocalRef(localList);

    populateListHandle(rt, jvm, jsObj, globalList, elementType);
    morphArrayList(rt, jvm, jsObj, elementType);

    return globalList;
}

} // namespace rdma
} // namespace facebook
