#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <memory>
#include <string>
#include <unordered_map>
#include <cstdint>

namespace facebook {
namespace rdma {

// Cached JNI data for the compose bridge.
struct ComposeJniCache {
    JavaVM* jvm = nullptr;

    jclass composerClass = nullptr;

    jclass scopeUpdateScopeClass = nullptr;
    jmethodID updateScope = nullptr;

    jclass mutableStateClass = nullptr;
    jmethodID stateGetValue = nullptr;
    jmethodID stateSetValue = nullptr;

    jclass snapshotStateKt = nullptr;
    jmethodID mutableStateOf = nullptr;
    jmethodID structuralEqualityPolicy = nullptr;

    jclass composerCompanion = nullptr;
    jmethodID getEmpty = nullptr;
    jfieldID composerCompanionField = nullptr;

    jclass function2Class = nullptr;
    jclass scopeBlockClass = nullptr;
    jmethodID scopeBlockCtor = nullptr;

    jclass objectClass = nullptr;
    jmethodID objectCtor = nullptr;

    jclass jsValueHolderClass = nullptr;
    jmethodID jsValueHolderCtor = nullptr;
    jmethodID jsValueHolderGetId = nullptr;
};

extern ComposeJniCache g_composeCache;

// Global ref to the currently-composing Composer (set during content invocation).
extern jobject g_currentComposer;

// Installed into the RDMA namespace from initRdmaRuntime().
void installRdmaComposeBridge(jsi::Runtime& rt, JavaVM* jvm);

// Called from JNI (RdmaComposeHost / RdmaBridge) to run the registered content.
void invokeRegisteredContent(jsi::Runtime& rt, jobject composer);

// Called from JNI to invoke a stored scope-update block.
void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composer, jint changed);

// --- Helpers shared with the generated RdmaComposerProxy.cpp -----------------

JNIEnv* getEnv(JavaVM* jvm);

jobject boxJsi(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);

jsi::Value unboxJni(JNIEnv* env, jsi::Runtime& rt, jobject o);

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject state);

jobject stateProxyJObject(jsi::Runtime& rt, const jsi::Value& v);

jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scope);

extern std::shared_ptr<jsi::Object> g_empty;
extern std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
extern int64_t g_nextJsValueId;

// JNI entry points (declared for clarity; defined in RdmaCompose.cpp).
extern "C" {
JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContent(
    JNIEnv* env, jclass, jobject composer);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed);
}

} // namespace rdma
} // namespace facebook
