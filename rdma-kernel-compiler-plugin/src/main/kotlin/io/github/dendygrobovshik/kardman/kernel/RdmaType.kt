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
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.typeOrNull

object RdmaTypeParser {
    val primitiveFqns = setOf(
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.Float",
        "kotlin.Double",
        "kotlin.Boolean",
        "kotlin.String",
    )

    fun toRdmaTypeRef(type: IrType): RdmaTypeRef {
        val nullable = type.isNullable()
        val fqn = type.classFqName?.asString()
        if (fqn == null) return RdmaTypeRef(RdmaType.Ref("kotlin.Any"), nullable)
        val kind: RdmaType = when {
            fqn == "kotlin.Unit" -> RdmaType.UnitType
            fqn in primitiveFqns -> RdmaType.Primitive(fqn)
            fqn == "kotlin.collections.List" || fqn == "kotlin.collections.MutableList" ->
                RdmaType.ListType((type as? IrSimpleType)?.toElementTypeRef() ?: anyRef())
            fqn == "kotlin.collections.Map" || fqn == "kotlin.collections.MutableMap" ->
                (type as? IrSimpleType)?.toMapTypeRef() ?: RdmaType.MapType(anyRef(), anyRef())
            fqn.startsWith("kotlin.Function") ->
                (type as? IrSimpleType)?.toFunctionType() ?: anyRef().type
            else -> RdmaType.Ref(fqn)
        }
        return RdmaTypeRef(kind, nullable)
    }

    private fun anyRef() = RdmaTypeRef(RdmaType.Ref("kotlin.Any"))

    private fun IrSimpleType.toElementTypeRef(): RdmaTypeRef =
        arguments.firstOrNull()?.typeOrNull?.let { toRdmaTypeRef(it) } ?: anyRef()

    private fun IrSimpleType.toMapTypeRef(): RdmaType.MapType {
        val args = arguments.mapNotNull { it.typeOrNull }
        val key = args.getOrNull(0)?.let { toRdmaTypeRef(it) } ?: anyRef()
        val value = args.getOrNull(1)?.let { toRdmaTypeRef(it) } ?: anyRef()
        return RdmaType.MapType(key, value)
    }

    private fun IrSimpleType.toFunctionType(): RdmaType.FunctionType {
        val args = arguments.mapNotNull { it.typeOrNull }
        val returnType = if (args.isEmpty()) RdmaTypeRef(RdmaType.UnitType) else toRdmaTypeRef(args.last())
        val parameters = if (args.size <= 1) emptyList() else args.dropLast(1).map { toRdmaTypeRef(it) }
        return RdmaType.FunctionType(parameters, returnType)
    }
}

object RdmaTypeValidator {
    fun validate(type: RdmaTypeRef, isRdmaClass: (String) -> Boolean): List<String> {
        val errors = mutableListOf<String>()
        fun check(t: RdmaType, path: String) {
            when (t) {
                is RdmaType.Primitive, is RdmaType.UnitType -> Unit
                is RdmaType.Ref -> if (!isRdmaClass(t.fqn)) {
                    errors += "$path: '${t.fqn}' is not a @RDMA type; only @RDMA classes, @RDMA enums, primitives, functions and lists can cross the runtime boundary"
                }
                is RdmaType.ListType -> check(t.element.type, "$path[]")
                is RdmaType.MapType -> {
                    check(t.key.type, "$path.key")
                    check(t.value.type, "$path.value")
                }
                is RdmaType.FunctionType -> {
                    t.parameters.forEachIndexed { i, p -> check(p.type, "$path.param$i") }
                    check(t.returnType.type, "$path.return")
                }
            }
        }
        check(type.type, "type")
        return errors
    }

    fun validateFunction(function: RdmaFunctionInfo, isRdmaClass: (String) -> Boolean): List<String> {
        val errors = mutableListOf<String>()
        function.parameters.forEach { p ->
            errors += validate(p.type, isRdmaClass).map { "function ${function.qualifiedName}: parameter '${p.name}' $it" }
        }
        errors += validate(function.returnType, isRdmaClass).map { "function ${function.qualifiedName}: $it" }
        return errors
    }

