package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName
import java.io.File

object RdmaFunctionExtractor {
    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")
    private val COMPOSABLE_ANNOTATION = FqName("androidx.compose.runtime.Composable")
    private const val COMPOSER_FQN = "androidx.compose.runtime.Composer"

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
                // The compose compiler injects synthetic `$composer`/`$changed` params and
                // rewrites `@Composable () -> Unit` types to `Function2<Composer, Int, Unit>`;
                // strip both so the manifest records the logical widget signature.
                val parameters = fn.parameters
                    .filter { it.kind == IrParameterKind.Regular }
                    .filter { !it.name.asString().startsWith("$") }
                    .map { p -> toParameterInfo(p) }
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

    /**
     * Classifies a single parameter into VALUE / CONTENT / CALLBACK.
     *
     * The compose compiler lowers `@Composable (P..) -> R` to `Function<N+2><P.., Composer, Int, R>`,
     * so a function type that contains a `Composer` parameter is a content lambda; a function type
     * without one is a plain callback. We record the logical (un-lowered) function type.
     */
    private fun toParameterInfo(p: IrValueParameter): RdmaParameterInfo {
        val simple = p.type as? IrSimpleType ?: return RdmaParameterInfo(p.name.asString(), RdmaTypeParser.toRdmaTypeRef(p.type))
        val fqn = p.type.classFqName?.asString()
        if (fqn == null || !fqn.startsWith("kotlin.Function")) {
            return RdmaParameterInfo(p.name.asString(), RdmaTypeParser.toRdmaTypeRef(p.type))
        }
        val args = simple.arguments.mapNotNull { it.typeOrNull }
        if (args.isEmpty()) return RdmaParameterInfo(p.name.asString(), RdmaTypeParser.toRdmaTypeRef(p.type))
        val composerIdx = args.dropLast(1).indexOfFirst { it.classFqName?.asString() == COMPOSER_FQN }
        return if (composerIdx >= 0) {
            val userParams = args.subList(0, composerIdx).map { RdmaTypeParser.toRdmaTypeRef(it) }
            RdmaParameterInfo(
                name = p.name.asString(),
                type = RdmaTypeRef(RdmaType.FunctionType(userParams, RdmaTypeRef(RdmaType.UnitType))),
                composable = true,
            )
        } else {
            val userParams = args.dropLast(1).map { RdmaTypeParser.toRdmaTypeRef(it) }
            val returnType = args.lastOrNull()?.let { RdmaTypeParser.toRdmaTypeRef(it) } ?: RdmaTypeRef(RdmaType.UnitType)
            RdmaParameterInfo(
                name = p.name.asString(),
                type = RdmaTypeRef(RdmaType.FunctionType(userParams, returnType)),
                composable = false,
            )
        }
    }
}
