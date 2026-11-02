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

import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.StaticInfo
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.FqName

private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

data class ExtractedClass(val cls: IrClass, val info: RdmaClassInfo)

object RdmaClassExtractor {

    fun extractWithClasses(moduleFragment: IrModuleFragment): List<ExtractedClass> {
        val result = mutableListOf<ExtractedClass>()
        for (file in moduleFragment.files) {
            for (declaration in file.declarations) {
                val cls = declaration as? IrClass ?: continue
                if (!cls.hasAnnotation(RDMA_ANNOTATION)) continue
                result.add(ExtractedClass(cls, extractClass(cls)))
            }
        }
        return result
    }

    fun extractClass(cls: IrClass): RdmaClassInfo {
        val qualifiedName = cls.fqNameWhenAvailable?.asString() ?: cls.name.asString()
        val packageName = cls.fqNameWhenAvailable?.parent()?.asString() ?: ""
        val className = cls.name.asString()

        val constructors = buildList {
            val primary = cls.primaryConstructor ?: return@buildList
            add(ConstructorInfo(primary.valueParams().map { it.toParameterInfo() }))
        }

        val methods = cls.functions
            .filter { it.visibility == DescriptorVisibilities.PUBLIC }
            .filter { it.correspondingPropertySymbol == null }
            .filter { fn ->
                val n = fn.name.asString()
                n !in setOf("equals", "hashCode") && !n.startsWith("component") && !n.startsWith("copy")
            }
            .map { fn ->
                val isOpen = fn.modality == Modality.OPEN && fn.overriddenSymbols.isEmpty()
                MethodInfo(
                    name = fn.name.asString(),
                    returnType = fn.returnType.toTypeName(),
                    parameters = fn.valueParams().map { it.toParameterInfo() },
                    nullableReturn = fn.returnType.isNullable(),
                    isOpen = isOpen,
                    isList = fn.returnType.isList(),
                    listElementType = fn.returnType.listElementType(),
                    isMap = fn.returnType.isMap(),
                    mapKeyType = fn.returnType.mapKeyType(),
                    mapValueType = fn.returnType.mapValueType(),
                    vtableId = -1,
                )
            }
            .let { list ->
                var nextId = 0
                list.map { if (it.isOpen) it.copy(vtableId = nextId++) else it }.toList()
            }

        val properties = cls.properties
            .filter { it.visibility == DescriptorVisibilities.PUBLIC }
            .filter { !it.name.asString().startsWith("__") }
            .filter { !it.name.asString().startsWith("$") }
            .map { prop ->
                val type = prop.type()
                PropertyInfo(
                    name = prop.name.asString(),
                    type = type.toTypeName(),
                    isMutable = prop.isVar,
                    nullable = type.isNullable(),
                    isList = type.isList(),
                    listElementType = type.listElementType(),
                    isMap = type.isMap(),
                    mapKeyType = type.mapKeyType(),
                    mapValueType = type.mapValueType(),
                )
            }
            .toList()

        return RdmaClassInfo(packageName, className, qualifiedName, constructors, methods, properties, extractStatics(cls))
    }

    /**
     * Extracts public `val`s from the companion object of an `@RDMA` class. These become
     * host-side singleton getters (e.g. `Alignment.Center`) available to the plugin.
     */
    private fun extractStatics(cls: IrClass): List<StaticInfo> {
        val companion = cls.declarations
            .filterIsInstance<IrClass>()
            .firstOrNull { it.isCompanion }
            ?: return emptyList()
        return companion.properties
            .filter { it.visibility == DescriptorVisibilities.PUBLIC }
            .filter { !it.name.asString().startsWith("__") }
            .filter { !it.name.asString().startsWith("$") }
            .mapNotNull { prop ->
                val type = prop.type()
                StaticInfo(
                    name = prop.name.asString(),
                    type = type.toTypeName(),
                    nullable = type.isNullable(),
                )
            }
            .toList()
    }

    private fun IrValueParameter.toParameterInfo(): ParameterInfo {
        val typeName = type.toTypeName()
        return ParameterInfo(
            name = name.asString(),
            type = typeName,
            nullable = type.isNullable(),
            isList = type.isList(),
            listElementType = type.listElementType(),
            isMap = type.isMap(),
            mapKeyType = type.mapKeyType(),
            mapValueType = type.mapValueType(),
        )
    }

    private fun org.jetbrains.kotlin.ir.declarations.IrFunction.valueParams(): List<IrValueParameter> =
        parameters.filter { it.kind == IrParameterKind.Regular }

    private fun IrProperty.type(): IrType = getter?.returnType ?: backingField?.type ?: error("no type")

    private fun IrType.toTypeName(): String = classFqName?.asString() ?: "kotlin.Any"

    private fun IrType.isList(): Boolean {
        val fqn = toTypeName()
        return fqn == "kotlin.collections.List" || fqn == "kotlin.collections.MutableList"
    }

    private fun IrType.listElementType(): String? {
        if (!isList()) return null
        val simple = this as? IrSimpleType ?: return "kotlin.Any"
        val elemType = simple.arguments.firstOrNull()?.typeOrNull ?: return "kotlin.Any"
        return elemType.toTypeDescriptor()
    }

    private fun IrType.isMap(): Boolean {
        val fqn = toTypeName()
        return fqn == "kotlin.collections.Map" || fqn == "kotlin.collections.MutableMap"
    }

    private fun IrType.mapKeyType(): String? {
        if (!isMap()) return null
        val simple = this as? IrSimpleType ?: return "kotlin.Any"
        val keyType = simple.arguments.getOrNull(0)?.typeOrNull ?: return "kotlin.Any"
        return keyType.toTypeDescriptor()
    }

    private fun IrType.mapValueType(): String? {
        if (!isMap()) return null
        val simple = this as? IrSimpleType ?: return "kotlin.Any"
        val valType = simple.arguments.getOrNull(1)?.typeOrNull ?: return "kotlin.Any"
        return valType.toTypeDescriptor()
    }

    // Recursive element-type descriptor consumed by the C++ runtime to (un)wrap
    // collection elements: a primitive/@RDMA FQN, `list:<elem>` for a nested list,
    // or `map:<key>:<value>` for a map.
    private fun IrType.toTypeDescriptor(): String {
        val fqn = toTypeName()
        if (fqn == "kotlin.collections.List" || fqn == "kotlin.collections.MutableList") {
            val elem = (this as? IrSimpleType)?.arguments?.firstOrNull()?.typeOrNull
                ?: return "list:kotlin.Any"
            return "list:" + elem.toTypeDescriptor()
        }
        if (fqn == "kotlin.collections.Map" || fqn == "kotlin.collections.MutableMap") {
            val args = (this as? IrSimpleType)?.arguments?.mapNotNull { it.typeOrNull } ?: emptyList()
            val key = args.getOrNull(0) ?: return "map:kotlin.Any:kotlin.Any"
            val value = args.getOrNull(1) ?: return "map:${key.toTypeDescriptor()}:kotlin.Any"
            return "map:" + key.toTypeDescriptor() + ":" + value.toTypeDescriptor()
        }
        return fqn
    }
}
