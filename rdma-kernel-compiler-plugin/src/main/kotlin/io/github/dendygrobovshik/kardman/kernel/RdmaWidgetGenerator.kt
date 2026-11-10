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
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import java.io.OutputStream

/**
 * Generates the typed, per-widget host bridge (Variant A):
 *  - `RdmaWidgetEntries.kt`  — one `@Composable` entry per widget (runs in `rdma-runtime`),
 *    marshaling content/callback lambdas via `RdmaComposeHost.nativeInvokeScopeBlock`/`nativeInvokeCallback`.
 *  - `RdmaWidgetBridge.h/cpp` — one JSI `HostFunction` per widget (`RDMA.composeXxx`) + JNI cache.
 *
 * Defaulted trailing parameters are supported via the "resolved-vals" scheme: the entry takes the
 * raw parameters as `_p0.._pN` plus a trailing `mask: Int`, and re-materializes each value with an
 * ordered `val <name> = if (mask and (1 shl i) != 0) (<default>) else <raw>` declaration before
 * delegating. The C++ host function computes the mask from `args[i].isUndefined()`.
 *
 * This replaces the hand-written `rdmaDispatch` string dispatch and the hand-written JS proxies.
 */
class RdmaWidgetGenerator(
    private val cppOutput: (String, String) -> OutputStream,
    private val kotlinOutput: (String, String) -> OutputStream,
    kernelPackage: String = "com.example.kernel",
    moduleId: String = "",
) {

    // Package of the generated host-side widget entries (RdmaWidgetEntries.kt).
    // This is user code, so it must not live in the framework runtime package.
    private val widgetEntriesPackage = "$kernelPackage.rdma"
    private val fileSuffix: String = if (moduleId.isEmpty()) "" else "_$moduleId"
    private val entriesFileBase = "RdmaWidgetEntries$fileSuffix"
    private val widgetEntriesClass = "$widgetEntriesPackage.${entriesFileBase}Kt"
    private val nsOpen: String = if (moduleId.isEmpty()) "" else "namespace $moduleId {\n"
    private val nsClose: String = if (moduleId.isEmpty()) "" else "} // namespace $moduleId\n"

    private sealed class Param {
        abstract val name: String
        abstract val hasDefault: Boolean
        abstract val defaultValue: String?

        data class Value(override val name: String, val jvmType: String, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Ref(override val name: String, val fqn: String, val nullable: Boolean, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Content(override val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Callback(override val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class ListVal(override val name: String, val elemFqn: String, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class ListHandle(override val name: String, val arity: Int, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
    }

    private fun simpleName(fqn: String): String = fqn.substringAfterLast('.')

    fun generate(widgets: List<RdmaFunctionInfo>) {
        generateKotlinEntries(widgets)
        generateCppHeader(widgets)
        generateCpp(widgets)
    }

    private fun classify(fn: RdmaFunctionInfo): List<Param> = fn.parameters.map { p ->
        val fnType = p.type.type as? RdmaType.FunctionType
        val listType = p.type.type as? RdmaType.ListType
        when {
            fnType != null && p.composable -> Param.Content(p.name, fnType.parameters.size, fnType.parameters, p.hasDefault, p.defaultValue)
            fnType != null -> Param.Callback(p.name, fnType.parameters.size, fnType.parameters, p.hasDefault, p.defaultValue)
            listType != null -> {
                val elem = listType.element.type
                if (elem is RdmaType.FunctionType) Param.ListHandle(p.name, elem.parameters.size, p.hasDefault, p.defaultValue)
                else Param.ListVal(p.name, valueFqn(elem), p.hasDefault, p.defaultValue)
            }
            p.type.type is RdmaType.Ref ->
                Param.Ref(p.name, (p.type.type as RdmaType.Ref).fqn, p.type.nullable, p.hasDefault, p.defaultValue)
            else -> Param.Value(p.name, valueFqn(p.type.type), p.hasDefault, p.defaultValue)
        }
    }

    private fun valueFqn(t: RdmaType): String = when (t) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        is RdmaType.ListType -> "list:" + valueFqn(t.element.type)
        else -> "kotlin.Any"
    }

    private fun refJniType(fqn: String): String = "L${fqn.replace('.', '/')};"

    /**
     * Number of trailing `int` "changed" parameters the Compose compiler appends to a
     * `@Composable` function: `ceil(paramCount / SLOTS_PER_INT)` (10 slots per int), with a minimum
     * of one for a parameter-less function. See `ComposableFunctionBodyTransformer.changedParamCount`.
     */
    private fun changedIntCount(paramCount: Int): Int =
        if (paramCount == 0) 1 else (paramCount + 9) / 10

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

    // Renders a recursive element descriptor (`list:list:kotlin.Int`) back into a
    // Kotlin type (`List<List<Int>>`).
    private fun listKotlinType(elemFqn: String): String =
        if (elemFqn.startsWith("list:")) "List<${listKotlinType(elemFqn.removePrefix("list:"))}>"
        else kotlinType(elemFqn)

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
        is Param.ListVal, is Param.ListHandle -> "Ljava/util/List;"
    }

    // ---------------------------------------------------------- Kotlin entries

    private fun generateKotlinEntries(widgets: List<RdmaFunctionInfo>) {
        val out = kotlinOutput("$entriesFileBase.kt", "$entriesFileBase.kt").bufferedWriter()
        out.write("""package $widgetEntriesPackage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
""")
        val imports = widgets.map { it.qualifiedName }.distinct().sorted()
        for (imp in imports) {
            out.write("import $imp\n")
        }
        val refFqns = widgets
            .flatMap { classify(it) }
            .flatMap { p ->
                when (p) {
                    is Param.Ref -> listOf(p.fqn)
                    is Param.Content -> p.paramTypes.flatMap(::refFqnsOf)
                    is Param.Callback -> p.paramTypes.flatMap(::refFqnsOf)
                    else -> emptyList()
                }
            }
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

    private fun refFqnsOf(t: RdmaTypeRef): List<String> = when (val ty = t.type) {
        is RdmaType.Ref -> listOf(ty.fqn)
        is RdmaType.ListType -> refFqnsOf(ty.element)
        is RdmaType.MapType -> refFqnsOf(ty.key) + refFqnsOf(ty.value)
        else -> emptyList()
    }

    private fun entry(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val hasDefaults = params.any { it.hasDefault }
        val entryName = "compose" + fn.name
        val sig = params.mapIndexed { i, p -> "${RdmaDefaultSupport.raw(i)}: ${rawKotlinType(p)}" }
            .plus(if (hasDefaults) listOf("mask: Int") else emptyList())
            .joinToString(", ")
        return buildString {
            appendLine("@Composable")
            appendLine("fun $entryName($sig) {")
            params.forEachIndexed { i, p -> append(resolvedDecl(p, i)) }
            appendLine("    ${fn.name}(")
            params.forEach { p -> appendLine("        ${p.name} = ${p.name},") }
            appendLine("    )")
            appendLine("}")
            appendLine()
        }
    }

    /** The raw (un-resolved) Kotlin type of a parameter as it appears in the entry signature. */
    private fun rawKotlinType(p: Param): String = when (p) {
        is Param.Value -> kotlinType(p.jvmType)
        is Param.Ref -> "${simpleName(p.fqn)}${if (p.nullable) "?" else ""}"
        is Param.Content, is Param.Callback -> "Long" // block id
        is Param.ListVal -> "List<${listKotlinType(p.elemFqn)}>"
        is Param.ListHandle -> "List<Long>" // block ids
    }

    /** The Kotlin type of a parameter's resolved value (default applied). */
    private fun resolvedType(p: Param): String = when (p) {
        is Param.Value -> kotlinType(p.jvmType)
        is Param.Ref -> "${simpleName(p.fqn)}${if (p.nullable) "?" else ""}"
        is Param.Content -> "@Composable ${lambdaType(p.arity, p.paramTypes)}"
        is Param.Callback -> lambdaType(p.arity, p.paramTypes)
        is Param.ListVal -> "List<${listKotlinType(p.elemFqn)}>"
        is Param.ListHandle -> "List<() -> Unit>"
    }

    private fun lambdaType(arity: Int, paramTypes: List<RdmaTypeRef>): String {
        val params = paramTypes.joinToString(", ") { rdmaTypeKotlin(it) }
        return if (params.isEmpty()) "() -> Unit" else "($params) -> Unit"
    }

    private fun rdmaTypeKotlin(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.Primitive -> kotlinType(ty.fqn)
        is RdmaType.Ref -> simpleName(ty.fqn)
        is RdmaType.ListType -> "List<${rdmaTypeKotlin(ty.element)}>"
        is RdmaType.MapType -> "Map<${rdmaTypeKotlin(ty.key)}, ${rdmaTypeKotlin(ty.value)}>"
        is RdmaType.FunctionType -> lambdaType(ty.parameters.size, ty.parameters)
        is RdmaType.UnitType -> "Unit"
    }

    private fun resolvedDecl(p: Param, i: Int): String {
        // A `@Composable` lambda default cannot be expressed as an `if`-expression branch (Kotlin
        // does not propagate the `@Composable` expected type into branches). Use definite assignment
        // so each `content = ...` assignment coerces against the declared `@Composable` type.
        if (p is Param.Content && p.hasDefault && p.defaultValue != null) {
            val converted = when (p.arity) {
                0 -> "{ RdmaComposeHost.nativeInvokeScopeBlock(${RdmaDefaultSupport.raw(i)}, currentComposer, 0) }"
                1 -> "{ p0 -> RdmaComposeHost.nativeInvokeScopeBlock1(${RdmaDefaultSupport.raw(i)}, currentComposer, 0, p0) }"
                else -> error("Content lambda arity ${p.arity} is not supported (only 0 or 1)")
            }
            return "    val ${p.name}: ${resolvedType(p)}\n" +
                "    if (${RdmaDefaultSupport.omitted(i)}) {\n" +
                "        ${p.name} = {}\n" +
                "    } else {\n" +
                "        ${p.name} = $converted\n" +
                "    }\n"
        }
        return "    val ${p.name}: ${resolvedType(p)} = ${resolvedExpr(p, i)}\n"
    }

    private fun resolvedExpr(p: Param, i: Int): String {
        val raw = RdmaDefaultSupport.raw(i)
        val converted = when (p) {
            is Param.Value, is Param.Ref, is Param.ListVal -> raw
            is Param.Content -> when (p.arity) {
                0 -> "{ RdmaComposeHost.nativeInvokeScopeBlock($raw, currentComposer, 0) }"
                1 -> "{ p0 -> RdmaComposeHost.nativeInvokeScopeBlock1($raw, currentComposer, 0, p0) }"
                else -> error("Content lambda arity ${p.arity} is not supported (only 0 or 1)")
            }
            is Param.Callback -> if (p.arity == 0) {
                "{ RdmaComposeHost.nativeInvokeCallback($raw, emptyArray<Any?>()) }"
            } else {
                val lambdaParams = (0 until p.arity).joinToString(", ") { "p$it" }
                val array = (0 until p.arity).joinToString(", ") { "p$it" }
                "{ $lambdaParams -> RdmaComposeHost.nativeInvokeCallback($raw, arrayOf<Any?>($array)) }"
            }
            is Param.ListHandle -> when (p.arity) {
                0 -> "$raw.map { { RdmaComposeHost.nativeInvokeCallback(it, emptyArray<Any?>()) } }"
                else -> error("List callback arity ${p.arity} is not supported (only 0)")
            }
        }
        val d = p.defaultValue
        return if (p.hasDefault && d != null) {
            "if (${RdmaDefaultSupport.omitted(i)}) (${RdmaDefaultSupport.rewriteThis(d)}) else $converted"
        } else {
            converted
        }
    }

    // ---------------------------------------------------------------- C++ glue

    private fun generateCppHeader(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge$fileSuffix.h", "RdmaWidgetBridge$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <jni.h>
#include <jsi/jsi.h>

namespace facebook {
namespace rdma {
$nsOpen
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

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateCpp(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge$fileSuffix.cpp", "RdmaWidgetBridge$fileSuffix.cpp").bufferedWriter()
        out.write("""#include "RdmaWidgetBridge$fileSuffix.h"
#include "RdmaCompose.h"
#include "ListHandle.h"

#include <string>
#include <memory>

namespace facebook {
namespace rdma {
$nsOpen
WidgetJniCache g_widgetCache;

void initWidgetJniCache(JNIEnv* env) {
""")
        if (widgets.isNotEmpty()) {
            out.write("""    jclass local = env->FindClass("${widgetEntriesClass.replace('.', '/')}");
    g_widgetCache.entriesClass = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
""")
        }
        for (fn in widgets) {
            val params = classify(fn)
            val hasDefaults = params.any { it.hasDefault }
            val sig = params.joinToString("") { it.jniType() } +
                (if (hasDefaults) "I" else "") +
                "Landroidx/compose/runtime/Composer;" +
                changedJniSuffix(if (hasDefaults) params.size + 1 else params.size)
            out.write("    g_widgetCache.compose${fn.name} = env->GetStaticMethodID(g_widgetCache.entriesClass, \"compose${fn.name}\", \"(${sig})V\");\n")
        }
        out.write("""}

void installRdmaWidgetBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma) {
""")
        for (fn in widgets) {
            out.write(hostFunction(fn))
        }
        out.write("""}

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun hostFunction(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val hasDefaults = params.any { it.hasDefault }
        val jsName = "compose" + fn.name
        val arity = params.size
        val extractions = params.mapIndexed { i, p -> extraction(i, p) }.joinToString("")
        val captures = params.mapIndexed { i, p -> captureExpr(i, p) }.joinToString(", ")
        val captureList = buildString {
            if (captures.isNotEmpty()) append(", ").append(captures)
            if (hasDefaults) append(", mask")
        }
        val callArgs = buildList {
            addAll(params.mapIndexed { i, p -> callArgExpr(i, p) })
            if (hasDefaults) add("mask")
        }.joinToString(", ")
        val cleanups = params.mapIndexedNotNull { i, p ->
            when {
                p is Param.Value && p.jvmType == "kotlin.String" -> "        if (j_p${i}g) e->DeleteGlobalRef(j_p${i}g);\n"
                p is Param.ListVal || p is Param.ListHandle -> "        if (cpp_p$i) e->DeleteGlobalRef(cpp_p$i);\n"
                else -> null
            }
        }.joinToString("")
        val changedArgs = changedCallArgs(if (hasDefaults) arity + 1 else arity)
        return """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsName"), $arity,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                JNIEnv* e = getEnv(jvm);
                if (!e) return jsi::Value::undefined();
${if (hasDefaults) "                int mask = 0;\n" else ""}$extractions
                jobject composer = g_currentComposer;
                RdmaResult result = rdmaCallUi([jvm, composer$captureList]() -> RdmaResult {
                    JNIEnv* e = getEnv(jvm);
                    if (!e) return RdmaResult::undefined();
                    e->CallStaticVoidMethod(g_widgetCache.entriesClass, g_widgetCache.$jsName, $callArgs, composer, $changedArgs);
                    RdmaResult err;
                    if (captureJniError(e, err)) return err;
$cleanups
                    return RdmaResult::undefined();
                });
                if (result.kind == RdmaResultKind::Error) return rdmaResultToJsi(r, result);
                return jsi::Value::undefined();
            });
        rdma.setProperty(rt, "$jsName", std::move(fn));
    }
"""
    }

    private fun captureExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "j_p${i}g" else "cpp_p$i"
        is Param.Ref -> "cpp_p$i"
        is Param.Content, is Param.Callback -> "cpp_p$i"
        is Param.ListVal, is Param.ListHandle -> "cpp_p$i"
    }

    private fun callArgExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "j_p${i}g" else "cpp_p$i"
        is Param.Ref -> "cpp_p$i"
        is Param.Content, is Param.Callback -> "cpp_p$i"
        is Param.ListVal, is Param.ListHandle -> "cpp_p$i"
    }

    private fun extraction(i: Int, p: Param): String {
        val guarded = p.hasDefault
        fun maskLine() = if (guarded) "                if (args[$i].isUndefined()) mask |= (1 << $i);\n" else ""
        fun notUndef() = if (guarded) "!args[$i].isUndefined() && " else ""
        return when (p) {
            is Param.Value -> when (p.jvmType) {
                "kotlin.String" ->
                    "                jstring j_p$i = nullptr;\n" +
                        "                if (${notUndef()}count > $i && args[$i].isString()) {\n" +
                        "                    j_p$i = e->NewStringUTF(args[$i].getString(r).utf8(r).c_str());\n" +
                        "                }\n" +
                        "                jstring j_p${i}g = j_p$i ? (jstring)e->NewGlobalRef(j_p$i) : nullptr;\n" +
                        "                if (j_p$i) e->DeleteLocalRef(j_p$i);\n" +
                        maskLine()
                "kotlin.Int" ->
                    "                jint cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (jint)args[$i].getNumber() : 0;\n" +
                        maskLine()
                "kotlin.Long" ->
                    "                jlong cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (jlong)args[$i].getNumber() : 0;\n" +
                        maskLine()
                "kotlin.Boolean" ->
                    "                jboolean cpp_p$i = count > $i && ${notUndef()}args[$i].isBool() ? args[$i].getBool() : false;\n" +
                        maskLine()
                "kotlin.Double", "kotlin.Float" ->
                    "                jdouble cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? args[$i].getNumber() : 0.0;\n" +
                        maskLine()
                else ->
                    "                jobject cpp_p$i = nullptr;\n"
            }
            is Param.Ref ->
                "                jobject cpp_p$i = nullptr;\n" +
                    "                if (${notUndef()}count > $i && args[$i].isObject() && args[$i].asObject(r).hasNativeState(r)) {\n" +
                    "                    auto argObj_$i = args[$i].asObject(r);\n" +
                    "                    auto argState_$i = std::static_pointer_cast<RdmaObjectNativeState>(argObj_$i.getNativeState(r));\n" +
                    "                    if (argState_$i) cpp_p$i = argState_$i->getObject();\n" +
                    "                }\n" +
                    maskLine()
            is Param.Content, is Param.Callback ->
                "                jlong cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (jlong)args[$i].getNumber() : 0;\n" +
                    maskLine()
            is Param.ListVal, is Param.ListHandle -> {
                val elemType = if (p is Param.ListVal) p.elemFqn else "kotlin.Long"
                "                jobject cpp_p$i = nullptr;\n" +
                    "                if (${notUndef()}count > $i && args[$i].isObject()) {\n" +
                    "                    auto listObj_$i = args[$i].asObject(r);\n" +
                    "                    cpp_p$i = materializeArray(e, r, jvm, listObj_$i, \"$elemType\");\n" +
                    "                }\n" +
                    maskLine()
            }
        }
    }
}
