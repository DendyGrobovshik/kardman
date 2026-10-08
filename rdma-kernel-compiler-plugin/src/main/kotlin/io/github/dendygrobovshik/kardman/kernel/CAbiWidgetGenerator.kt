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
        data class Value(val name: String, val jvmType: String) : Param()
        data class Ref(val name: String, val fqn: String, val nullable: Boolean) : Param()
        data class Content(val name: String, val arity: Int) : Param()
        data class Callback(val name: String, val arity: Int, val paramTypes: List<RdmaTypeRef>) : Param()

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
            fnType != null && p.composable -> Param.Content(p.name, fnType.parameters.size)
            fnType != null -> Param.Callback(p.name, fnType.parameters.size, fnType.parameters)
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

    private fun changedIntCount(paramCount: Int): Int = paramCount / 16 + 1

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
import kotlin.native.EagerInitialization
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.staticCFunction

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
        else -> null
    }

    private fun stub(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val cName = prefix + "compose" + fn.name
        val sig = params.joinToString(", ") { cParamSig(it) }
        return "fun $cName($sig) {\n}\n"
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
    }

    private fun cParamType(p: Param): String = "${cParamTypeOnly(p)} ${p.idName}"

    private fun cWrapper(fn: RdmaFunctionInfo): String {
        val params = classify(fn)
        val cName = prefix + "compose" + fn.name
        val decl = params.joinToString(", ") { cParamType(it) }
        val types = params.joinToString(", ") { cParamTypeOnly(it) }
        val args = params.joinToString(", ") { it.idName }
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
        val jsName = "compose" + fn.name
        val cName = prefix + jsName
        val arity = params.size
        val extractions = params.mapIndexed { i, p -> extraction(i, p) }.joinToString("")
        val captures = params.mapIndexed { i, p -> captureExpr(i, p) }.joinToString(", ")
        val captureList = if (captures.isEmpty()) "" else ", $captures"
        val callArgs = params.mapIndexed { i, p -> callArgExpr(i, p) }.joinToString(", ")
        return """
    {
        auto fn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "$jsName"), $arity,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
$extractions
                rdmaCallUi([$captures]() -> RdmaResult {
                    $cName($callArgs);
                    return RdmaResult::undefined();
                });
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
    }

    private fun callArgExpr(i: Int, p: Param): String = when (p) {
        is Param.Value -> if (p.jvmType == "kotlin.String") "cpp_p$i.c_str()" else "cpp_p$i"
        is Param.Ref -> "cpp_p$i"
        is Param.Content, is Param.Callback -> "cpp_p$i"
    }

    private fun extraction(i: Int, p: Param): String = when (p) {
        is Param.Value -> when (p.jvmType) {
            "kotlin.String" ->
                "                std::string cpp_p$i = count > $i && args[$i].isString() ? args[$i].getString(r).utf8(r) : std::string();\n"
            "kotlin.Int" ->
                "                int32_t cpp_p$i = count > $i && args[$i].isNumber() ? (int32_t)args[$i].getNumber() : 0;\n"
            "kotlin.Long" ->
                "                int64_t cpp_p$i = count > $i && args[$i].isNumber() ? (int64_t)args[$i].getNumber() : 0;\n"
            "kotlin.Boolean" ->
                "                bool cpp_p$i = count > $i && args[$i].isBool() ? args[$i].getBool() : false;\n"
            "kotlin.Double", "kotlin.Float" ->
                "                double cpp_p$i = count > $i && args[$i].isNumber() ? args[$i].getNumber() : 0.0;\n"
            else ->
                "                void* cpp_p$i = nullptr;\n"
        }
        is Param.Ref ->
            "                void* cpp_p$i = nullptr;\n" +
                "                if (count > $i && args[$i].isObject() && args[$i].asObject(r).hasNativeState(r)) {\n" +
                "                    auto argObj_$i = args[$i].asObject(r);\n" +
                "                    auto argState_$i = std::static_pointer_cast<RdmaObjectNativeStateCAbi>(argObj_$i.getNativeState(r));\n" +
                "                    if (argState_$i) cpp_p$i = argState_$i->handle();\n" +
                "                }\n"
        is Param.Content, is Param.Callback ->
            "                int64_t cpp_p$i = count > $i && args[$i].isNumber() ? (int64_t)args[$i].getNumber() : 0;\n"
    }
}
