/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once
#include <jsi/jsi.h>
#include <jni.h>
#include <memory>
#include <string>
#include <unordered_map>
#include <cstdint>

#include "RdmaRendezvous.h"
#include "RdmaNativeState.h"
#include "RdmaPlatform.h"
#include "RdmaException.h"
#include "RdmaJsLong.h"
#include "RdmaJniUtil.h"
#include "RdmaBlocks.h"

namespace facebook {
namespace rdma {

// Cached JNI data for the compose bridge.
struct ComposeJniCache {
    JavaVM* jvm = nullptr;

    jclass composerClass = nullptr;

    jclass scopeUpdateScopeClass = nullptr;
    jmethodID updateScope = nullptr;

    jclass mutableStateClass = nullptr;
    jclass stateClass = nullptr;     // State: getValue works on mutable and derived states
    jmethodID stateGetValue = nullptr;
    jclass derivedStatesClass = nullptr; // RdmaDerivedStates
    jmethodID derivedStateCreate = nullptr;
    jmethodID stateSetValue = nullptr;

    jclass snapshotStateKt = nullptr;
    jmethodID mutableStateOf = nullptr;
    jmethodID structuralEqualityPolicy = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.RdmaStateFactory — snapshot
    // collections backing for `mutableStateListOf`/`mutableStateMapOf`
    // (docs/design/type_descriptors.md#state-semantics).
    jclass stateFactory = nullptr;
    jmethodID stateFactoryNewList = nullptr;
    jmethodID stateFactoryNewMap = nullptr;

