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

import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.StaticInfo
import java.io.BufferedWriter
import java.io.OutputStream

/**
 * Generates the Kotlin/Native C-ABI bridge for a kernel module: the C-compatible
 * functions the iOS C++ proxies call (via the function-pointer registry) instead
 * of JNI.
 *
 * Every object crosses as a StableRef handle (`COpaquePointer`); primitives cross
 * by value; a `String` crosses as a `const char*` (`CPointer<ByteVar>?`); a lambda
 * crosses as a block id (`Long`) and is wrapped into the concrete Kotlin function
 * type inline. The `rdma_..._typeIdOf` entry powers `wrapAny` (integer type
 * registry instead of `Class.getName`).
 *
 * All generated functions are registered in the C++ function-pointer registry via
 * `@EagerInitialization` + `staticCFunction` (replacing the former `@CName` symbol
 * linkage).
 */
class CAbiKotlinGenerator(
    private val moduleId: String,
    private val kernelPackage: String,
    private val output: (String, String) -> OutputStream,
) {
    private val fileSuffix: String = if (moduleId.isEmpty()) "" else "_$moduleId"

    // A class-member type carries its kind explicitly (List + element type).
    private data class MemberType(
        val type: String,
        val isList: Boolean = false,
        val listElementType: String? = null,
        val nullable: Boolean = false,
    )

    fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>) {
        val plainFunctions = functions.filter { !it.composable }
        if (classInfos.isEmpty() && plainFunctions.isEmpty()) return

        val registered = mutableListOf<String>()
        val out = output("RdmaCAbi$fileSuffix.kt", "RdmaCAbi$fileSuffix.kt").bufferedWriter()
        out.write("""@file:Suppress("FunctionName", "unused", "UNUSED_PARAMETER", "UNCHECKED_CAST")
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class, kotlin.ExperimentalStdlibApi::class)

package $kernelPackage

import io.github.dendygrobovshik.kardman.runtime.rdmaRegisterFunction
import io.github.dendygrobovshik.kardman.runtime.rdmaStringToCStr
import kotlin.native.EagerInitialization
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong

private fun <T : Any> T.handle(): COpaquePointer = StableRef.create(this).asCPointer()

private fun <T : Any> COpaquePointer.obj(): T = asStableRef<Any>().get() as T

""")
        for (info in classInfos) {
            writeCtor(out, info, registered)
            for (method in info.methods) writeMethod(out, info, method, registered)
            for (prop in info.properties) writeProperty(out, info, prop, registered)
            for (static in info.statics) writeStatic(out, info, static, registered)
            writeVtableSetter(out, info, registered)
        }
        for (fn in plainFunctions) {
            writeFunction(out, fn, registered)
        }
        writeTypeIdOf(out, classInfos, registered)
        writeRegistration(out, registered)
        out.close()
    }

    // --------------------------------------------------------------- helpers

    private fun cName(name: String): String = CAbi.prefix(moduleId) + name

    private fun kotlinType(m: MemberType): String = when {
        m.isList -> if (m.nullable) "COpaquePointer?" else "COpaquePointer"
        m.type.startsWith("kotlin.Function") -> "Long"
        m.type == "kotlin.String" -> "CPointer<ByteVar>?"
        CAbi.isPrimitive(m.type) || CAbi.isVoid(m.type) -> CAbi.forMemberType(m.type).kotlinType
        else -> if (m.nullable) "COpaquePointer?" else "COpaquePointer"
    }

    private fun fromC(expr: String, m: MemberType): String {
        if (m.isList) {
            val elem = m.listElementType ?: "kotlin.Any"
            return if (m.nullable) "$expr?.obj<List<$elem>>()" else "$expr.obj<List<$elem>>()"
        }
        return when {
            m.type == "kotlin.String" -> "$expr?.toKString() ?: \"\""
            CAbi.isPrimitive(m.type) || CAbi.isVoid(m.type) -> expr
            m.type.startsWith("kotlin.Function") -> expr
            else -> if (m.nullable) "$expr?.obj<${m.type}>()" else "$expr.obj<${m.type}>()"
        }
    }

    private fun toC(expr: String, m: MemberType): String = when {
        m.isList -> if (m.nullable) "$expr?.handle()" else "$expr.handle()"
        m.type == "kotlin.String" -> "rdmaStringToCStr($expr ?: \"\")"
        CAbi.isPrimitive(m.type) || CAbi.isVoid(m.type) -> expr
        m.type.startsWith("kotlin.Function") -> expr
        else -> if (m.nullable) "$expr?.handle()" else "$expr.handle()"
    }

    // --------------------------------------------------------------- classes

    private fun writeCtor(out: BufferedWriter, info: RdmaClassInfo, registered: MutableList<String>) {
        for (ctor in info.constructors) {
            val name = cName(info.className + "_new")
            val params = ctor.parameters.joinToString(", ") { "${it.name}: ${kotlinType(it.toMemberType())}" }
            val args = ctor.parameters.joinToString(", ") { fromC(it.name, it.toMemberType()) }
            out.write("""
fun $name($params): COpaquePointer {
    return ${info.qualifiedName}($args).handle()
}
""")
            registered += name
        }
    }

    private fun writeMethod(out: BufferedWriter, info: RdmaClassInfo, method: MethodInfo, registered: MutableList<String>) {
        val retM = MemberType(method.returnType, method.isList, method.listElementType, method.nullableReturn)
        val name = cName(info.className + "_" + method.name)
        val params = buildList {
            add("handle: COpaquePointer")
            method.parameters.forEach { add("${it.name}: ${kotlinType(it.toMemberType())}") }
        }.joinToString(", ")
        val args = method.parameters.joinToString(", ") { fromC(it.name, it.toMemberType()) }
        val call = "handle.obj<${info.qualifiedName}>().${method.name}($args)"
        val body = if (CAbi.isVoid(method.returnType)) {
            "    $call\n"
        } else {
            "    return ${toC(call, retM)}\n"
        }
        out.write("""
fun $name($params): ${kotlinType(retM)} {
$body}
""")
        registered += name
    }

    private fun writeProperty(out: BufferedWriter, info: RdmaClassInfo, prop: PropertyInfo, registered: MutableList<String>) {
        val m = MemberType(prop.type, prop.isList, prop.listElementType, prop.nullable)
        val call = "handle.obj<${info.qualifiedName}>().${prop.name}"
        val getterName = cName(info.className + "_get" + prop.name.replaceFirstChar { it.uppercase() })
        out.write("""
fun $getterName(handle: COpaquePointer): ${kotlinType(m)} {
    return ${toC(call, m)}
}
""")
        registered += getterName
        if (prop.isMutable) {
            val setterName = cName(info.className + "_set" + prop.name.replaceFirstChar { it.uppercase() })
            out.write("""
fun $setterName(handle: COpaquePointer, value: ${kotlinType(m)}) {
    handle.obj<${info.qualifiedName}>().${prop.name} = ${fromC("value", m)}
}
""")
            registered += setterName
        }
    }

    private fun writeStatic(out: BufferedWriter, info: RdmaClassInfo, static: StaticInfo, registered: MutableList<String>) {
        val m = MemberType(static.type, nullable = static.nullable)
        val call = "${info.qualifiedName}.${static.name}"
        val name = cName(info.className + "_get" + static.name.replaceFirstChar { it.uppercase() })
        out.write("""
fun $name(): ${kotlinType(m)} {
    return ${toC(call, m)}
}
""")
        registered += name
    }

    private fun writeVtableSetter(out: BufferedWriter, info: RdmaClassInfo, registered: MutableList<String>) {
        val name = cName(info.className + "_setVtable")
        out.write("""
fun $name(handle: COpaquePointer, vtable: COpaquePointer) {
    io.github.dendygrobovshik.kardman.runtime.rdmaVtableSet(handle.obj<Any>(), vtable.toLong())
}
""")
        registered += name
    }

    // -------------------------------------------------------------- functions

    private fun functionParamKotlinType(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.UnitType -> "Unit"
        is RdmaType.Primitive -> if (ty.fqn == "kotlin.String") "CPointer<ByteVar>?" else CAbi.forMemberType(ty.fqn).kotlinType
        is RdmaType.Ref -> "COpaquePointer"
        is RdmaType.ListType -> "COpaquePointer"
        is RdmaType.FunctionType -> "Long"
    }

    private fun functionReturnKotlinType(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.UnitType -> "Unit"
        is RdmaType.Primitive -> if (ty.fqn == "kotlin.String") "CPointer<ByteVar>?" else CAbi.forMemberType(ty.fqn).kotlinType
        is RdmaType.Ref -> "COpaquePointer"
        is RdmaType.ListType -> "COpaquePointer"
        is RdmaType.FunctionType -> "COpaquePointer"
    }

    private fun functionParamFromC(p: io.github.dendygrobovshik.kardman.types.RdmaParameterInfo): String = when (val ty = p.type.type) {
        is RdmaType.UnitType -> p.name
        is RdmaType.Primitive -> if (ty.fqn == "kotlin.String") "${p.name}?.toKString() ?: \"\"" else p.name
        is RdmaType.Ref -> if (p.type.nullable) "${p.name}?.obj<${ty.fqn}>()" else "${p.name}.obj<${ty.fqn}>()"
        is RdmaType.ListType -> if (p.type.nullable) "${p.name}?.obj<List<${listElemKotlin(ty)}>>()" else "${p.name}.obj<List<${listElemKotlin(ty)}>>()"
        is RdmaType.FunctionType -> "${p.name}_fn"
    }

    private fun functionReturnToC(t: RdmaTypeRef, expr: String): String = when (val ty = t.type) {
        is RdmaType.UnitType -> ""
        is RdmaType.Primitive -> if (ty.fqn == "kotlin.String") "rdmaStringToCStr($expr ?: \"\")" else expr
        is RdmaType.Ref -> "$expr.handle()"
        is RdmaType.ListType -> "$expr.handle()"
        is RdmaType.FunctionType -> "$expr.handle()"
    }

    private fun listElemKotlin(t: RdmaType.ListType): String = when (val e = t.element.type) {
        is RdmaType.Primitive -> CAbi.forMemberType(e.fqn).kotlinType
        is RdmaType.Ref -> e.fqn
        is RdmaType.ListType -> "List<${listElemKotlin(e)}>"
        is RdmaType.FunctionType -> "kotlin.Function${e.parameters.size}"
        is RdmaType.UnitType -> "Unit"
    }

    private fun functionParamKotlinFnType(t: RdmaType.FunctionType): String = buildString {
        append("(")
        append(t.parameters.joinToString(", ") { p ->
            when (val ty = p.type) {
                is RdmaType.Primitive -> CAbi.forMemberType(ty.fqn).kotlinType
                is RdmaType.Ref -> ty.fqn
                is RdmaType.ListType -> "List<${listElemKotlin(ty)}>"
                is RdmaType.FunctionType -> "kotlin.Function${ty.parameters.size}"
                is RdmaType.UnitType -> "Unit"
            }
        })
        append(") -> Unit")
    }

    private fun writeFunction(out: BufferedWriter, fn: RdmaFunctionInfo, registered: MutableList<String>) {
        val name = cName(fn.name)
        val params = fn.parameters.joinToString(", ") { "${it.name}: ${functionParamKotlinType(it.type)}" }

        val lambdaDecls = StringBuilder()
        for (p in fn.parameters) {
            val ty = p.type.type as? RdmaType.FunctionType ?: continue
            val n = ty.parameters.size
            val argNames = (0 until n).joinToString(", ") { "p$it" }
            val argsArray = if (n == 0) "emptyArray<Any?>()"
            else (0 until n).joinToString(", ", "arrayOf<Any?>(", ")") { "p$it" }
            lambdaDecls.append("    val ${p.name}_fn: ${functionParamKotlinFnType(ty)} = " +
                "{ $argNames -> io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost.nativeInvokeLambda(${p.name}, $argsArray); Unit }\n")
        }

        val args = fn.parameters.joinToString(", ") { functionParamFromC(it) }
        val retK = functionReturnKotlinType(fn.returnType)
        val invokeCall = "${fn.qualifiedName}" + (if (args.isEmpty()) "()" else "($args)")
        val body = if (fn.returnType.type is RdmaType.UnitType) {
            "$lambdaDecls    $invokeCall\n"
        } else {
            "$lambdaDecls    return ${functionReturnToC(fn.returnType, invokeCall)}\n"
        }
        out.write("""
fun $name($params): $retK {
$body}
""")
        registered += name
    }

    private fun writeTypeIdOf(out: BufferedWriter, infos: List<RdmaClassInfo>, registered: MutableList<String>) {
        if (infos.isEmpty()) return
        val name = cName("typeIdOf")
        val branches = infos.mapIndexed { i, info ->
            "        is ${info.qualifiedName} -> $i"
        }.joinToString("\n")
        out.write("""
fun $name(handle: COpaquePointer): Int {
    return when (handle.obj<Any>()) {
$branches
        else -> -1
    }
}
""")
        registered += name
    }

    private fun writeRegistration(out: BufferedWriter, registered: List<String>) {
        if (registered.isEmpty()) return
        val calls = registered.joinToString("\n") { name ->
            "    rdmaRegisterFunction(\"$name\", staticCFunction(::$name))"
        }
        out.write("""
@EagerInitialization
private val _rdma_${moduleId.ifEmpty { "default" }}_register = run {
$calls
    Unit
}
""")
    }

    private fun ParameterInfo.toMemberType() =
        MemberType(type, isList, listElementType, nullable)
}
