#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jsi/jsi.h>

#define LOG_TAG "RdmaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern void initRdmaRuntime(JavaVM* jvm);
extern void evalJavaScript(const std::string& code, std::string& result);

static JavaVM* g_jvm = nullptr;
static AAssetManager* g_assetMgr = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeInit(JNIEnv* env, jclass, jobject assetManager) {
    env->GetJavaVM(&g_jvm);
    g_assetMgr = AAssetManager_fromJava(env, assetManager);
    initRdmaRuntime(g_jvm);
    LOGI("RDMA runtime initialized");
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeEvalAsset(JNIEnv* env, jclass, jstring assetPath) {
    if (!g_assetMgr) {
        return env->NewStringUTF("Error: AssetManager not initialized");
    }
    const char* path = env->GetStringUTFChars(assetPath, nullptr);
    AAsset* asset = AAssetManager_open(g_assetMgr, path, AASSET_MODE_BUFFER);
    env->ReleaseStringUTFChars(assetPath, path);
    if (!asset) {
        return env->NewStringUTF("Error: failed to open asset");
    }
    const void* data = AAsset_getBuffer(asset);
    off_t length = AAsset_getLength(asset);
    std::string result;
    if (data && length > 0) {
        std::string code(static_cast<const char*>(data), static_cast<size_t>(length));
        evalJavaScript(code, result);
    } else if (length > 0) {
        std::string code(static_cast<size_t>(length), '\0');
        int read = AAsset_read(asset, &code[0], static_cast<size_t>(length));
        if (read == length) {
            evalJavaScript(code, result);
        } else {
            result = "Error: failed to read asset";
        }
    } else if (length == 0) {
        result = "";
    } else {
        result = "Error: failed to read asset";
    }
    AAsset_close(asset);
    return env->NewStringUTF(result.c_str());
}