    /**
     * Validates every type in an `@RDMA` class (constructor parameters, properties,
     * method parameters/returns, statics) recursively. Class members use the string
     * `ParameterInfo`/`PropertyInfo`/`MethodInfo` shape rather than [RdmaTypeRef], so
     * the list element descriptor (`list:<elem>`) is walked directly.
     */
    fun validateClass(classInfo: RdmaClassInfo, isRdmaClass: (String) -> Boolean): List<String> {
        val errors = mutableListOf<String>()
        val primitives = RdmaTypeParser.primitiveFqns

        fun checkLeaf(fqn: String, path: String) {
            if (fqn !in primitives && !isRdmaClass(fqn)) {
                errors += "$path: '$fqn' is not a @RDMA type; only @RDMA classes, @RDMA enums, primitives, functions and lists can cross the runtime boundary"
            }
        }

        // Splits the front descriptor off [s] and returns it together with the
        // unconsumed remainder. A descriptor is a leaf FQN, `list:<elem>`, or
        // `map:<key>:<value>`; collection prefixes are consumed recursively.
        fun splitDescriptor(s: String): Pair<String, String> {
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

        fun checkDescriptor(descriptor: String, path: String) {
            when {
                descriptor.startsWith("list:") ->
                    checkDescriptor(descriptor.removePrefix("list:"), "$path[]")
                descriptor.startsWith("map:") -> {
                    val (key, value) = splitDescriptor(descriptor.removePrefix("map:"))
                    checkDescriptor(key, "$path.key")
                    checkDescriptor(value, "$path.value")
                }
                else -> checkLeaf(descriptor, path)
            }
        }

        fun checkParameter(p: ParameterInfo, path: String) {
            when {
                p.isList -> {
                    val elem = p.listElementType
                    if (elem == null) errors.add("$path: list element type is missing") else checkDescriptor(elem, path)
                }
                p.isMap -> {
                    val key = p.mapKeyType
                    val value = p.mapValueType
                    if (key == null || value == null) errors.add("$path: map key/value type is missing")
                    else { checkDescriptor(key, "$path.key"); checkDescriptor(value, "$path.value") }
                }
                p.type != "kotlin.Unit" -> checkLeaf(p.type, path)
            }
        }

        fun checkProperty(p: PropertyInfo, path: String) {
            when {
                p.isList -> {
                    val elem = p.listElementType
                    if (elem == null) errors.add("$path: list element type is missing") else checkDescriptor(elem, path)
                }
                p.isMap -> {
                    val key = p.mapKeyType
                    val value = p.mapValueType
                    if (key == null || value == null) errors.add("$path: map key/value type is missing")
                    else { checkDescriptor(key, "$path.key"); checkDescriptor(value, "$path.value") }
                }
                else -> checkLeaf(p.type, path)
            }
        }

        fun checkMethodReturn(m: MethodInfo, path: String) {
            when {
                m.isList -> {
                    val elem = m.listElementType
                    if (elem == null) errors.add("$path: list element type is missing") else checkDescriptor(elem, path)
                }
                m.isMap -> {
                    val key = m.mapKeyType
                    val value = m.mapValueType
                    if (key == null || value == null) errors.add("$path: map key/value type is missing")
                    else { checkDescriptor(key, "$path.key"); checkDescriptor(value, "$path.value") }
                }
                m.returnType != "kotlin.Unit" -> checkLeaf(m.returnType, path)
            }
        }

        val prefix = "class ${classInfo.qualifiedName}"
        classInfo.constructors.forEachIndexed { i, c ->
            c.parameters.forEachIndexed { j, p -> checkParameter(p, "$prefix: constructor[$i] parameter '${p.name}'") }
        }
        classInfo.properties.forEach { p -> checkProperty(p, "$prefix: property '${p.name}'") }
        classInfo.methods.forEach { m ->
            if (m.isSuspend && m.isOpen) {
                errors += "$prefix: method '${m.name}': overriding a suspend method from the plugin is not supported"
            }
            m.parameters.forEachIndexed { i, p -> checkParameter(p, "$prefix: method '${m.name}' parameter '${p.name}'") }
            checkMethodReturn(m, "$prefix: method '${m.name}' return")
        }
        classInfo.statics.forEach { s ->
            if (s.type != "kotlin.Unit") checkLeaf(s.type, "$prefix: static '${s.name}'")
        }
        return errors
    }
}
