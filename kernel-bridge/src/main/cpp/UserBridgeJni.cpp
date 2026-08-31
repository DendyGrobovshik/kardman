#include <jni.h>
#include <string>
#include <android/log.h>

#include "RdmaBridge.h"
#include "RdmaCompose.h"
#include "RdmaVtable.h"

#define LOG_TAG "RdmaUserBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Registers the user's generated bridge (installUserBridge) with the generic
// runtime. The runtime invokes it at the end of installRdmaComposeBridge.
extern "C" JNIEXPORT void JNICALL
Java_com_example_kernel_UserBridge_nativeInstall(JNIEnv* env, jclass) {
    facebook::rdma::rdmaSetUserBridgeInstaller(&facebook::rdma::installUserBridge);
    LOGI("User bridge installer registered");
}

// Kernel-side vtable dispatch: called from Kotlin open methods of @RDMA classes.
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
