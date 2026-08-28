package io.github.dendygrobovshik.kardman.kernel

import java.io.OutputStream

/**
 * Generates `RdmaComposerProxy.h` / `RdmaComposerProxy.cpp`: the host-side `ComposerProxyHost`
 * covering the base protocol described by [RdmaComposerProtocol.baseProtocol].
 *
 * The generated proxy lives alongside the other generated glue in
 * `kernel/build/generated/rdma/cpp/` and is copied into the runtime by `copyGeneratedCpp`.
 * It relies on helpers exposed by the static `RdmaCompose.cpp` (declared in `RdmaCompose.h`).
 */
class RdmaComposerProxyGenerator(private val output: (String, String) -> OutputStream) {

    fun generate(methods: List<ComposerMethod>) {
        generateHeader(methods)
        generateCpp(methods)
    }

    private fun generateHeader(methods: List<ComposerMethod>) {
        val out = output("RdmaComposerProxy.h", "RdmaComposerProxy.h").bufferedWriter()
        out.write("""#pragma once
#include <jni.h>
#include <jsi/jsi.h>

namespace facebook {
namespace rdma {

struct ComposerProxyCache {
""")
        for (m in methods.filter { it.needsMethodId }) {
            out.write("    jmethodID ${m.jniName} = nullptr;\n")
        }
        out.write("""};

extern ComposerProxyCache g_composerProxyCache;

void initComposerProxyCache(JNIEnv* env);

jsi::Object makeComposerProxy(jsi::Runtime& rt, jobject composer);

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateCpp(methods: List<ComposerMethod>) {
        val out = output("RdmaComposerProxy.cpp", "RdmaComposerProxy.cpp").bufferedWriter()
        out.write("""#include "RdmaComposerProxy.h"
#include "RdmaCompose.h"

#include <memory>
#include <string>
#include <unordered_map>

namespace facebook {
namespace rdma {

ComposerProxyCache g_composerProxyCache;

void initComposerProxyCache(JNIEnv* env) {
""")
        for (m in methods.filter { it.needsMethodId }) {
            out.write("    g_composerProxyCache.${m.jniName} = env->GetMethodID(g_composeCache.composerClass, \"${m.jniName}\", \"${m.jniSignature}\");\n")
        }
        out.write("""}

class ComposerProxyHost : public jsi::HostObject, public std::enable_shared_from_this<ComposerProxyHost> {
public:
    explicit ComposerProxyHost(jobject composer) : composer_(composer) {}
    ~ComposerProxyHost() override {
        if (composer_ && g_composeCache.jvm) {
            JNIEnv* env = getEnv(g_composeCache.jvm);
            if (env) env->DeleteGlobalRef(composer_);
        }
    }

    jsi::Value get(jsi::Runtime& rt, const jsi::PropNameID& name) override {
        std::string n = name.utf8(rt);
""")
        for (m in methods) {
            out.write(handlerBlock(m))
        }
        out.write("""
        return jsi::Value::undefined();
    }

    jobject composer() const { return composer_; }

private:
    jobject composer_; // global ref
};

jsi::Object makeComposerProxy(jsi::Runtime& rt, jobject composer) {
    JNIEnv* env = getEnv(g_composeCache.jvm);
    jobject global = env->NewGlobalRef(composer);
    auto host = std::make_shared<ComposerProxyHost>(global);
    return jsi::Object::createFromHostObject(rt, host);
}

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun handlerBlock(m: ComposerMethod): String = buildString {
        appendLine("        if (n.rfind(\"${m.jsName}\", 0) == 0) {")
        append(handler(m))
        appendLine("        }")
    }

    private fun handler(m: ComposerMethod): String = when (m.returnKind) {
        ComposerReturnKind.NOOP -> noopHandler(m, "undefined")
        ComposerReturnKind.NOOP_NULL -> noopHandler(m, "null")
        ComposerReturnKind.VOID -> callHandler(m, "CallVoidMethod", "jsi::Value::undefined()")
        ComposerReturnKind.BOOLEAN -> callHandler(m, "CallBooleanMethod", "jsi::Value(false)")
        ComposerReturnKind.COMPOSER_SELF -> composerSelfHandler(m)
        ComposerReturnKind.SCOPE_UPDATE_SCOPE -> scopeHandler(m)
        ComposerReturnKind.CHANGED -> changedHandler()
        ComposerReturnKind.REMEMBERED_VALUE -> rememberedValueHandler()
        ComposerReturnKind.UPDATE_REMEMBERED_VALUE -> updateRememberedValueHandler()
    }

    private fun noopHandler(m: ComposerMethod, value: String): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    return jsi::Value::$value();
                });
"""

    private fun callHandler(m: ComposerMethod, jniCall: String, fallback: String): String {
        val paramExtract = m.params.mapIndexed { i, k -> paramExtraction(i, k) }.joinToString("")
        val paramCleanup = m.params.mapIndexedNotNull { i, k ->
            if (k == ComposerParamKind.OBJECT) "                    if (p$i) e->DeleteLocalRef(p$i);\n" else null
        }.joinToString("")
        val paramsCall = if (m.params.isEmpty()) "" else ", " + m.params.indices.joinToString(", ") { "p$it" }
        return """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return $fallback;
$paramExtract
                    ${if (jniCall == "CallVoidMethod") "e->CallVoidMethod(self->composer_, g_composerProxyCache.${m.jniName}$paramsCall);"
                        else "auto res = e->$jniCall(self->composer_, g_composerProxyCache.${m.jniName}$paramsCall);"}
$paramCleanup
                    ${if (jniCall == "CallVoidMethod") "return jsi::Value::undefined();"
                        else "return jsi::Value((bool)res);"}
                });
"""
    }

    private fun paramExtraction(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.INT ->
            "                    jint p$i = count > $i && args[$i].isNumber() ? (jint)args[$i].getNumber() : 0;\n"
        ComposerParamKind.BOOLEAN ->
            "                    jboolean p$i = count > $i && args[$i].isBool() ? args[$i].getBool() : false;\n"
        ComposerParamKind.OBJECT ->
            "                    jobject p$i = count > $i ? boxJsi(e, r, args[$i]) : nullptr;\n"
    }

    private fun composerSelfHandler(m: ComposerMethod): String {
        val paramExtract = m.params.mapIndexed { i, k -> paramExtraction(i, k) }.joinToString("")
        val paramCleanup = m.params.mapIndexedNotNull { i, k ->
            if (k == ComposerParamKind.OBJECT) "                    if (p$i) e->DeleteLocalRef(p$i);\n" else null
        }.joinToString("")
        val paramsCall = if (m.params.isEmpty()) "" else ", " + m.params.indices.joinToString(", ") { "p$it" }
        return """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
$paramExtract
                    jobject result = e->CallObjectMethod(self->composer_, g_composerProxyCache.${m.jniName}$paramsCall);
$paramCleanup
                    if (result) e->DeleteLocalRef(result); // ComposerImpl returns `this`
                    return jsi::Object::createFromHostObject(r, self);
                });
