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
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import java.io.OutputStream

/**
 * Generates the JVM-side async bridge wrappers for `@RDMA suspend` functions and
 * methods. Each wrapper launches the suspend body on the framework scope and
 * completes through `RdmaSuspend.completeResult`/`completeError`.
 *
 * The wrappers are top-level functions in a single facade (`<pkg>.RdmaSuspendBridgeKt`)
 * so the generated C++ glue has a single class to resolve for `GetStaticMethodID`.
 */
class RdmaSuspendBridgeGenerator(
    private val kernelPackage: String,
    private val output: (String, String) -> OutputStream,
) {

    fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>) {
        val suspendFunctions = functions.filter { it.suspend && !it.composable }
        val suspendMethods = classInfos.flatMap { cls ->
            cls.methods.filter { it.isSuspend }.map { cls to it }
        }
        if (suspendFunctions.isEmpty() && suspendMethods.isEmpty()) return

        val out = output("RdmaSuspendBridge.kt", "RdmaSuspendBridge.kt").bufferedWriter()
        out.write("""@file:Suppress("unused")

package $kernelPackage

import io.github.dendygrobovshik.kardman.runtime.RdmaSuspend

""")
        for (fn in suspendFunctions) {
            out.write(wrapperForFunction(fn))
            out.write("\n")
        }
        for ((cls, m) in suspendMethods) {
            out.write(wrapperForMethod(cls, m))
            out.write("\n")
        }
        out.close()
    }

    private fun wrapperForFunction(fn: RdmaFunctionInfo): String {
        val wrapper = suspendWrapperName(fn.name)
        val params = fn.parameters.joinToString(", ") { "${it.name}: ${renderType(it.type)}" }
        val callArgs = fn.parameters.joinToString(", ") { it.name }
        val body = tryCatchBlock("${fn.qualifiedName}($callArgs)")
        val declParams = listOf("completionId: Long") + fn.parameters.map { "${it.name}: ${renderType(it.type)}" }
        return "fun $wrapper(${declParams.joinToString(", ")}) {\n    RdmaSuspend.launch {\n$body    }\n}\n"
    }

    private fun wrapperForMethod(cls: RdmaClassInfo, m: MethodInfo): String {
        val wrapper = suspendWrapperName(cls, m)
        val callArgs = m.parameters.joinToString(", ") { it.name }
        val body = tryCatchBlock("receiver.${m.name}($callArgs)")
        val declParams = listOf("completionId: Long", "receiver: ${cls.qualifiedName}") +
            m.parameters.map { "${it.name}: ${renderParamType(it)}" }
        return "fun $wrapper(${declParams.joinToString(", ")}) {\n    RdmaSuspend.launch {\n$body    }\n}\n"
    }

    private fun tryCatchBlock(call: String): String =
        "        try { RdmaSuspend.completeResult(completionId, $call) }\n" +
            "        catch (t: Throwable) { RdmaSuspend.completeError(completionId, t) }\n"

    companion object {
        fun suspendWrapperName(functionName: String): String = "_${functionName}Async"

        fun suspendWrapperName(cls: RdmaClassInfo, m: MethodInfo): String =
            "_${cls.className}_${m.name}Async"
    }
}

private fun renderType(t: RdmaTypeRef): String {
    val base = when (val ty = t.type) {
        is RdmaType.Primitive -> simpleType(ty.fqn)
        is RdmaType.Ref -> ty.fqn
        is RdmaType.ListType -> "List<${renderType(ty.element)}>"
        is RdmaType.MapType -> "Map<${renderType(ty.key)}, ${renderType(ty.value)}>"
        is RdmaType.FunctionType -> renderFunctionType(ty)
        is RdmaType.UnitType -> "Unit"
    }
    return if (t.nullable) "$base?" else base
}

private fun renderFunctionType(t: RdmaType.FunctionType): String {
    val params = t.parameters.joinToString(", ") { renderType(it) }
    return "($params) -> ${renderType(t.returnType)}"
}

private fun renderParamType(p: ParameterInfo): String {
    val base = when {
        p.isList -> "List<${renderDescriptor(p.listElementType ?: "kotlin.Any")}>"
        p.isMap -> {
            val (k, v) = splitMapDescriptor(p.mapKeyType, p.mapValueType)
            "Map<${renderDescriptor(k)}, ${renderDescriptor(v)}>"
        }
        else -> simpleType(p.type)
    }
    return if (p.nullable) "$base?" else base
}

private fun splitMapDescriptor(key: String?, value: String?): Pair<String, String> =
    (key ?: "kotlin.Any") to (value ?: "kotlin.Any")

private fun renderDescriptor(d: String): String = when {
    d.startsWith("list:") -> "List<${renderDescriptor(d.removePrefix("list:"))}>"
    d.startsWith("map:") -> {
        val rest = d.removePrefix("map:")
        val (k, v) = splitDescriptor(rest)
        "Map<${renderDescriptor(k)}, ${renderDescriptor(v)}>"
    }
    else -> simpleType(d)
}

// Splits `key:value` where each part is itself a (possibly nested) descriptor.
private fun splitDescriptor(s: String): Pair<String, String> {
    if (s.startsWith("list:")) {
        val (elem, rest) = splitDescriptor(s.removePrefix("list:"))
        return "list:$elem" to rest
    }
    if (s.startsWith("map:")) {
        val (key, rest1) = splitDescriptor(s.removePrefix("map:"))
        val (value, rest2) = splitDescriptor(rest1)
        return "map:$key:$value" to rest2
    }
    val idx = s.indexOf(':')
    return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
}

private fun simpleType(fqn: String): String = when (fqn) {
    "kotlin.Int" -> "Int"
    "kotlin.Long" -> "Long"
    "kotlin.Float" -> "Float"
    "kotlin.Double" -> "Double"
    "kotlin.Boolean" -> "Boolean"
    "kotlin.String" -> "String"
    "kotlin.Unit" -> "Unit"
    else -> fqn
}
