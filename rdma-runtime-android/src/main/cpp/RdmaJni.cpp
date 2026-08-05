#include <jni.h>
#include <string>
#include <android/log.h>
#include <jsi/jsi.h>
#include "RdmaVtable.h"

#define LOG_TAG "RdmaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern void initRdmaRuntime(JavaVM* jvm);
extern void evalJavaScript(const std::string& code, std::string& result);

static JavaVM* g_jvm = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeInit(JNIEnv* env, jclass) {
    env->GetJavaVM(&g_jvm);
    initRdmaRuntime(g_jvm);
    LOGI("RDMA runtime initialized");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeEval(JNIEnv* env, jclass, jstring jsCode) {
    const char* code = env->GetStringUTFChars(jsCode, nullptr);
    std::string cppCode(code);
    env->ReleaseStringUTFChars(jsCode, code);
    std::string result;
    evalJavaScript(cppCode, result);
    return env->NewStringUTF(result.c_str());
}

// Vtable dispatch: called from Kotlin open methods to check for JS overrides
extern "C" JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeDispatch(JNIEnv* env, jclass, jlong vtablePtr, jstring methodName, jobjectArray args) {
    if (vtablePtr == 0) return nullptr;

    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    const char* methodCstr = env->GetStringUTFChars(methodName, nullptr);
    std::string methodStr(methodCstr);
    env->ReleaseStringUTFChars(methodName, methodCstr);

    auto it = vt->entries.find(methodStr);
    if (it == vt->entries.end()) return nullptr;

    facebook::jsi::Runtime& rt = *vt->rt;
    jsize argCount = args ? env->GetArrayLength(args) : 0;
    std::vector<facebook::jsi::Value> jsiArgs;
    jsiArgs.reserve(argCount);
    for (jsize i = 0; i < argCount; i++) {
        jobject arg = env->GetObjectArrayElement(args, i);
        facebook::jsi::Value jsiVal = facebook::jsi::Value::null();
        if (arg != nullptr) {
            jclass stringCls = env->FindClass("java/lang/String");
            if (env->IsInstanceOf(arg, stringCls)) {
                const char* cstr = env->GetStringUTFChars((jstring)arg, nullptr);
                jsiVal = facebook::jsi::String::createFromUtf8(rt, cstr);
                env->ReleaseStringUTFChars((jstring)arg, cstr);
            } else {
                jclass intCls = env->FindClass("java/lang/Integer");
                if (env->IsInstanceOf(arg, intCls)) {
                    jmethodID intValue = env->GetMethodID(intCls, "intValue", "()I");
                    jint iv = env->CallIntMethod(arg, intValue);
                    jsiVal = facebook::jsi::Value((double)iv);
                } else {
                    jclass boolCls = env->FindClass("java/lang/Boolean");
                    if (env->IsInstanceOf(arg, boolCls)) {
                        jmethodID boolValue = env->GetMethodID(boolCls, "booleanValue", "()Z");
                        jsiVal = facebook::jsi::Value(env->CallBooleanMethod(arg, boolValue));
                    }
                }
            }
        }
        jsiArgs.push_back(std::move(jsiVal));
        env->DeleteLocalRef(arg);
    }

    try {
        const facebook::jsi::Value* argsPtr = jsiArgs.empty() ? nullptr : jsiArgs.data();
        size_t jsArgCount = jsiArgs.size();
        auto& irt = *(facebook::jsi::IRuntime*)&rt;
        facebook::jsi::Value result = it->second->call(irt, argsPtr, jsArgCount);
        if (result.isString()) {
            std::string s = result.getString(rt).utf8(rt);
            return env->NewStringUTF(s.c_str());
        }
    } catch (const std::exception& e) {
        LOGI("nativeDispatch error: %s", e.what());
    }
    return nullptr;
}

// Kernel-side vtable dispatch: called from Kotlin open methods
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_kernel_RdmaVtableKt_rdmaVtableDispatch(JNIEnv* env, jclass, jlong vtablePtr, jstring methodName) {
    if (vtablePtr == 0) return nullptr;

    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    const char* methodCstr = env->GetStringUTFChars(methodName, nullptr);
    std::string methodStr(methodCstr);
    env->ReleaseStringUTFChars(methodName, methodCstr);

    auto it = vt->entries.find(methodStr);
    if (it == vt->entries.end()) return nullptr;

    try {
        // Call with no args and undefined this
        auto& irt = *(facebook::jsi::IRuntime*)vt->rt;
        facebook::jsi::Value result = it->second->call(irt, nullptr, 0);
        if (result.isString()) {
            std::string s = result.getString(*vt->rt).utf8(*vt->rt);
            return env->NewStringUTF(s.c_str());
        }
    } catch (const std::exception& e) {
        LOGI("rdmaVtableDispatch error: %s", e.what());
    }
    return nullptr;
}
