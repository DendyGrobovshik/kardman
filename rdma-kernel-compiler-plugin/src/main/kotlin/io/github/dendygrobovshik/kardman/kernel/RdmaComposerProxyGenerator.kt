package io.github.dendygrobovshik.kardman.kernel

import java.io.OutputStream

/**
 * Generates `RdmaComposerProxy.h` / `RdmaComposerProxy.cpp`: the host-side `ComposerProxyHost`
 * covering the base protocol described by [RdmaComposerProtocol.baseProtocol].
 *
 * The generated proxy lives alongside the other generated glue in
 * `kernel/build/generated/rdma/cpp/` and is copied into the runtime by `copyGeneratedCpp`.
 * It relies on helpers exposed by the static `RdmaCompose.cpp` (declared in `RdmaCompose.h`).
 *
 * Since the Hermes runtime lives on a dedicated thread, every `Composer` method is marshaled
 * to the UI thread via `rdmaCallUi`: the JS thread extracts/boxes arguments into global refs,
 * the UI thread performs the JNI call and returns an `RdmaResult` descriptor, and the JS thread
 * converts it back into a `jsi::Value` via `rdmaResultToJsi`.
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
        ComposerReturnKind.VOID -> voidHandler(m)
        ComposerReturnKind.BOOLEAN -> booleanHandler(m)
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

    // --- JS-thread argument extraction ---------------------------------------

    private fun extraction(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.INT ->
            "                    jint p$i = count > $i && args[$i].isNumber() ? (jint)args[$i].getNumber() : 0;\n"
        ComposerParamKind.BOOLEAN ->
            "                    jboolean p$i = count > $i && args[$i].isBool() ? args[$i].getBool() : false;\n"
        ComposerParamKind.OBJECT ->
            "                    jobject p$i = count > $i ? boxJsi(e, r, args[$i]) : nullptr;\n" +
                "                    jobject p${i}g = p$i ? e->NewGlobalRef(p$i) : nullptr;\n" +
                "                    if (p$i) e->DeleteLocalRef(p$i);\n"
    }

    private fun captureExpr(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.INT -> "p$i"
        ComposerParamKind.BOOLEAN -> "p$i"
        ComposerParamKind.OBJECT -> "p${i}g"
    }

    private fun callExpr(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.INT -> "p$i"
        ComposerParamKind.BOOLEAN -> "p$i"
        ComposerParamKind.OBJECT -> "p${i}g"
    }

    private fun cleanup(i: Int, k: ComposerParamKind): String = when (k) {
        ComposerParamKind.OBJECT -> "                    if (p${i}g) e->DeleteGlobalRef(p${i}g);\n"
        else -> ""
    }

    private fun paramExtract(m: ComposerMethod): String =
        m.params.mapIndexed { i, k -> extraction(i, k) }.joinToString("")

    private fun paramCleanup(m: ComposerMethod): String =
        m.params.mapIndexed { i, k -> cleanup(i, k) }.joinToString("")

    private fun captureList(m: ComposerMethod): String {
        val c = m.params.mapIndexed { i, k -> captureExpr(i, k) }.joinToString(", ")
        return if (c.isEmpty()) "" else ", $c"
    }

    private fun paramsCall(m: ComposerMethod): String =
        if (m.params.isEmpty()) "" else ", " + m.params.indices.joinToString(", ") { i -> callExpr(i, m.params[i]) }

    // --- Handlers ------------------------------------------------------------

    private fun voidHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
${paramExtract(m)}
                    rdmaCallUi([composer = self->composer_${captureList(m)}]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::undefined();
                        e->CallVoidMethod(composer, g_composerProxyCache.${m.jniName}${paramsCall(m)});
${paramCleanup(m)}
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
"""

    private fun booleanHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value(false);
${paramExtract(m)}
                    RdmaResult result = rdmaCallUi([composer = self->composer_${captureList(m)}]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::boolean(false);
                        jboolean res = e->CallBooleanMethod(composer, g_composerProxyCache.${m.jniName}${paramsCall(m)});
${paramCleanup(m)}
                        return RdmaResult::boolean((bool)res);
                    });
                    return rdmaResultToJsi(r, result);
                });
"""

    private fun composerSelfHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
${paramExtract(m)}
                    rdmaCallUi([composer = self->composer_${captureList(m)}]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::undefined();
                        jobject result = e->CallObjectMethod(composer, g_composerProxyCache.${m.jniName}${paramsCall(m)});
                        if (result) e->DeleteLocalRef(result);
${paramCleanup(m)}
                        return RdmaResult::undefined();
                    });
                    return jsi::Object::createFromHostObject(r, self);
                });
"""

    private fun scopeHandler(m: ComposerMethod): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "${m.jsName}"), ${m.arity},
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::null();
${paramExtract(m)}
                    RdmaResult result = rdmaCallUi([composer = self->composer_${captureList(m)}]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::null();
                        jobject scope = e->CallObjectMethod(composer, g_composerProxyCache.${m.jniName}${paramsCall(m)});
${paramCleanup(m)}
                        if (!scope) return RdmaResult::null();
                        jobject global = e->NewGlobalRef(scope);
                        e->DeleteLocalRef(scope);
                        return RdmaResult::scopeRef(global);
                    });
                    return rdmaResultToJsi(r, result);
                });
"""

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
                    jobject argG = arg ? e->NewGlobalRef(arg) : nullptr;
                    if (arg) e->DeleteLocalRef(arg);
                    RdmaResult result = rdmaCallUi([composer = self->composer_, argG]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::boolean(true);
                        jboolean res = e->CallBooleanMethod(composer, g_composerProxyCache.changed, argG);
                        if (argG) e->DeleteGlobalRef(argG);
                        return RdmaResult::boolean((bool)res);
                    });
                    return rdmaResultToJsi(r, result);
                });
"""

    private fun rememberedValueHandler(): String = """
            return jsi::Function::createFromHostFunction(
                rt, jsi::PropNameID::forAscii(rt, "rememberedValue"), 0,
                [self = shared_from_this()](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                    JNIEnv* e = getEnv(g_composeCache.jvm);
                    if (!e) return jsi::Value::undefined();
                    RdmaResult result = rdmaCallUi([composer = self->composer_]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::undefined();
                        jobject v = e->CallObjectMethod(composer, g_composerProxyCache.rememberedValue);

                        jobject companion = e->GetStaticObjectField(g_composeCache.composerClass, g_composeCache.composerCompanionField);
                        jobject empty = e->CallObjectMethod(companion, g_composeCache.getEmpty);
                        e->DeleteLocalRef(companion);
                        bool isEmpty = e->IsSameObject(v, empty);
                        e->DeleteLocalRef(empty);
                        if (isEmpty) {
                            if (v) e->DeleteLocalRef(v);
                            return RdmaResult::empty();
                        }
                        if (e->IsInstanceOf(v, g_composeCache.mutableStateClass)) {
                            jobject global = e->NewGlobalRef(v);
                            e->DeleteLocalRef(v);
                            return RdmaResult::stateRef(global);
                        }
                        if (e->IsInstanceOf(v, g_composeCache.jsValueHolderClass)) {
                            jlong id = e->CallLongMethod(v, g_composeCache.jsValueHolderGetId);
                            e->DeleteLocalRef(v);
                            return RdmaResult::jsValueId((int64_t)id);
                        }
                        RdmaResult r2 = classifyBoxedValue(e, v);
                        e->DeleteLocalRef(v);
                        return r2;
                    });
                    return rdmaResultToJsi(r, result);
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
                        jobject local = e->NewObject(g_composeCache.jsValueHolderClass, g_composeCache.jsValueHolderCtor, (jlong)id);
                        stored = e->NewGlobalRef(local);
                        e->DeleteLocalRef(local);
                        deleteStored = true;
                    } else {
                        jobject local = boxJsi(e, r, args[0]);
                        stored = local ? e->NewGlobalRef(local) : nullptr;
                        if (local) e->DeleteLocalRef(local);
                        deleteStored = true;
                    }
                    // Always call updateRememberedValue (even with null) so the slot is
                    // appended on the first composition. Skipping it for Unit/undefined
                    // would misalign the slot table on recomposition.
                    rdmaCallUi([composer = self->composer_, stored, deleteStored]() -> RdmaResult {
                        JNIEnv* e = getEnv(g_composeCache.jvm);
                        if (!e) return RdmaResult::undefined();
                        e->CallVoidMethod(composer, g_composerProxyCache.updateRememberedValue, stored);
                        if (deleteStored && stored) e->DeleteGlobalRef(stored);
                        return RdmaResult::undefined();
                    });
                    return jsi::Value::undefined();
                });
"""
}
