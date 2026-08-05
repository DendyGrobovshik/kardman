#include <jni.h>
#include <string>
#include <android/log.h>

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
