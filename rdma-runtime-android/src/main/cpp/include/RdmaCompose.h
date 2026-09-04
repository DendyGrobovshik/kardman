#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <memory>
#include <string>
#include <unordered_map>
#include <cstdint>

#include "RdmaRendezvous.h"

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

    // io.github.dendygrobovshik.kardman.runtime.RdmaFunction{0..3} (service-lambda
    // wrappers). Cached so the Hermes thread can create them without FindClass
    // (app classes are not visible on a native thread's system classloader).
    jclass rdmaFunctionClass[4] = {nullptr, nullptr, nullptr, nullptr};
    jmethodID rdmaFunctionCtor[4] = {nullptr, nullptr, nullptr, nullptr};
};

extern ComposeJniCache g_composeCache;

// Global ref to the currently-composing Composer. Set on the Hermes thread just
// before running content/scope blocks and read by the widget bridge (also on the
// Hermes thread) to capture the composer into a UI-bound compose op. Owned by the
// content/scope task (not by this global).
extern jobject g_currentComposer;

// Initializes all JNI caches required by the compose bridge, the composer proxy
// and the user bridge. MUST run on the UI thread (app-class FindClass requires
// the app classloader). Called from RdmaBridge.nativeInit before rdmaStart().
void initRdmaComposeJniCache(JNIEnv* env);

// Installs the RDMA JSI namespace (registerContent / setComposerEmpty /
// mutableStateOf / registerBlock) and invokes the user-bridge hook. JSI-only;
// runs on the Hermes thread. JNI caches must already be initialized.
void installRdmaComposeBridge(jsi::Runtime& rt, JavaVM* jvm);

// User-bridge hook: the user's generated code (compiled into a separate
// librdma_user.so) registers an installer that is invoked at the end of
// installRdmaComposeBridge to add createXxx / functions / statics / widgets.
typedef void (*UserBridgeInstaller)(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma);
extern "C" void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer);

// User-bridge JNI-cache hook: registers the user bridge's FindClass/GetMethodID
// initialization, invoked from initRdmaComposeJniCache() on the UI thread.
typedef void (*UserBridgeJniInit)(JNIEnv* env);
extern "C" void rdmaSetUserBridgeJniInit(UserBridgeJniInit init);

// Runs the registered content on the Hermes thread. The passed composer is a
// global ref owned by the caller (the content task); it is deleted by the caller.
void invokeRegisteredContent(jsi::Runtime& rt, jobject composerGlobal);

// Invokes a stored scope-update block on the Hermes thread. `composerGlobal` is
// a global ref owned by the caller (the scope task).
void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed);

// Converts a UI-thread RdmaResult into a jsi::Value on the Hermes thread.
jsi::Value rdmaResultToJsi(jsi::Runtime& rt, RdmaResult& result);

// --- Helpers shared with the generated RdmaComposerProxy.cpp -----------------

JNIEnv* getEnv(JavaVM* jvm);

jobject boxJsi(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);

jsi::Value unboxJni(JNIEnv* env, jsi::Runtime& rt, jobject o);

// Classifies a boxed JVM value into an RdmaResult descriptor. Runs on the UI
// thread. Handles Integer/Boolean/Double/Long/Float/String/null; else -> Null.
RdmaResult classifyBoxedValue(JNIEnv* env, jobject o);

// Creates an io.github.dendygrobovshik.kardman.runtime.RdmaFunction{arity} wrapping
// the given JS block id. Callable from any attached thread (uses cached jclass).
jobject createRdmaFunction(JNIEnv* env, int arity, jlong id);

jsi::Object makeStateProxy(jsi::Runtime& rt, jobject stateGlobal);

jobject stateProxyJObject(jsi::Runtime& rt, const jsi::Value& v);

// Takes ownership of a global ref to a ScopeUpdateScope.
jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scopeGlobal);

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

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeCallback(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args);

JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeLambda(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args);
}

} // namespace rdma
} // namespace facebook
