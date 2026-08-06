#include "ListHandle.h"
#include <jni.h>

namespace facebook {
namespace rdma {

void populateListHandle(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& obj, jobject globalListRef, const std::string& elementType) {
    auto ns = std::make_shared<ListNativeState>(jvm, globalListRef, elementType);
    obj.setNativeState(rt, ns);
    
    auto getFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "get"), 1,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)count;
            JNIEnv* env = nullptr;
            jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = std::static_pointer_cast<ListNativeState>(thisObj.getNativeState(r));
            int index = (int)args[0].getNumber();
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID getMethod = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
            jobject element = env->CallObjectMethod(state->globalListRef_, getMethod, index);
            if (!element) return jsi::Value::null();
            jobject globalElem = env->NewGlobalRef(element);
            env->DeleteLocalRef(element);
            return jsi::Value::null();
        });
    obj.setProperty(rt, "get", std::move(getFn));
    
    auto sizeFn = jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forAscii(rt, "sizeImpl"), 0,
        [jvm](jsi::Runtime& r, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
            (void)args; (void)count;
            JNIEnv* env = nullptr;
            jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (!env) return jsi::Value::undefined();
            auto thisObj = thisVal.asObject(r);
            auto state = std::static_pointer_cast<ListNativeState>(thisObj.getNativeState(r));
            jclass listCls = env->GetObjectClass(state->globalListRef_);
            jmethodID sizeMethod = env->GetMethodID(listCls, "size", "()I");
            jint size = env->CallIntMethod(state->globalListRef_, sizeMethod);
            return jsi::Value((double)size);
        });
    obj.setProperty(rt, "sizeImpl", std::move(sizeFn));
    
    rt.global().setProperty(rt, "__rdma_list", jsi::Value(rt, obj));
    rt.evaluateJavaScript(std::make_shared<jsi::StringBuffer>(
        "Object.defineProperty(globalThis.__rdma_list, 'size', {"
        "  get: function() { return this.sizeImpl(); },"
        "  configurable: true"
        "})"
    ), "<listGetter>");
    rt.global().setProperty(rt, "__rdma_list", jsi::Value::undefined());
}

jsi::Object createListHandle(jsi::Runtime& rt, JavaVM* jvm, jobject globalListRef, const std::string& elementType) {
    auto listObj = jsi::Object(rt);
    populateListHandle(rt, jvm, listObj, globalListRef, elementType);
    return listObj;
}

jobject materializeArray(JNIEnv* env, jsi::Runtime& rt, JavaVM* jvm, jsi::Object& jsObj, const std::string& elementType) {
    jclass alCls = env->FindClass("java/util/ArrayList");
    jmethodID ctor = env->GetMethodID(alCls, "<init>", "()V");
    jmethodID addMethod = env->GetMethodID(alCls, "add", "(Ljava/lang/Object;)Z");
    jobject localList = env->NewObject(alCls, ctor);
    
    size_t len = 0;
    jsi::Array backingArray(rt, 0);
    int path = 0; // 0=none, 1=jsArray, 2=length/size, 3=array_1 (Kotlin ArrayList)
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
            if (array1.isObject()) {
                auto a1obj = array1.asObject(rt);
                if (a1obj.isArray(rt)) {
                    backingArray = a1obj.asArray(rt);
                    len = backingArray.size(rt);
                    path = 3;
                }
            }
        }
        if (path == 0) {
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
        if (elem.isObject()) {
            jsi::Object eo = elem.asObject(rt);
            if (eo.hasNativeState(rt)) {
                auto ns = eo.getNativeState(rt);
                if (ns) {
                    jobject je = *(jobject*)((char*)ns.get() + 24);
                    if (je) env->CallBooleanMethod(localList, addMethod, je);
                }
            }
        }
    }
    
    jobject globalList = env->NewGlobalRef(localList);
    env->DeleteLocalRef(localList);
    
    populateListHandle(rt, jvm, jsObj, globalList, elementType);
    
    return globalList;
}

} // namespace rdma
} // namespace facebook