"""
    }

    private fun scopeHandler(m: ComposerMethod): String {
        val paramExtract = m.params.mapIndexed { i, k -> paramExtraction(i, k) }.joinToString("")
        val paramCleanup = m.params.mapIndexedNotNull { i, k ->
            if (k == ComposerParamKind.OBJECT) "                    if (p$i) e->DeleteLocalRef(p$i);\n" else null
        }.joinToString("")
        val paramsCall = if (m.params.isEmpty()) "" else ", " + m.params.indices.joinToString(", ") { "p$it" }
        return """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::null();
$paramExtract
                    jobject scope = e->CallObjectMethod(self->composer_, g_composerProxyCache.${m.jniName}$paramsCall);
$paramCleanup
                    if (!scope) return jsi::Value::null();
                    jsi::Object proxy = makeScopeUpdateScopeProxy(r, scope);
                    e->DeleteLocalRef(scope);
                    return proxy;
                });
"""
    }

    private fun changedHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "changed"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value(true);
                    // Box primitive keys so `equals` compares correctly; use an identity-unique
                    // holder for object keys (which we cannot faithfully marshal yet).
                    jobject arg = nullptr;
                    if (count > 0) {
                        if (args[0].isObject()) {
                            arg = e->NewObject(g_composeCache.objectClass, g_composeCache.objectCtor);
                        } else {
                            arg = boxJsi(e, r, args[0]);
                        }
                    }
                    jboolean res = e->CallBooleanMethod(self->composer_, g_composerProxyCache.changed, arg);
                    if (arg) e->DeleteLocalRef(arg);
                    return jsi::Value((bool)res);
                });
"""

    private fun rememberedValueHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "rememberedValue"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    jobject v = e->CallObjectMethod(self->composer_, g_composerProxyCache.rememberedValue);

                    jobject companion = e->GetStaticObjectField(g_composeCache.composerClass, g_composeCache.composerCompanionField);
                    jobject empty = e->CallObjectMethod(companion, g_composeCache.getEmpty);
                    e->DeleteLocalRef(companion);
                    bool isEmpty = e->IsSameObject(v, empty);
                    e->DeleteLocalRef(empty);
                    if (isEmpty) {
                        e->DeleteLocalRef(v);
                        return g_empty ? jsi::Value(r, *g_empty) : jsi::Value::undefined();
                    }
                    if (e->IsInstanceOf(v, g_composeCache.mutableStateClass)) {
                        jobject global = e->NewGlobalRef(v);
                        e->DeleteLocalRef(v);
                        return makeStateProxy(r, global);
                    }
                    if (e->IsInstanceOf(v, g_composeCache.jsValueHolderClass)) {
                        jlong id = e->CallLongMethod(v, g_composeCache.jsValueHolderGetId);
                        e->DeleteLocalRef(v);
                        auto it = g_jsValues.find(id);
                        if (it != g_jsValues.end()) {
                            return jsi::Value(r, *it->second);
                        }
                        return jsi::Value::undefined();
                    }
                    jsi::Value out = unboxJni(e, r, v);
                    e->DeleteLocalRef(v);
                    return out;
                });
"""

    private fun updateRememberedValueHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "updateRememberedValue"), 1,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    if (count < 1) return jsi::Value::undefined();
                    jobject stored = nullptr;
                    bool deleteStored = false;
                    jobject stateObj = stateProxyJObject(r, args[0]);
                    if (stateObj) {
                        stored = stateObj;
                    } else if (args[0].isObject()) {
                        auto obj = std::make_shared<jsi::Object>(args[0].asObject(r));
                        int64_t id = g_nextJsValueId++;
                        g_jsValues[id] = obj;
                        stored = e->NewObject(g_composeCache.jsValueHolderClass, g_composeCache.jsValueHolderCtor, (jlong)id);
                        deleteStored = true;
                    } else {
                        stored = boxJsi(e, r, args[0]);
                        deleteStored = true;
                    }
                    if (stored) {
                        e->CallVoidMethod(self->composer_, g_composerProxyCache.updateRememberedValue, stored);
                        if (deleteStored) e->DeleteLocalRef(stored);
                    }
                    return jsi::Value::undefined();
                });
"""
}
