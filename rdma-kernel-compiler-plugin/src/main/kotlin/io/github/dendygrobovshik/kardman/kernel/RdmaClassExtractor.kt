package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
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
                )
            }
            .toList()

        return RdmaClassInfo(packageName, className, qualifiedName, constructors, methods, properties)
    }

    private fun IrValueParameter.toParameterInfo(): ParameterInfo {
        val typeName = type.toTypeName()
        return ParameterInfo(
            name = name.asString(),
            type = typeName,
            nullable = type.isNullable(),
            isList = type.isList(),
            listElementType = type.listElementType(),
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
        return elemType.toTypeName()
    }
}
