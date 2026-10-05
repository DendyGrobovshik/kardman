#pragma once
#include <jni.h>
#include <jsi/jsi.h>

namespace facebook {
namespace rdma {

struct ComposerProxyCache {
    jmethodID startRestartGroup = nullptr;
    jmethodID endRestartGroup = nullptr;
    jmethodID startReplaceGroup = nullptr;
    jmethodID endReplaceGroup = nullptr;
    jmethodID startMovableGroup = nullptr;
    jmethodID endMovableGroup = nullptr;
    jmethodID startReusableGroup = nullptr;
    jmethodID endReusableGroup = nullptr;
    jmethodID skipCurrentGroup = nullptr;
    jmethodID skipToGroupEnd = nullptr;
    jmethodID rememberedValue = nullptr;
    jmethodID updateRememberedValue = nullptr;
    jmethodID changed = nullptr;
    jmethodID shouldExecute = nullptr;
};

extern ComposerProxyCache g_composerProxyCache;

void initComposerProxyCache(JNIEnv* env);

jsi::Object makeComposerProxy(jsi::Runtime& rt, jobject composer);

} // namespace rdma
} // namespace facebook
