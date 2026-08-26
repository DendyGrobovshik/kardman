package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
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
            fqn.startsWith("kotlin.Function") ->
                (type as? IrSimpleType)?.toFunctionType() ?: anyRef().type
            else -> RdmaType.Ref(fqn)
        }
        return RdmaTypeRef(kind, nullable)
    }

    private fun anyRef() = RdmaTypeRef(RdmaType.Ref("kotlin.Any"))

    private fun IrSimpleType.toElementTypeRef(): RdmaTypeRef =
        arguments.firstOrNull()?.typeOrNull?.let { toRdmaTypeRef(it) } ?: anyRef()

    private fun IrSimpleType.toFunctionType(): RdmaType.FunctionType {
        val args = arguments.mapNotNull { it.typeOrNull }
        val returnType = if (args.isEmpty()) RdmaTypeRef(RdmaType.UnitType) else toRdmaTypeRef(args.last())
        val parameters = if (args.size <= 1) emptyList() else args.dropLast(1).map { toRdmaTypeRef(it) }
        return RdmaType.FunctionType(parameters, returnType)
    }
}

object RdmaTypeValidator {
    fun validate(type: RdmaTypeRef, rdmaClasses: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        fun check(t: RdmaType, path: String) {
            when (t) {
                is RdmaType.Primitive, is RdmaType.UnitType -> Unit
                is RdmaType.Ref -> if (t.fqn !in rdmaClasses) {
                    errors += "$path: '${t.fqn}' is not a @RDMA type; only @RDMA classes, primitives, functions and lists can cross the runtime boundary"
                }
                is RdmaType.ListType -> check(t.element.type, "$path[]")
                is RdmaType.FunctionType -> {
                    t.parameters.forEachIndexed { i, p -> check(p.type, "$path.param$i") }
                    check(t.returnType.type, "$path.return")
                }
            }
        }
        check(type.type, "type")
        return errors
    }

    fun validateFunction(function: RdmaFunctionInfo, rdmaClasses: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        function.parameters.forEach { p ->
            errors += validate(p.type, rdmaClasses).map { "function ${function.qualifiedName}: parameter '${p.name}' $it" }
        }
        errors += validate(function.returnType, rdmaClasses).map { "function ${function.qualifiedName}: $it" }
        return errors
    }
}