    jclass composerCompanion = nullptr;
    jmethodID getEmpty = nullptr;
    jfieldID composerCompanionField = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.RdmaEffectsKt.sideEffect (framework
    // host for the plugin's SideEffect primitive).
    jclass effectsKt = nullptr;
    jmethodID sideEffect = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.RdmaEffectsKt.disposableEffect
    // (framework host for the plugin's DisposableEffect primitive). Returns a
    // boolean indicating whether the passed block id was adopted by a fresh
    // observer (used by the JSI bridge to release an unused block).
    jmethodID disposableEffect = nullptr;

    // The app's context ClassLoader, captured at cache init (a Java-created thread).
    // `FindClass` cannot see application classes on threads attached from native
    // code (they resolve with the boot loader — see RdmaJni.cpp), so call-time
    // lookups of app classes (e.g. `@RDMA` enum classes during descriptor
    // marshalling, docs/design/type_descriptors.md) go through `loadClass`.
    jobject appClassLoader = nullptr;
    jmethodID classLoaderLoadClass = nullptr;

    jclass function2Class = nullptr;
    jclass scopeBlockClass = nullptr;
    // RdmaComposeHost.onComponentsChanged (static): a component was (re)registered.
    jclass composeHostClass = nullptr;
    jmethodID onComponentsChanged = nullptr;
    // RdmaSuspend.cancel (static): the plugin cancelled a coroutine waiting for a kernel call.
    jclass suspendClass = nullptr;
    jmethodID suspendCancel = nullptr;
    // RdmaLambdaScopes (static): recompose scopes tracked by plugin composable lambdas.
    jclass lambdaScopesClass = nullptr;
    jmethodID lambdaScopesTrack = nullptr;
    jmethodID lambdaScopesInvalidate = nullptr;
    jmethodID scopeBlockCtor = nullptr;

    jclass objectClass = nullptr;
    jmethodID objectCtor = nullptr;

    jclass jsValueHolderClass = nullptr;
    jclass jsIdentityKeyClass = nullptr;
    jmethodID jsIdentityKeyCtor = nullptr;
    jmethodID jsValueHolderCtor = nullptr;
    jmethodID jsValueHolderGetId = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.RdmaException — the host-side
    // exception thrown when a plugin exception crosses the boundary.
    jclass rdmaExceptionClass = nullptr;
    jmethodID rdmaExceptionCtor = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.BundleLoadException — thrown from
    // nativeIsReady when a bundle failed to compile/load.
    jclass bundleLoadExceptionClass = nullptr;
    jmethodID bundleLoadExceptionCtor = nullptr;

    // io.github.dendygrobovshik.kardman.runtime.RdmaFunction{0..3} (service-lambda
    // wrappers). Cached so the Hermes thread can create them without FindClass
    // (app classes are not visible on a native thread's system classloader).
    jclass rdmaFunctionClass[4] = {nullptr, nullptr, nullptr, nullptr};
    jmethodID rdmaFunctionCtor[4] = {nullptr, nullptr, nullptr, nullptr};
    jmethodID rdmaFunctionBlockId[4] = {nullptr, nullptr, nullptr, nullptr};
    // kotlin.jvm.functions.Function{0..3}.invoke — calling a native lambda from JS.
    jclass functionClass[4] = {nullptr, nullptr, nullptr, nullptr};
    jmethodID functionInvoke[4] = {nullptr, nullptr, nullptr, nullptr};
};

extern ComposeJniCache g_composeCache;

// Global ref to the currently-composing Composer. Set on the Hermes thread just
// before running content/scope blocks and read by the widget bridge (also on the
// Hermes thread) to capture the composer into a UI-bound compose op. Owned by the
// content/scope task (not by this global).
extern jobject g_currentComposer;
// True while plugin composition runs on this thread (proxy read registration for
// state-backed handles, see ListHandle/MapHandle and docs/design/type_descriptors.md).
extern thread_local bool g_inComposition;

// Initializes all JNI caches required by the compose bridge, the composer proxy
// and the user bridge. MUST run on the UI thread (app-class FindClass requires
// the app classloader). Called from RdmaBridge.nativeInit before rdmaStart().
void initRdmaComposeJniCache(JNIEnv* env);

// Installs the RDMA JSI namespace (registerComponent / setComposerEmpty /
// mutableStateOf / registerBlock) and invokes the user-bridge hook. JSI-only;
// runs on the Hermes thread. JNI caches must already be initialized.
void installRdmaComposeBridge(jsi::Runtime& rt, HostContext ctx);

// User-bridge hook: the user's generated code (compiled into a separate
// librdma_user.so) registers an installer that is invoked at the end of
// installRdmaComposeBridge to add createXxx / functions / statics / widgets.
typedef void (*UserBridgeInstaller)(jsi::Runtime& rt, HostContext ctx, jsi::Object& rdma);
extern "C" void rdmaSetUserBridgeInstaller(UserBridgeInstaller installer);

// User-bridge JNI-cache hook: registers the user bridge's FindClass/GetMethodID
// initialization, invoked from initRdmaComposeJniCache() on the UI thread.
typedef void (*UserBridgeJniInit)(JNIEnv* env);
extern "C" void rdmaSetUserBridgeJniInit(UserBridgeJniInit init);

// User-bridge object-wrapper hook: wraps a JVM object (already a global ref) into
// a JSI proxy using the user's generated `create<X>Wrapper`. Returns `undefined`
// for objects it does not recognize. Used by the generic runtime to marshal
// @RDMA objects that cross the boundary in service-lambda arguments and List
// elements (the generic runtime only knows primitives/String via `unboxJni`).
typedef jsi::Value (*ObjectWrapper)(jsi::Runtime& rt, HostContext ctx, jobject obj);
extern "C" void rdmaSetObjectWrapper(ObjectWrapper wrapper);

// Wraps an arbitrary boxed JVM object into a jsi::Value: primitives/String via
// `unboxJni`, anything else via the registered object wrapper (if any).
jsi::Value wrapAny(jsi::Runtime& rt, HostContext ctx, jobject obj);

// Associates a suspend-completion id with the return type descriptor of the
// suspend function being invoked. Called by the generated `RDMA._<name>Async`
// host function (from librdma_user.so) before it starts the kernel suspend body.
// The descriptor is a leaf FQN, `list:<elem>`, or `map:<key>:<value>`.
void rdmaRegisterSuspendResultType(int64_t completionId, const std::string& typeDesc);

// Returns the current content registration version. Bumped by registerComponent on
// the Hermes thread; read by the host on the UI thread to detect content changes.
int getContentVersion();

// Invokes a stored scope-update block on the Hermes thread. `composerGlobal` is
// a global ref owned by the caller (the scope task).
void invokeScopeBlock(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed);

// Like `invokeScopeBlock`, but the guest block is invoked with a single leading
// user argument (`p1`, e.g. a lazy-list item index) before the composer proxy.
void invokeScopeBlock1(jsi::Runtime& rt, long blockId, jobject composerGlobal, jint changed, jint p1);

// Components (docs/internals/compose_protocol.md#components): composables a plugin
// registers by name. The registry holds the latest block per name; a host call site keeps
// the block it first rendered until it leaves the composition.
int64_t rdmaComponentBlock(const std::string& name);
// Runs component block `blockId` with kernel arguments converted by the block's descriptors.
void invokeComponent(jsi::Runtime& rt, int64_t blockId, jobject composerGlobal, jobjectArray args);

// Converts a UI-thread RdmaResult into a jsi::Value on the Hermes thread.
jsi::Value rdmaResultToJsi(jsi::Runtime& rt, RdmaResult& result);

// --- Helpers shared with the generated RdmaComposerProxy.cpp -----------------

JNIEnv* getEnv(JavaVM* jvm);

jobject boxJsi(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);

jsi::Value unboxJni(JNIEnv* env, jsi::Runtime& rt, jobject o);

// Classifies a boxed JVM value into an RdmaResult descriptor. Runs on the UI
// thread. Handles Integer/Boolean/Double/Long/Float/String/null; else -> Null.
RdmaResult classifyBoxedValue(JNIEnv* env, jobject o);

// Builds a host RdmaException (a local ref) from the description, recursively
// wrapping `cause`. Caller must DeleteLocalRef. Returns nullptr on JNI failure.
jthrowable makeRdmaException(JNIEnv* env, const RdmaExceptionInfo& info);

// Builds a host BundleLoadException wrapping the RdmaException for `info`.
jthrowable makeBundleLoadException(JNIEnv* env, const RdmaExceptionInfo& info);

// Host-side uncaught-exception handler (set via nativeSetUncaughtExceptionHandler):
// converts info to an RdmaException and invokes the registered Kotlin lambda.
extern jobject g_uncaughtHandlerRef;
extern jmethodID g_uncaughtHandlerInvoke;
void androidUncaughtHandler(const RdmaExceptionInfo& info, const std::string& pluginId);

// Serializes a host Throwable into the boundary description (Native -> JS, §5).
RdmaExceptionInfo fromJniThrowable(JNIEnv* env, jthrowable t);

// Strings cross JNI as UTF-16 (NewString / GetStringChars), not through JNI's modified UTF-8:
// supplementary characters (emoji) and U+0000 arrive intact.
// A JS string as a new local jstring.
jstring rdmaNewJString(JNIEnv* env, jsi::Runtime& rt, const jsi::String& s);
// A JS value as a jstring: nullptr for null/undefined (a nullable String).
jstring rdmaJsToJString(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);
// A JS value that must be a string (throws a JS TypeError otherwise).
jstring rdmaJsToJStringNonNull(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);
// A jstring as a JS value: null for nullptr.
jsi::Value rdmaJStringToJs(JNIEnv* env, jsi::Runtime& rt, jstring s);

// Throws a JS Error if the JNI env has a pending exception. Call on the Hermes
// thread immediately after a Call*Method into host code.
void checkAndThrowJs(JNIEnv* env, jsi::Runtime& rt);

// Strict boxing for one descriptor leaf (flags stripped): `Int`/`Long`/`Double`/
// `Float`/`Boolean`/`String` and `@RDMA` handles. Raises a cross-runtime error on
// a value that does not fit the leaf instead of coercing (state writes and
// collection element materialization share this path — docs/design/type_descriptors.md).
jobject rdmaBoxLeafValue(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v, const std::string& fqn);

// Boxes the enum entry name string into the concrete JVM enum via the enum class's
// `valueOf(String)`. `fqn` must be the enum class FQN (e.g. `com.example.HttpMethod`).
// Returns the enum entry as a new local reference the caller owns. Raises a
// cross-runtime error when `v` is not a string or `valueOf(String)` is not found.
// Used by state writes and collection element materialization (docs/design/types.md).
jobject rdmaBoxEnumValue(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v, const std::string& fqn);

// Captures a pending JNI exception into an Error result (returns false if none).
// For UI-thread compose ops returning their result through RdmaResult.
bool captureJniError(JNIEnv* e, RdmaResult& out);

// Creates an io.github.dendygrobovshik.kardman.runtime.RdmaFunction{arity} wrapping
// the given JS block id. Callable from any attached thread (uses cached jclass).
jobject createRdmaFunction(JNIEnv* env, int arity, jlong id);

// A kernel lambda handed to the plugin (a lambda-typed property, method or function
// result) as a JS function. A lambda that came from the plugin (`RdmaFunctionN`) returns
// the plugin's own JS function; any other lambda becomes a host function that converts
// its arguments by `paramDescs` (comma-separated) and its result by `resultDesc`, calls
// the lambda on the Hermes thread and owns a global ref released with the JS function.
jsi::Value rdmaWrapNativeFunction(jsi::Runtime& rt, JavaVM* jvm, jobject fn,
                                  const std::string& paramDescs, const std::string& resultDesc);

// State proxy over a kernel `SnapshotMutableState`, typed by the channel's type
// descriptor (docs/design/type_descriptors.md). Pre-descriptor plugin bundles call
// `RDMA.mutableStateOf` without a descriptor and keep their old Int/Boolean/String
// contract (`typeDesc` defaults to the runtime-dispatch `kotlin.Any`).
jsi::Object makeStateProxy(jsi::Runtime& rt, jobject stateGlobal, const std::string& typeDesc = "kotlin.Any", bool readOnly = false);

jobject stateProxyJObject(jsi::Runtime& rt, const jsi::Value& v);

// A Compose key for `changed`/effects: primitives boxed, kernel objects and states as
// themselves, other JS objects as their identity (RdmaIdentity.h). Local ref.
jobject rdmaBoxComposeKey(JNIEnv* env, jsi::Runtime& rt, const jsi::Value& v);

// Takes ownership of a global ref to a ScopeUpdateScope.
jsi::Object makeScopeUpdateScopeProxy(jsi::Runtime& rt, jobject scopeGlobal);

// `Composer.recomposeScope` for a plugin `ComposableLambdaImpl` (field report #50): the
// current scope, recorded used, as a proxy whose `invalidate` invalidates it — or `null`
// when this scope was already handed out and not invalidated since.
jsi::Value rdmaLambdaScope(jsi::Runtime& rt, jobject composer);

extern std::shared_ptr<jsi::Object> g_empty;
extern std::unordered_map<int64_t, std::shared_ptr<jsi::Object>> g_jsValues;
extern int64_t g_nextJsValueId;

// JNI entry points (declared for clarity; defined in RdmaCompose.cpp).
extern "C" {
JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeScopeBlock1(
    JNIEnv* env, jclass, jlong blockId, jobject composer, jint changed, jint p1);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeCallback(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args);

JNIEXPORT jobject JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeLambda(
    JNIEnv* env, jclass, jlong blockId, jobjectArray args);

JNIEXPORT jlong JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeEffectBody(
    JNIEnv* env, jclass, jlong blockId);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeDispose(
    JNIEnv* env, jclass, jlong resultId);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeFreeBlock(
    JNIEnv* env, jclass, jlong blockId);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContinuation(
    JNIEnv* env, jclass, jlong completionId, jobject value);

JNIEXPORT void JNICALL
Java_io_github_dendygrobovshik_kardman_runtime_RdmaComposeHost_nativeInvokeContinuationError(
    JNIEnv* env, jclass, jlong completionId, jthrowable throwable);
}

// Hotfix dispatch slots (docs/design/runtime_rework.md §6), called from the per-kernel-
// module JNI exports generated into the app's user bridge: a generated `RdmaSlotCell`
// registers itself by callable FQN; a native caller of a hotfixed callable invokes the
// JS implementation (returns `fallthrough` when the calling thread cannot run JS).
void rdmaSlotRegisterJni(JNIEnv* env, jobject cell, jstring fqn);
jobject rdmaSlotInvokeJni(JNIEnv* env, jlong blockId, jobjectArray args, jstring paramDescs,
                          jstring resultDesc, jobject fallthrough);
// Native call of a plugin override of an `open` method (the vtable), from any thread.
jobject rdmaVtableInvokeJni(JNIEnv* env, jlong vtablePtr, jint vtableId, jobjectArray args,
                            jstring paramDescs, jstring resultDesc, jobject fallthrough);

} // namespace rdma
} // namespace facebook
