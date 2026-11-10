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

import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import java.io.OutputStream

/**
 * JNI (JVM/Android) backend: generates a single Kotlin source file with one top-level wrapper
 * function per defaulted `@RDMA` callable (function, method, constructor).
 *
 * The C++ JNI bridge cannot evaluate an arbitrary Kotlin default expression, so it marshals the
 * arguments, computes a bitmask of the omitted trailing parameters, and calls the wrapper here.
 * The wrapper evaluates the default *natively* for each masked parameter and delegates to the
 * original declaration.
 */
class RdmaDefaultWrapperGenerator(
    private val kernelPackage: String,
    private val output: (String, String) -> OutputStream,
) {

    fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>) {
        val fns = functions.filter { !it.suspend && it.parameters.any { p -> p.hasDefault } }
        val methods = classInfos.flatMap { cls ->
            cls.methods.filter { !it.isSuspend && it.parameters.any { p -> p.hasDefault } }.map { cls to it }
        }
        val ctors = classInfos.filter { cls -> cls.constructors.any { c -> c.parameters.any { p -> p.hasDefault } } }
        if (fns.isEmpty() && methods.isEmpty() && ctors.isEmpty()) return

        val out = output("RdmaDefaultWrappers.kt", "RdmaDefaultWrappers.kt").bufferedWriter()
        out.write("@file:Suppress(\"unused\", \"FunctionName\")\n\n")
        out.write("package $kernelPackage\n\n")

        for (fn in fns) {
            out.write(writeFunction(fn))
        }
        for ((cls, m) in methods) {
            out.write(writeMethod(cls, m))
        }
        for (cls in ctors) {
            for (ctor in cls.constructors) {
                out.write(writeConstructor(cls, ctor))
            }
        }
        out.close()
    }

    /** `mask and (1 shl i) != 0` — true when parameter [i] was omitted. */
    private fun omitted(i: Int) = "mask and ${1 shl i} != 0"

    private fun resolve(name: String, defaultValue: String, converted: String, i: Int): String =
        "if (${omitted(i)}) ($defaultValue) else $converted"

    private fun writeFunction(fn: RdmaFunctionInfo): String {
        val sb = StringBuilder()
        val params = fn.parameters.mapIndexed { i, p ->
            "${p.name}: ${kotlinType(p.type)}"
        }.toMutableList()
        params.add("mask: Int")
        val args = fn.parameters.mapIndexed { i, p ->
            if (p.hasDefault && p.defaultValue != null) resolve(p.name, p.defaultValue!!, p.name, i) else p.name
        }.joinToString(", ")
        val ret = kotlinType(fn.returnType)
        sb.append("fun ${fn.name}_rdma_default(${params.joinToString(", ")}): $ret = ${fn.qualifiedName}($args)\n\n")
        return sb.toString()
    }

    private fun writeMethod(cls: RdmaClassInfo, m: io.github.dendygrobovshik.kardman.types.MethodInfo): String {
        val sb = StringBuilder()
        val params = buildList {
            add("self: ${cls.qualifiedName}")
            m.parameters.forEach { add("${it.name}: ${memberKotlinType(it)}") }
            add("mask: Int")
        }
        val args = m.parameters.mapIndexed { i, p ->
            if (p.hasDefault && p.defaultValue != null) resolve(p.name, p.defaultValue!!, p.name, i) else p.name
        }.joinToString(", ")
        val ret = memberKotlinType(m)
        val call = "self.${m.name}($args)"
        sb.append("fun ${cls.className}_${m.name}_rdma_default(${params.joinToString(", ")}): $ret = $call\n\n")
        return sb.toString()
    }

    private fun writeConstructor(cls: RdmaClassInfo, ctor: io.github.dendygrobovshik.kardman.types.ConstructorInfo): String {
        val sb = StringBuilder()
        val params = buildList {
            ctor.parameters.forEach { add("${it.name}: ${memberKotlinType(it)}") }
            add("mask: Int")
        }
        val args = ctor.parameters.mapIndexed { i, p ->
            if (p.hasDefault && p.defaultValue != null) resolve(p.name, p.defaultValue!!, p.name, i) else p.name
        }.joinToString(", ")
        sb.append("fun ${cls.className}_new_rdma_default(${params.joinToString(", ")}): ${cls.qualifiedName} = ${cls.qualifiedName}($args)\n\n")
        return sb.toString()
    }

    private fun kotlinType(t: RdmaTypeRef): String = when (val ty = t.type) {
        is RdmaType.UnitType -> "Unit"
        is RdmaType.Primitive -> primitiveKotlin(ty.fqn)
        is RdmaType.Ref -> ty.fqn
        is RdmaType.ListType -> "List<${kotlinType(ty.element)}>"
        is RdmaType.MapType -> "Map<${kotlinType(ty.key)}, ${kotlinType(ty.value)}>"
        is RdmaType.FunctionType -> "kotlin.Function${ty.parameters.size}"
    }

    private fun primitiveKotlin(fqn: String): String = when (fqn) {
        "kotlin.String" -> "String"
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.Double" -> "Double"
        "kotlin.Float" -> "Float"
        else -> "Any"
    }

    private fun memberKotlinType(p: ParameterInfo): String = when {
        p.isList -> "List<${descriptorKotlin(p.listElementType ?: "kotlin.Any")}>"
        p.isMap -> "Map<${descriptorKotlin(p.mapKeyType ?: "kotlin.Any")}, ${descriptorKotlin(p.mapValueType ?: "kotlin.Any")}>"
        else -> primitiveKotlin(p.type).takeIf { it != "Any" } ?: p.type
    }

    private fun memberKotlinType(m: io.github.dendygrobovshik.kardman.types.MethodInfo): String = when {
        m.isList -> "List<${descriptorKotlin(m.listElementType ?: "kotlin.Any")}>"
        m.isMap -> "Map<${descriptorKotlin(m.mapKeyType ?: "kotlin.Any")}, ${descriptorKotlin(m.mapValueType ?: "kotlin.Any")}>"
        else -> primitiveKotlin(m.returnType).takeIf { it != "Any" } ?: m.returnType
    }

    private fun descriptorKotlin(descriptor: String): String = when {
        descriptor.startsWith("list:") -> "List<${descriptorKotlin(descriptor.removePrefix("list:"))}>"
        descriptor.startsWith("map:") -> {
            val rest = descriptor.removePrefix("map:")
            val idx = rest.indexOf(':')
            val key = if (idx < 0) rest else rest.substring(0, idx)
            val value = if (idx < 0) "kotlin.Any" else rest.substring(idx + 1)
            "Map<${descriptorKotlin(key)}, ${descriptorKotlin(value)}>"
        }
        else -> primitiveKotlin(descriptor).takeIf { it != "Any" } ?: descriptor
    }
}
