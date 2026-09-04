#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jsi/jsi.h>

#include "RdmaRendezvous.h"
#include "RdmaCompose.h"

#define LOG_TAG "RdmaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
static AAssetManager* g_assetMgr = nullptr;

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeInit(JNIEnv* env, jclass, jobject assetManager) {
    env->GetJavaVM(&g_jvm);
    g_assetMgr = AAssetManager_fromJava(env, assetManager);

    // 1) Populate every JNI cache on the UI thread (app-class FindClass requires
    //    the app classloader, which is only available on Java-created threads).
    facebook::rdma::initRdmaComposeJniCache(env);

    // 2) Start the Hermes thread asynchronously. It creates the runtime + bridge
    //    and then blocks servicing the queue.
    facebook::rdma::rdmaStart(g_jvm);

    LOGI("RDMA runtime starting (async)");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeIsReady(JNIEnv* env, jclass) {
    return facebook::rdma::rdmaIsReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaBridge_nativeEvalAsset(JNIEnv* env, jclass, jstring assetPath) {
    if (!g_assetMgr) {
        LOGI("nativeEvalAsset: AssetManager not initialized");
        return;
    }
    const char* path = env->GetStringUTFChars(assetPath, nullptr);
    AAsset* asset = AAssetManager_open(g_assetMgr, path, AASSET_MODE_BUFFER);
    env->ReleaseStringUTFChars(assetPath, path);
    if (!asset) {
        LOGI("nativeEvalAsset: failed to open asset");
        return;
    }
    const void* data = AAsset_getBuffer(asset);
    off_t length = AAsset_getLength(asset);
    std::string code;
    if (data && length > 0) {
        code.assign(static_cast<const char*>(data), static_cast<size_t>(length));
    } else if (length > 0) {
        code.resize(static_cast<size_t>(length));
        int read = AAsset_read(asset, &code[0], static_cast<size_t>(length));
        if (read != length) {
            code.clear();
        }
    }
    AAsset_close(asset);

    if (!code.empty()) {
        // Async: enqueue the eval on the Hermes thread. `rdmaIsReady()` becomes
        // true only after all enqueued evals finish.
        facebook::rdma::rdmaEvalAsset(code);
    }
}
