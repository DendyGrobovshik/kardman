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
import io.github.dendygrobovshik.kardman.types.RdmaTypeDescFormat
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
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Plugin overrides of `open` methods (the vtable). Every `open` method of an `@RDMA` class
 * gets the prologue
 *
 * ```
 * if (vtable != 0L) {
 *     val r = rdmaVtableDispatch(vtable, id, arrayOf(p0, …), "<param descs>", "<result desc>", RdmaSlotFallthrough)
 *     if (rdmaSlotHandled(r)) return r as T
 * }
 * ```
 *
 * The runtime runs the override on the Hermes thread from any calling thread, marshals
 * the arguments and the result by the descriptors, and throws a JS exception as
 * `RdmaException` (docs/design/runtime_rework.md §6.4, §2.8). `RdmaSlotFallthrough` /
 * `rdmaSlotHandled` are the module's generated slot helpers.
 */
class RdmaVtableTransformer(private val pluginContext: IrPluginContext, kernelPackage: String, private val isCapi: Boolean) {
    private val pkg = FqName(kernelPackage)

    private val dispatchFn: IrSimpleFunctionSymbol? by lazy { topLevel(pkg, "rdmaVtableDispatch") }
    private val handledFn: IrSimpleFunctionSymbol? by lazy { topLevel(pkg, "rdmaSlotHandled") }
    private val fallthrough: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId(pkg, Name.identifier("RdmaSlotFallthrough")))
    }

    // C ABI only: the vtable pointer lives in a runtime registry (the IR-injected
    // `__vtable` field can't be referenced from generated Kotlin source).
    private val vtableGetFn: IrSimpleFunctionSymbol? by lazy {
        topLevel(FqName("io.github.dendygrobovshik.kardman.runtime"), "rdmaVtableGet")
    }

    private fun topLevel(pkg: FqName, name: String): IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(CallableId(pkg, Name.identifier(name))).firstOrNull { !it.owner.isExpect }

    fun transform(cls: IrClass, info: RdmaClassInfo) {
        val openMethods = info.methods.filter { it.isOpen }
        if (openMethods.isEmpty()) return
        val symbols = Symbols(dispatchFn ?: return, handledFn ?: return, fallthrough ?: return)

        val functionsByName = cls.functions.filter {
            it.modality == Modality.OPEN && it.overriddenSymbols.isEmpty()
        }.groupBy { it.name.asString() }

        if (isCapi) {
            val getFn = vtableGetFn ?: return
            for (method in openMethods) {
                val irFn = functionsByName[method.name]?.firstOrNull() ?: continue
                inject(irFn, method, symbols) { builder, thisReceiver ->
                    builder.irCall(getFn).apply { arguments[0] = builder.irGet(thisReceiver) }
                }
            }
        } else {
            val field = cls.addField("__vtable", pluginContext.irBuiltIns.longType, DescriptorVisibilities.PUBLIC)
            for (method in openMethods) {
                val irFn = functionsByName[method.name]?.firstOrNull() ?: continue
                inject(irFn, method, symbols) { builder, thisReceiver ->
                    builder.irGetField(builder.irGet(thisReceiver), field)
                }
            }
        }
    }

    private class Symbols(val dispatch: IrSimpleFunctionSymbol, val handled: IrSimpleFunctionSymbol, val fallthrough: IrClassSymbol)

    private fun inject(
        function: IrSimpleFunction,
        method: MethodInfo,
        symbols: Symbols,
        vtableRead: (DeclarationIrBuilder, IrValueParameter) -> IrExpression,
    ) {
        val body = function.body ?: return
        if (body !is org.jetbrains.kotlin.ir.expressions.IrBlockBody) return
        val thisReceiver = function.dispatchReceiverParameter ?: return
        val valueParams = function.parameters.filter { it.kind == IrParameterKind.Regular }
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val anyN = pluginContext.irBuiltIns.anyNType
        val unitType = pluginContext.irBuiltIns.unitType

        val paramDescs = method.parameters.joinToString(",") { paramDesc(it) }
        val resultDesc = resultDesc(method)

        val injected = builder.irIfThen(
            unitType,
            builder.irNotEquals(vtableRead(builder, thisReceiver), builder.irLong(0)),
            builder.irBlock {
                val args = irCall(pluginContext.irBuiltIns.arrayOf).apply {
                    typeArguments[0] = anyN
                    arguments[0] = irVararg(anyN, valueParams.map { irGet(it) })
                }
                val result = irTemporary(
                    irCall(symbols.dispatch).apply {
                        arguments[0] = vtableRead(builder, thisReceiver)
                        arguments[1] = irInt(method.vtableId)
                        arguments[2] = args
                        arguments[3] = irString(paramDescs)
                        arguments[4] = irString(resultDesc)
                        arguments[5] = irGetObject(symbols.fallthrough)
                    },
                    nameHint = "rdmaVtableResult",
                )
                val handled = irCall(symbols.handled).apply { arguments[0] = irGet(result) }
                val ret = if (function.returnType == unitType) irReturn(irUnit()) else irReturn(irAs(irGet(result), function.returnType))
                +irIfThen(unitType, handled, ret)
            },
        )

        function.body = builder.irBlockBody(body.startOffset, body.endOffset) {
            +injected
            body.statements.forEach { +it }
        }
    }

    private fun leaf(fqn: String, nullable: Boolean): String =
        RdmaTypeDescFormat.leaf(fqn, nullable, isEnum = RdmaCallbackDescs.isEnum(fqn))

    private fun nested(desc: String): String =
        if (desc.startsWith("list:") || desc.startsWith("map:")) desc else leaf(desc, false)

    private fun flatDesc(type: String, nullable: Boolean, isList: Boolean, elem: String?, isMap: Boolean, key: String?, value: String?): String =
        when {
            isList -> RdmaTypeDescFormat.list(nested(elem ?: "kotlin.Any"))
            isMap -> RdmaTypeDescFormat.map(nested(key ?: "kotlin.Any"), nested(value ?: "kotlin.Any"))
            type == "kotlin.Unit" -> "kotlin.Unit"
            else -> leaf(type, nullable)
        }

    private fun paramDesc(p: ParameterInfo) =
        flatDesc(p.type, p.nullable, p.isList, p.listElementType, p.isMap, p.mapKeyType, p.mapValueType)

    private fun resultDesc(m: MethodInfo) =
        flatDesc(m.returnType, m.nullableReturn, m.isList, m.listElementType, m.isMap, m.mapKeyType, m.mapValueType)
}
