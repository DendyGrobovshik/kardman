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
 * C-ABI variant of [RdmaWidgetGenerator] for the iOS backend. Emits:
 *  - `RdmaWidgetEntries_<mod>.kt` — the C-compatible widget entry STUBS (the body is
 *    injected as IR by [CAbiWidgetIrGenerator]), the content/callback lambda helper
 *    functions, and the `@EagerInitialization` + `staticCFunction` registration;
 *  - `RdmaWidgetBridge_<mod>.h/cpp` — JSI `HostFunction`s that call the entry through
 *    the function-pointer registry (no composer/changed crossing) inside `rdmaCallUi`.
 *
 * Defaulted trailing parameters use the "resolved-vals" scheme: the entry stub takes the raw
 * parameters as `_p0.._pN` plus a trailing `mask: Int`, re-materializes each value with an ordered
 * `val <name> = if (mask and (1 shl i) != 0) (<default>) else <converted>` declaration, then calls a
 * generated `_impl` function whose body [CAbiWidgetIrGenerator] fills with the widget call.
 */
class CAbiWidgetGenerator(
    private val cppOutput: (String, String) -> OutputStream,
    private val kotlinOutput: (String, String) -> OutputStream,
    kernelPackage: String = "com.example.kernel",
    moduleId: String = "",
) {

    private val widgetEntriesPackage = "$kernelPackage.rdma"
    private val fileSuffix: String = if (moduleId.isEmpty()) "" else "_$moduleId"
    private val entriesFileBase = "RdmaWidgetEntries$fileSuffix"
    private val prefix: String = CAbi.prefix(moduleId)
    private val nsOpen: String = if (moduleId.isEmpty()) "" else "namespace $moduleId {\n"
    private val nsClose: String = if (moduleId.isEmpty()) "" else "} // namespace $moduleId\n"

    private sealed class Param {
        abstract val name: String
        abstract val hasDefault: Boolean
        abstract val defaultValue: String?

        data class Value(override val name: String, val jvmType: String, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Ref(override val name: String, val fqn: String, val nullable: Boolean, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Content(override val name: String, val arity: Int, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class Callback(override val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class ListVal(override val name: String, val elemType: RdmaTypeRef, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()
        data class ListHandle(override val name: String, val arity: Int, override val hasDefault: Boolean = false, override val defaultValue: String? = null) : Param()

        val idName: String get() = when (this) {
            is Value -> name
            is Ref -> name
            is Content -> name + "Id"
            is Callback -> name + "Id"
            is ListVal -> name
            is ListHandle -> name
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
        val listType = p.type.type as? RdmaType.ListType
        when {
            fnType != null && p.composable -> Param.Content(p.name, fnType.parameters.size, p.hasDefault, p.defaultValue)
            fnType != null -> Param.Callback(p.name, fnType.parameters.size, fnType.parameters, p.hasDefault, p.defaultValue)
            listType != null -> {
                val elem = listType.element
                if (elem.type is RdmaType.FunctionType) Param.ListHandle(p.name, (elem.type as RdmaType.FunctionType).parameters.size, p.hasDefault, p.defaultValue)
                else Param.ListVal(p.name, elem, p.hasDefault, p.defaultValue)
            }
            p.type.type is RdmaType.Ref ->
                Param.Ref(p.name, (p.type.type as RdmaType.Ref).fqn, p.type.nullable, p.hasDefault, p.defaultValue)
            else -> Param.Value(p.name, valueFqn(p.type.type), p.hasDefault, p.defaultValue)
        }
    }

    private fun valueFqn(t: RdmaType): String = when (t) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        else -> "kotlin.Any"
    }

    private fun changedIntCount(paramCount: Int): Int =
        if (paramCount == 0) 1 else (paramCount + 9) / 10

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

    private fun refKotlinType(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.Primitive -> kotlinType(ty.fqn)
        is RdmaType.Ref -> ty.fqn
        is RdmaType.ListType -> "List<${refKotlinType(ty.element)}>"
        is RdmaType.MapType -> "Map<${refKotlinType(ty.key)}, ${refKotlinType(ty.value)}>"
        is RdmaType.FunctionType -> "kotlin.Function${ty.parameters.size}"
        is RdmaType.UnitType -> "Unit"
    }

    // ---------------------------------------------------------- Kotlin entries

    private fun generateKotlinEntries(widgets: List<RdmaFunctionInfo>) {
        val out = kotlinOutput("$entriesFileBase.kt", "$entriesFileBase.kt").bufferedWriter()
        out.write("""@file:Suppress("FunctionName", "unused")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class, kotlin.ExperimentalStdlibApi::class)

package $widgetEntriesPackage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost
import io.github.dendygrobovshik.kardman.runtime.rdmaRegisterFunction
import io.github.dendygrobovshik.kardman.runtime.rdmaToKString
import kotlin.native.EagerInitialization
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

private fun <T : Any> COpaquePointer.obj(): T = asStableRef<Any>().get() as T

""")
        val refFqns = widgets
            .flatMap { classify(it) }
            .flatMap { p ->
                when (p) {
                    is Param.Ref -> listOf(p.fqn)
                    is Param.Callback -> p.paramTypes.flatMap { t ->
                        when (val ty = t.type) {
                            is RdmaType.Ref -> listOf(ty.fqn)
                            else -> emptyList()
                        }
                    }
                    is Param.ListVal -> when (val ty = p.elemType.type) {
                        is RdmaType.Ref -> listOf(ty.fqn)
                        else -> emptyList()
                    }
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
            out.write(lambdaHelpers(fn))
        }
        for (fn in widgets) {
            out.write(stub(fn))
        }
        for (fn in widgets) {
            if (classify(fn).any { it.hasDefault }) out.write(implStub(fn))
        }
        out.write(registration(widgets))
        out.close()
    }

    private fun lambdaHelpers(fn: RdmaFunctionInfo): String {
        val cName = prefix + "compose" + fn.name
        return classify(fn).mapNotNull { p -> helper(cName, p) }.joinToString("")
    }

    private fun helper(cName: String, p: Param): String? = when (p) {
        is Param.Content -> {
            when (p.arity) {
                0 -> "fun ${cName}_${p.name}(${p.idName}: Long): @Composable () -> Unit = { " +
                    "RdmaComposeHost.nativeInvokeScopeBlock(${p.idName}, currentComposer, 0) }\n"
                1 -> "fun ${cName}_${p.name}(${p.idName}: Long): @Composable (Int) -> Unit = { p0 -> " +
                    "RdmaComposeHost.nativeInvokeScopeBlock1(${p.idName}, currentComposer, 0, p0) }\n"
                else -> error("Content lambda arity ${p.arity} is not supported (only 0 or 1)")
            }
        }
        is Param.Callback -> {
            val ret = if (p.arity == 0) "() -> Unit"
            else "(" + p.paramTypes.joinToString(", ") { refKotlinType(it) } + ") -> Unit"
            val lambda = if (p.arity == 0) {
                "{ RdmaComposeHost.nativeInvokeCallback(${p.idName}, emptyArray<Any?>()) }"
            } else {
                val params = (0 until p.arity).joinToString(", ") { "p$it" }
                val arr = (0 until p.arity).joinToString(", ") { "p$it" }
                "{ $params -> RdmaComposeHost.nativeInvokeCallback(${p.idName}, arrayOf<Any?>($arr)) }"
            }
            "fun ${cName}_${p.name}(${p.idName}: Long): $ret = $lambda\n"
        }
        is Param.ListVal -> {
            val elemKotlin = refKotlinType(p.elemType)
            "fun ${cName}_${p.name}(${p.name}: COpaquePointer): List<$elemKotlin> = ${p.name}.obj<List<$elemKotlin>>()\n"
        }
        is Param.ListHandle -> when (p.arity) {
            0 -> "fun ${cName}_${p.name}(${p.name}: COpaquePointer): List<() -> Unit> = ${p.name}.obj<List<Long>>().map { { RdmaComposeHost.nativeInvokeCallback(it, emptyArray<Any?>()) } }\n"
            else -> error("List callback arity ${p.arity} is not supported (only 0)")
        }
        else -> null
    }

    private fun stub(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val cName = prefix + "compose" + fn.name
        val hasDefaults = params.any { it.hasDefault }
        if (!hasDefaults) return "fun $cName(${params.joinToString(", ") { cParamSig(it) }}) {\n}\n"
        val sig = params.mapIndexed { i, p -> "${RdmaDefaultSupport.raw(i)}: ${cParamKotlinType(p)}" }
            .plus(listOf("mask: Int"))
            .joinToString(", ")
        val resolutions = StringBuilder()
        params.forEachIndexed { i, p ->
            val converted = convertExpr(p, RdmaDefaultSupport.raw(i), cName)
            val d = p.defaultValue
            if (p is Param.Content && p.hasDefault && d != null) {
                // Definite assignment so `{}` coerces against the declared `@Composable` type.
                resolutions.append("    val ${p.name}: ${resolvedType(p)}\n")
                resolutions.append("    if (${RdmaDefaultSupport.omitted(i)}) {\n")
                resolutions.append("        ${p.name} = {}\n")
                resolutions.append("    } else {\n")
                resolutions.append("        ${p.name} = $converted\n")
                resolutions.append("    }\n")
            } else {
                val resolved = if (p.hasDefault && d != null) {
                    "if (${RdmaDefaultSupport.omitted(i)}) (${RdmaDefaultSupport.rewriteThis(d)}) else $converted"
                } else {
                    converted
                }
                resolutions.append("    val ${p.name}: ${resolvedType(p)} = $resolved\n")
            }
        }
        val implArgs = params.joinToString(", ") { it.name }
        return "fun $cName($sig) {\n$resolutions    ${cName}_impl($implArgs)\n}\n"
    }

    private fun implStub(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val cName = prefix + "compose" + fn.name
        val sig = params.joinToString(", ") { "${it.name}: ${resolvedType(it)}" }
        return "fun ${cName}_impl($sig) {\n}\n"
    }

    private fun resolvedType(p: Param): String = when (p) {
        is Param.Value -> kotlinType(p.jvmType)
        is Param.Ref -> "${p.fqn}${if (p.nullable) "?" else ""}"
        is Param.Content -> if (p.arity == 0) "@Composable () -> Unit" else "@Composable (Int) -> Unit"
        is Param.Callback -> if (p.arity == 0) "() -> Unit"
        else "(" + p.paramTypes.joinToString(", ") { refKotlinType(it) } + ") -> Unit"
        is Param.ListVal -> "List<${refKotlinType(p.elemType)}>"
        is Param.ListHandle -> "List<() -> Unit>"
    }

    private fun convertExpr(p: Param, raw: String, cName: String): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "rdmaToKString($raw)" else raw
        is Param.Ref -> "$raw.obj<${p.fqn}>()"
        is Param.Content, is Param.Callback, is Param.ListVal, is Param.ListHandle -> "${cName}_${p.name}($raw)"
    }

    private fun registration(widgets: List<RdmaFunctionInfo>): String {
        if (widgets.isEmpty()) return ""
        val calls = widgets.joinToString("\n") { fn ->
            val cName = prefix + "compose" + fn.name
            "    rdmaRegisterFunction(\"$cName\", staticCFunction(::$cName))"
        }
        return "\n@EagerInitialization\nprivate val _rdma_widgets_register = run {\n$calls\n    Unit\n}\n"
    }

    private fun cParamSig(p: Param): String = when (p) {
        is Param.Value -> when (p.jvmType) {
            "kotlin.String" -> "${p.idName}: CPointer<ByteVar>?"
            else -> "${p.idName}: ${kotlinType(p.jvmType)}"
        }
        is Param.Ref -> "${p.idName}: COpaquePointer"
        is Param.Content, is Param.Callback -> "${p.idName}: Long"
        is Param.ListVal, is Param.ListHandle -> "${p.idName}: COpaquePointer"
    }

    private fun cParamKotlinType(p: Param): String = when (p) {
        is Param.Value -> when (p.jvmType) {
            "kotlin.String" -> "CPointer<ByteVar>?"
            else -> kotlinType(p.jvmType)
        }
        is Param.Ref -> "COpaquePointer"
        is Param.Content, is Param.Callback -> "Long"
        is Param.ListVal, is Param.ListHandle -> "COpaquePointer"
    }

    private fun cParamTypeOnly(p: Param): String = when (p) {
        is Param.Value -> when (p.jvmType) {
            "kotlin.String" -> "const char*"
            "kotlin.Int" -> "int32_t"
            "kotlin.Long" -> "int64_t"
            "kotlin.Boolean" -> "bool"
            "kotlin.Double", "kotlin.Float" -> "double"
            else -> "void*"
        }
        is Param.Ref -> "void*"
        is Param.Content, is Param.Callback -> "int64_t"
        is Param.ListVal, is Param.ListHandle -> "void*"
    }

    private fun cParamType(p: Param): String = "${cParamTypeOnly(p)} ${p.idName}"

    private fun cWrapper(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val hasDefaults = params.any { it.hasDefault }
        val cName = prefix + "compose" + fn.name
        val decl = params.joinToString(", ") { cParamType(it) } +
            (if (hasDefaults) (if (params.isNotEmpty()) ", " else "") + "int32_t mask" else "")
        val types = params.joinToString(", ") { cParamTypeOnly(it) } +
            (if (hasDefaults) (if (params.isNotEmpty()) ", " else "") + "int32_t" else "")
        val args = params.joinToString(", ") { it.idName } +
            (if (hasDefaults) (if (params.isNotEmpty()) ", " else "") + "mask" else "")
        return "inline void $cName($decl) { ((void (*)($types))rdma_lookupFunction(\"$cName\"))($args); }"
    }

    // ---------------------------------------------------------------- C++ glue

    private fun generateCppHeader(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge$fileSuffix.h", "RdmaWidgetBridge$fileSuffix.h").bufferedWriter()
        out.write("""#pragma once
#include <jsi/jsi.h>
#include <cstdint>
#include "RdmaComposeCAbi.h"

""")
        for (fn in widgets) {
            out.write(cWrapper(fn) + "\n")
        }
        out.write("""
namespace facebook {
namespace rdma {
$nsOpen
void installRdmaWidgetBridge(jsi::Runtime& rt, jsi::Object& rdma);

$nsClose} // namespace rdma
} // namespace facebook
""")
        out.close()
    }

    private fun generateCpp(widgets: List<RdmaFunctionInfo>) {
        val out = cppOutput("RdmaWidgetBridge$fileSuffix.cpp", "RdmaWidgetBridge$fileSuffix.cpp").bufferedWriter()
        out.write("""#include "RdmaWidgetBridge$fileSuffix.h"
#include "RdmaComposeCAbi.h"

#include <string>

namespace facebook {
namespace rdma {
$nsOpen
void* materializeList(jsi::Runtime& rt, jsi::Object& jsObj, const std::string& elementType);

void installRdmaWidgetBridge(jsi::Runtime& rt, jsi::Object& rdma) {
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
        val cName = prefix + jsName
        val arity = params.size
        val extractions = params.mapIndexed { i, p -> extraction(i, p) }.joinToString("")
        val captures = params.mapIndexed { i, p -> captureExpr(i, p) }.joinToString(", ")
        val captureList = buildString {
            if (captures.isNotEmpty()) append(captures)
            if (hasDefaults) append(if (captures.isEmpty()) "mask" else ", mask")
        }
        val callArgs = buildList {
            addAll(params.mapIndexed { i, p -> callArgExpr(i, p) })
            if (hasDefaults) add("mask")
        }.joinToString(", ")
        return """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsName"), $arity,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
${if (hasDefaults) "                int32_t mask = 0;\n" else ""}$extractions
                RdmaResult result = rdmaCallUi([$captureList]() -> RdmaResult {
                    $cName($callArgs);
                    RdmaResult err;
                    if (captureCAbiError(err)) return err;
                    return RdmaResult::undefined();
                });
                if (result.kind == RdmaResultKind::Error) return rdmaResultToJsiCAbi(r, result);
                return jsi::Value::undefined();
            });
        rdma.setProperty(rt, "$jsName", std::move(fn));
    }
"""
    }

    private fun captureExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> "cpp_p$i"
        is Param.Ref -> "cpp_p$i"
        is Param.Content, is Param.Callback -> "cpp_p$i"
        is Param.ListVal, is Param.ListHandle -> "cpp_p$i"
    }

    private fun callArgExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "cpp_p$i.c_str()" else "cpp_p$i"
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
                    "                std::string cpp_p$i = count > $i && ${notUndef()}args[$i].isString() ? args[$i].getString(r).utf8(r) : std::string();\n" +
                        maskLine()
                "kotlin.Int" ->
                    "                int32_t cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (int32_t)args[$i].getNumber() : 0;\n" +
                        maskLine()
                "kotlin.Long" ->
                    "                int64_t cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (int64_t)args[$i].getNumber() : 0;\n" +
                        maskLine()
                "kotlin.Boolean" ->
                    "                bool cpp_p$i = count > $i && ${notUndef()}args[$i].isBool() ? args[$i].getBool() : false;\n" +
                        maskLine()
                "kotlin.Double", "kotlin.Float" ->
                    "                double cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? args[$i].getNumber() : 0.0;\n" +
                        maskLine()
                else ->
                    "                void* cpp_p$i = nullptr;\n"
            }
            is Param.Ref ->
                "                void* cpp_p$i = nullptr;\n" +
                    "                if (${notUndef()}count > $i && args[$i].isObject() && args[$i].asObject(r).hasNativeState(r)) {\n" +
                    "                    auto argObj_$i = args[$i].asObject(r);\n" +
                    "                    auto argState_$i = std::static_pointer_cast<RdmaObjectNativeStateCAbi>(argObj_$i.getNativeState(r));\n" +
                    "                    if (argState_$i) cpp_p$i = argState_$i->handle();\n" +
                    "                }\n" +
                    maskLine()
            is Param.Content, is Param.Callback ->
                "                int64_t cpp_p$i = count > $i && ${notUndef()}args[$i].isNumber() ? (int64_t)args[$i].getNumber() : 0;\n" +
                    maskLine()
            is Param.ListVal, is Param.ListHandle -> {
                val elemType = if (p is Param.ListVal) elemTypeFqn(p.elemType) else "kotlin.Long"
                "                void* cpp_p$i = nullptr;\n" +
                    "                if (${notUndef()}count > $i && args[$i].isObject()) {\n" +
                    "                    auto listObj_$i = args[$i].asObject(r);\n" +
                    "                    cpp_p$i = materializeList(r, listObj_$i, \"$elemType\");\n" +
                    "                }\n" +
                    maskLine()
            }
        }
    }

    private fun elemTypeFqn(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.Primitive -> ty.fqn
        is RdmaType.Ref -> ty.fqn
        is RdmaType.ListType -> "list:" + elemTypeFqn(ty.element)
        else -> "kotlin.Any"
    }
}
