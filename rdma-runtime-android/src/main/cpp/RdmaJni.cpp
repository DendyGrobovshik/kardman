#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jsi/jsi.h>
#include "RdmaVtable.h"

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

// Kernel-side vtable dispatch: called from Kotlin open methods
extern "C" JNIEXPORT jobject JNICALL
Java_com_example_kernel_RdmaVtableKt_rdmaVtableDispatch(JNIEnv* env, jclass, jlong vtablePtr, jint vtableId) {
    if (vtablePtr == 0) return nullptr;

    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    if (vtableId < 0 || (size_t)vtableId >= vt->entries.size()) return nullptr;
    auto& entry = vt->entries[vtableId];
    if (!entry) return nullptr;

    try {
        auto& irt = *(facebook::jsi::IRuntime*)vt->rt;
        facebook::jsi::Value result = entry->call(irt, nullptr, 0);
        if (result.isString()) {
            std::string s = result.getString(*vt->rt).utf8(*vt->rt);
            return env->NewStringUTF(s.c_str());
        }
    } catch (const std::exception& e) {
        LOGI("rdmaVtableDispatch error: %s", e.what());
    }
    return nullptr;
}
