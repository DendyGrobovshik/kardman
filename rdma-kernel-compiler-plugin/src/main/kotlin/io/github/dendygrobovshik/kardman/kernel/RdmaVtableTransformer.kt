package io.github.dendygrobovshik.kardman.kernel

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class RdmaVtableTransformer(private val pluginContext: IrPluginContext) {

    private val dispatchFn: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("com.example.kernel"), Name.identifier("rdmaVtableDispatch"))
        ).firstOrNull()
    }

    fun transform(cls: IrClass, info: RdmaClassInfo) {
        val openMethods = info.methods.filter { it.isOpen }
        if (openMethods.isEmpty()) return

        val fn = dispatchFn ?: return
        val field = cls.addField("__vtable", pluginContext.irBuiltIns.longType, DescriptorVisibilities.PUBLIC)

        val functionsByName = cls.functions.filter {
            it.modality == Modality.OPEN && it.overriddenSymbols.isEmpty()
        }.groupBy { it.name.asString() }

        for (method in openMethods) {
            val irFn = functionsByName[method.name]?.firstOrNull() ?: continue
            inject(irFn, field, fn, method.vtableId)
        }
    }

    private fun inject(function: IrSimpleFunction, field: IrField, dispatchFn: IrSimpleFunctionSymbol, vtableId: Int) {
        val body = function.body ?: return
        if (body !is org.jetbrains.kotlin.ir.expressions.IrBlockBody) return
        val thisReceiver = function.dispatchReceiverParameter ?: return
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)

        val vtableRead = builder.irGetField(builder.irGet(thisReceiver), field)
        val condition = builder.irNotEquals(vtableRead, builder.irLong(0))
        val dispatchCall = builder.irCall(dispatchFn).apply {
            arguments[0] = vtableRead
            arguments[1] = builder.irInt(vtableId)
        }

        val unitType = pluginContext.irBuiltIns.unitType
        val injected = if (function.returnType == unitType) {
            builder.irIfThen(unitType, condition, builder.irBlock { +dispatchCall })
        } else {
            val returnType = function.returnType
            builder.irIfThen(unitType, condition, builder.irBlock {
                val temp = irTemporary(dispatchCall, nameHint = "__r")
                val nullCheck = irNotEquals(irGet(temp), irNull())
                val casted = irAs(irGet(temp), returnType)
                val returnStmt = irReturn(casted)
                +irIfThen(unitType, nullCheck, irBlock { +returnStmt })
            })
        }

        function.body = builder.irBlockBody(body.startOffset, body.endOffset) {
            +injected
            body.statements.forEach { +it }
        }
    }
}
