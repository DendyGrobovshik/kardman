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

        data class Value(override val name: String, val jvmType: String, override val hasDefault: Boolean = false, override val defaultValue: String? = null, val nullable: Boolean = false) : Param()
        data class Ref(override val name: String, val fqn: String, val nullable: Boolean, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Content(override val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Callback(override val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class ListVal(override val name: String, val elemFqn: String, override val hasDefault: Boolean = false, override val defaultValue: String? = null, val nullable: Boolean = false) : Param()
        data class ListHandle(override val name: String, val arity: Int, override val hasDefault: Boolean = false, override val defaultValue: String? = null, val nullable: Boolean = false) : Param()
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
                if (elem is RdmaType.FunctionType) Param.ListHandle(p.name, elem.parameters.size, p.hasDefault, p.defaultValue, p.type.nullable)
                else Param.ListVal(p.name, valueFqn(elem), p.hasDefault, p.defaultValue, p.type.nullable)
            }
            p.type.type is RdmaType.Ref ->
                Param.Ref(p.name, (p.type.type as RdmaType.Ref).fqn, p.type.nullable, p.hasDefault, p.defaultValue)
            else -> Param.Value(p.name, valueFqn(p.type.type), p.hasDefault, p.defaultValue, p.type.nullable)
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
    // Kotlin type (`List<List<Int>>`). An `@RDMA` element is referenced by its fully
    // qualified class name (no import needed), so `List<Banner>` keeps its real type
    // instead of degrading to `List<Any?>` (matching the C ABI generator).
    private fun listKotlinType(elemFqn: String): String =
        if (elemFqn.startsWith("list:")) "List<${listKotlinType(elemFqn.removePrefix("list:"))}>"
        else if (elemFqn.startsWith("kotlin.")) kotlinType(elemFqn)
        else elemFqn

    private fun jniType(jvmType: String): String = when (jvmType) {
        "kotlin.String" -> "Ljava/lang/String;"
        "kotlin.Int" -> "I"
        "kotlin.Long" -> "J"
        "kotlin.Boolean" -> "Z"
        "kotlin.Double" -> "D"
        "kotlin.Float" -> "F"
        else -> "Ljava/lang/Object;"
    }

    private fun isPrimitive(jvmType: String): Boolean =
        jvmType in setOf("kotlin.Int", "kotlin.Long", "kotlin.Boolean", "kotlin.Double", "kotlin.Float")

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
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberCallback0
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberCallback1
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberCallback2
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberCallback3
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberCallbackList
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberContent0
import io.github.dendygrobovshik.kardman.runtime.rdmaRememberContent1
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

    /**
     * The raw (un-resolved) Kotlin type of a parameter as it appears in the entry
     * signature. Raws are nullable: the host passes `null`/0 for an omitted
     * parameter and the mask selects the default (or asserts non-null below) —
     * the public widget's own parameter types are unchanged.
     */
    private fun rawKotlinType(p: Param): String = when (p) {
        // A primitive crosses JNI unboxed (`jniType` gives `I`/`J`/`Z`/`D`/`F`), so its
        // raw must be the primitive itself: an omitted argument arrives as 0 and the
        // mask selects the default.
        is Param.Value -> if (isPrimitive(p.jvmType)) kotlinType(p.jvmType) else "${kotlinType(p.jvmType)}?"
        is Param.Ref -> "${simpleName(p.fqn)}?"
        is Param.Content, is Param.Callback -> "Long" // block id
        is Param.ListVal -> "List<${listKotlinType(p.elemFqn)}>?"
        is Param.ListHandle -> "List<Long>?" // block ids
    }

    /** The Kotlin type of a parameter's resolved value (default applied). */
    private fun resolvedType(p: Param): String = when (p) {
        is Param.Value -> "${kotlinType(p.jvmType)}${if (p.nullable) "?" else ""}"
        is Param.Ref -> "${simpleName(p.fqn)}${if (p.nullable) "?" else ""}"
        is Param.Content -> "@Composable ${lambdaType(p.arity, p.paramTypes)}"
        is Param.Callback -> lambdaType(p.arity, p.paramTypes)
        is Param.ListVal -> "List<${listKotlinType(p.elemFqn)}>${if (p.nullable) "?" else ""}"
        is Param.ListHandle -> "List<() -> Unit>${if (p.nullable) "?" else ""}"
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
            val converted = contentExpr(p, RdmaDefaultSupport.raw(i))
            return "    val ${p.name}: ${resolvedType(p)}\n" +
                "    if (${RdmaDefaultSupport.omitted(i)}) {\n" +
                "        ${p.name} = {}\n" +
                "    } else {\n" +
                "        ${p.name} = $converted\n" +
                "    }\n"
        }
        return "    val ${p.name}: ${resolvedType(p)} = ${resolvedExpr(p, i)}\n"
    }

    private fun contentExpr(p: Param.Content, raw: String): String = when (p.arity) {
        0 -> "rdmaRememberContent0($raw)"
        1 -> "rdmaRememberContent1($raw)"
        else -> error("Content lambda arity ${p.arity} is not supported (only 0 or 1)")
    }

    private fun resolvedExpr(p: Param, i: Int): String {
        val raw = RdmaDefaultSupport.raw(i)
        // The raw is nullable (an omitted argument arrives as `null`); the resolved
        // value asserts non-null where the parameter type is non-null — a caller that
        // omits a value without a default gets a loud error instead of a null leak.
        val rawOrAssert = if (resolvedNullable(p) || (p is Param.Value && isPrimitive(p.jvmType))) raw else "$raw!!"
        val converted = when (p) {
            is Param.Value, is Param.Ref, is Param.ListVal -> rawOrAssert
            // Composition-owned block lambdas (docs/design/runtime_rework.md §4.4): the
            // holder is remembered by block id, so a stable plugin lambda yields the
            // same Kotlin lambda (the widget can be skipped) and a replaced one is
            // released at once.
            is Param.Content -> contentExpr(p, raw)
            is Param.Callback -> if (p.arity == 0) {
                "rdmaRememberCallback0($raw)"
            } else {
                if (p.arity > 3) error("Callback arity ${p.arity} is not supported (at most 3)")
                val typeArgs = p.paramTypes.joinToString(", ") { rdmaTypeKotlin(it) }
                "rdmaRememberCallback${p.arity}<$typeArgs>($raw, \"${RdmaCallbackDescs.joined(p.paramTypes)}\")"
            }
            is Param.ListHandle -> when (p.arity) {
                0 -> if (resolvedNullable(p)) "$raw?.let { rdmaRememberCallbackList(it) }" else "rdmaRememberCallbackList($raw!!)"
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

    private fun resolvedNullable(p: Param): Boolean = when (p) {
        is Param.Value -> p.nullable
        is Param.Ref -> p.nullable
        is Param.ListVal -> p.nullable
        is Param.ListHandle -> p.nullable
        is Param.Content, is Param.Callback -> false
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
        }.joinToString("") { "$it, " } // each argument carries its separator: a widget may have none
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
                    e->CallStaticVoidMethod(g_widgetCache.entriesClass, g_widgetCache.$jsName, ${callArgs}composer, $changedArgs);
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
                    "                jlong cpp_p$i = 0;\n" +
                        "                if (${notUndef()}count > $i && args[$i].isNumber()) cpp_p$i = (jlong)rdmaCheckedJLong(r, args[$i]); // guarded crossing\n" +
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
                val elemType = if (p is Param.ListVal) p.elemFqn else "rdma.BlockId" // block ids are plain JS numbers
                "                jobject cpp_p$i = nullptr;\n" +
                    "                if (${notUndef()}count > $i && args[$i].isObject()) {\n" +
                    "                    auto listObj_$i = args[$i].asObject(r);\n" +
                    "                    // materializeArray returns a local reference; the UI-thread op needs a\n" +
                    "                    // global one (released after the op).\n" +
                    "                    jobject local_$i = materializeArray(e, r, jvm, listObj_$i, \"$elemType\");\n" +
                    "                    if (local_$i) { cpp_p$i = e->NewGlobalRef(local_$i); e->DeleteLocalRef(local_$i); }\n" +
                    "                }\n" +
                    maskLine()
            }
        }
    }
}
