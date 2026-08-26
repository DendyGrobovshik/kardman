package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName
import java.io.File

object RdmaFunctionExtractor {
    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")
    private val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")

    fun extract(moduleFragment: IrModuleFragment): List<RdmaFunctionInfo> {
        val result = mutableListOf<RdmaFunctionInfo>()
        for (file in moduleFragment.files) {
            for (declaration in file.declarations) {
                val fn = declaration as? IrSimpleFunction ?: continue
                if (!fn.hasAnnotation(RDMA_ANNOTATION)) continue
                val qualifiedName = fn.fqNameWhenAvailable?.asString() ?: fn.name.asString()
                val packageName = fn.fqNameWhenAvailable?.parent()?.asString() ?: ""
                val fileBase = File(file.fileEntry.name).nameWithoutExtension
                val facadeClass = if (packageName.isEmpty()) "${fileBase}Kt" else "$packageName.${fileBase}Kt"
                val composable = fn.hasAnnotation(COMPOSABLE_ANNOTATION)
                val parameters = if (composable) {
                    emptyList<RdmaParameterInfo>()
                } else {
                    fn.parameters.filter { it.kind == IrParameterKind.Regular }.map { p ->
                        RdmaParameterInfo(p.name.asString(), RdmaTypeParser.toRdmaTypeRef(p.type))
                    }
                }
                val returnType = if (composable) {
                    RdmaTypeRef(RdmaType.UnitType)
                } else {
                    RdmaTypeParser.toRdmaTypeRef(fn.returnType)
                }
                result += RdmaFunctionInfo(fn.name.asString(), qualifiedName, facadeClass, composable, parameters, returnType)
            }
        }
        return result
    }
}
