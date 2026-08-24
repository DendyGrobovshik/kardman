package io.github.dendygrobovshik.kardman.kernel

import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

private val RDMA_WIDGET_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMAWidget")

object RdmaWidgetExtractor {
    fun extract(moduleFragment: IrModuleFragment): List<String> {
        val result = mutableListOf<String>()
        for (file in moduleFragment.files) {
            for (declaration in file.declarations) {
                val fn = declaration as? IrSimpleFunction ?: continue
                if (!fn.hasAnnotation(RDMA_WIDGET_ANNOTATION)) continue
                val fqn = fn.fqNameWhenAvailable?.asString() ?: fn.name.asString()
                result.add(fqn)
            }
        }
        return result.sorted()
    }
}
