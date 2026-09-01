package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import java.io.OutputStream

/**
 * Generates the typed, per-widget host bridge (Variant A):
 *  - `RdmaWidgetEntries.kt`  — one `@Composable` entry per widget (runs in `rdma-runtime-android`),
 *    marshaling content/callback lambdas via `RdmaComposeHost.nativeInvokeScopeBlock`/`nativeInvokeCallback`.
 *  - `RdmaWidgetBridge.h/cpp` — one JSI `HostFunction` per widget (`RDMA.composeXxx`) + JNI cache.
 *
 * This replaces the hand-written `rdmaDispatch` string dispatch and the hand-written JS proxies.
 */
class RdmaWidgetGenerator(
    private val cppOutput: (String, String) -> OutputStream,
    private val kotlinOutput: (String, String) -> OutputStream,
) {

    companion object {
        // Package of the generated host-side widget entries (RdmaWidgetEntries.kt).
        // This is user code, so it must not live in the framework runtime package.
        const val WIDGET_ENTRIES_PACKAGE = "com.example.kernel.rdma"
        const val WIDGET_ENTRIES_CLASS = "com.example.kernel.rdma.RdmaWidgetEntriesKt"
    }

    private sealed class Param {
        data class Value(val name: String, val jvmType: String) : Param()
        data class Ref(val name: String, val fqn: String, val nullable: Boolean) : Param()
        data class Content(val name: String) : Param()
        data class Callback(val name: String, val arity: Int) : Param()

        val idName: String get() = when (this) {
            is Value -> name
            is Ref -> name
            is Content -> name + "Id"
            is Callback -> name + "Id"
        }
    }

    private fun simpleName(fqn: String): String = fqn.substringAfterLast('.')

    fun generate(widgets: List<RdmaFunctionInfo>) {
        generateKotlinEntries(widgets)
        generateCppHeader(widgets)
        generateCpp(widgets)
    }

    private fun classify(fn: RdmaFunctionInfo): List<Param> = fn.parameters.map { p ->
        val fnType = p.type.type as? RdmaType.FunctionType
        when {
            fnType != null && p.composable -> Param.Content(p.name)
            fnType != null -> Param.Callback(p.name, fnType.parameters.size)
            p.type.type is RdmaType.Ref ->
                Param.Ref(p.name, (p.type.type as RdmaType.Ref).fqn, p.type.nullable)
            else -> Param.Value(p.name, valueFqn(p.type.type))
        }
    }

    private fun valueFqn(t: RdmaType): String = when (t) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        else -> "kotlin.Any"
    }

    private fun refJniType(fqn: String): String = "L${fqn.replace('.', '/')};"

    /**
     * Number of trailing `int` "changed" parameters the Compose compiler appends to a
     * `@Composable` function. The compiler emits `paramCount / 16 + 1` such ints for a
     * top-level (static) function (each int encodes 16 parameter change bits).
     */
    private fun changedIntCount(paramCount: Int): Int = paramCount / 16 + 1

    private fun changedJniSuffix(paramCount: Int): String = "I".repeat(changedIntCount(paramCount))

    private fun changedCallArgs(paramCount: Int): String =
        (0 until changedIntCount(paramCount)).joinToString(", ") { "0" }

    private fun kotlinType(jvmType: String): String = when (jvmType) {
        "kotlin.String" -> "String"
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.Double" -> "Double"
        "kotlin.Float" -> "Float"
        "kotlin.Unit" -> "Unit"
        else -> "Any?"
    }

    private fun jniType(jvmType: String): String = when (jvmType) {
        "kotlin.String" -> "Ljava/lang/String;"
        "kotlin.Int" -> "I"
        "kotlin.Long" -> "J"
        "kotlin.Boolean" -> "Z"
        "kotlin.Double" -> "D"
        "kotlin.Float" -> "F"
        else -> "Ljava/lang/Object;"
    }

    private fun Param.jniType(): String = when (this) {
        is Param.Value -> jniType(jvmType)
        is Param.Ref -> refJniType(fqn)
        is Param.Content, is Param.Callback -> "J" // block id is a Long
    }

    // ---------------------------------------------------------- Kotlin entries

    private fun generateKotlinEntries(widgets: List<RdmaFunctionInfo>) {
        val out = kotlinOutput("RdmaWidgetEntries.kt", "RdmaWidgetEntries.kt").bufferedWriter()
        out.write("""package ${WIDGET_ENTRIES_PACKAGE}

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
""")
        val imports = widgets.map { it.qualifiedName }.distinct().sorted()
        for (imp in imports) {
            out.write("import $imp\n")
        }
        val refFqns = widgets
            .flatMap { classify(it).filterIsInstance<Param.Ref>().map { r -> r.fqn } }
            .distinct()
            .sorted()
        for (ref in refFqns) {
            out.write("import $ref\n")
        }
        out.write("\n")
        for (fn in widgets) {
            out.write(entry(fn))
        }
        out.close()
    }

    private fun entry(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val entryName = "compose" + fn.name
        val sig = params.joinToString(", ") { p ->
            when (p) {
                is Param.Value -> "${p.idName}: ${kotlinType(p.jvmType)}"
                is Param.Ref -> "${p.idName}: ${simpleName(p.fqn)}${if (p.nullable) "?" else ""}"
                is Param.Content, is Param.Callback -> "${p.idName}: Long"
            }
        }
        val args = params.joinToString(",\n        ") { p -> callArg(p) }
        return buildString {
            appendLine("@Composable")
            appendLine("fun $entryName($sig) {")
            appendLine("    ${fn.name}(")
            append("        $args,\n")
            appendLine("    )")
            appendLine("}")
            appendLine()
        }
    }

    private fun callArg(p: Param): String = when (p) {
        is Param.Value -> "${p.name} = ${p.idName}"
        is Param.Ref -> "${p.name} = ${p.idName}"
        is Param.Content ->
            "${p.name} = { RdmaComposeHost.nativeInvokeScopeBlock(${p.idName}, currentComposer, 0) }"
        is Param.Callback -> {
            if (p.arity == 0) {
                "${p.name} = { RdmaComposeHost.nativeInvokeCallback(${p.idName}, emptyArray<Any?>()) }"
            } else {
                val lambdaParams = (0 until p.arity).joinToString(", ") { "p$it" }
                val array = (0 until p.arity).joinToString(", ") { "p$it" }
                "${p.name} = { $lambdaParams -> RdmaComposeHost.nativeInvokeCallback(${p.idName}, arrayOf<Any?>($array)) }"
            }
        }
    }

    // ---------------------------------------------------------------- C++ glue

    private fun generateCppHeader(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge.h", "RdmaWidgetBridge.h").bufferedWriter()
        out.write("""#pragma once
#include <jni.h>
#include <jsi/jsi.h>

namespace facebook {
namespace rdma {

struct WidgetJniCache {
    jclass entriesClass = nullptr;
""")
        for (fn in widgets) {
            out.write("    jmethodID compose${fn.name} = nullptr;\n")
        }
        out.write("""};

extern WidgetJniCache g_widgetCache;

void initWidgetJniCache(JNIEnv* env);

void installRdmaWidgetBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma);

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateCpp(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge.cpp", "RdmaWidgetBridge.cpp").bufferedWriter()
        val refFqns = widgets
            .flatMap { classify(it).filterIsInstance<Param.Ref>().map { r -> r.fqn } }
            .distinct()
            .sorted()
        out.write("""#include "RdmaWidgetBridge.h"
#include "RdmaCompose.h"
""")
        for (ref in refFqns) {
            out.write("#include \"${simpleName(ref)}HostObject.h\"\n")
        }
        out.write("""
#include <string>
#include <memory>

namespace facebook {
namespace rdma {

WidgetJniCache g_widgetCache;

void initWidgetJniCache(JNIEnv* env) {
    jclass local = env->FindClass("${WIDGET_ENTRIES_CLASS.replace('.', '/')}");
    g_widgetCache.entriesClass = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
""")
        for (fn in widgets) {
            val params = classify(fn)
            val sig = params.joinToString("") { it.jniType() } +
                "Landroidx/compose/runtime/Composer;" + changedJniSuffix(params.size)
            out.write("    g_widgetCache.compose${fn.name} = env->GetStaticMethodID(g_widgetCache.entriesClass, \"compose${fn.name}\", \"(${sig})V\");\n")
        }
        out.write("""}

void installRdmaWidgetBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma) {
""")
        for (fn in widgets) {
            out.write(hostFunction(fn))
        }
        out.write("""}

} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun hostFunction(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val jsName = "compose" + fn.name
        val arity = params.size
        val extractions = params.mapIndexed { i, p -> extraction(i, p) }.joinToString("")
        val callArgs = params.mapIndexed { i, p -> argExpr(i, p) }.joinToString(", ")
        val cleanups = params.mapIndexedNotNull { i, p ->
            if (p is Param.Value && p.jvmType == "kotlin.String") "    if (j_p$i) e->DeleteLocalRef(j_p$i);\n" else null
        }.joinToString("")
        val changedArgs = changedCallArgs(arity)
        return """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsName"), $arity,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                JNIEnv* e = getEnv(jvm);
                if (!e) return jsi::Value::undefined();
$extractions
                e->CallStaticVoidMethod(g_widgetCache.entriesClass, g_widgetCache.$jsName, $callArgs, g_currentComposer, $changedArgs);
$cleanups
                return jsi::Value::undefined();
            });
        rdma.setProperty(rt, "$jsName", std::move(fn));
    }
"""
    }

    private fun extraction(i: Int, p: Param): String = when (p) {
        is Param.Value -> when (p.jvmType) {
            "kotlin.String" ->
                "                jstring j_p$i = count > $i && args[$i].isString() ? e->NewStringUTF(args[$i].getString(r).utf8(r).c_str()) : nullptr;\n"
            "kotlin.Int" ->
                "                jint cpp_p$i = count > $i && args[$i].isNumber() ? (jint)args[$i].getNumber() : 0;\n"
            "kotlin.Long" ->
                "                jlong cpp_p$i = count > $i && args[$i].isNumber() ? (jlong)args[$i].getNumber() : 0;\n"
            "kotlin.Boolean" ->
                "                jboolean cpp_p$i = count > $i && args[$i].isBool() ? args[$i].getBool() : false;\n"
            "kotlin.Double", "kotlin.Float" ->
                "                jdouble cpp_p$i = count > $i && args[$i].isNumber() ? args[$i].getNumber() : 0.0;\n"
            else ->
                "                jobject cpp_p$i = nullptr;\n"
        }
        is Param.Ref ->
            "                jobject cpp_p$i = nullptr;\n" +
                "                if (count > $i && args[$i].isObject() && args[$i].asObject(r).hasNativeState(r)) {\n" +
                "                    auto argObj_$i = args[$i].asObject(r);\n" +
                "                    auto argState_$i = std::static_pointer_cast<${simpleName(p.fqn)}NativeState>(argObj_$i.getNativeState(r));\n" +
                "                    if (argState_$i) cpp_p$i = argState_$i->getObject();\n" +
                "                }\n"
        is Param.Content, is Param.Callback ->
            "                jlong cpp_p$i = count > $i && args[$i].isNumber() ? (jlong)args[$i].getNumber() : 0;\n"
    }

    private fun argExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "j_p$i" else "cpp_p$i"
        is Param.Ref -> "cpp_p$i"
        is Param.Content, is Param.Callback -> "cpp_p$i"
    }
}
