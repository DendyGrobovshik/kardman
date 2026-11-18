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

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irLong
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Hotfix dispatch slots (docs/design/runtime_rework.md §6.2): every slotted top-level
 * `@RDMA` function gets a private static field with its `RdmaSlotCell` (generated into
 * the module package by the kernel Gradle plugin) and a prologue
 *
 * ```
 * if (cell.blockId != 0L) {
 *     val r = rdmaSlotInvoke(cell, arrayOf(p0, p1, …))
 *     if (rdmaSlotHandled(r)) return r as T
 * }
 * ```
 *
 * Not hotfixed, the cost is a static load, a volatile load and a predictable branch.
 * Not slotted (yet): `@Composable` widgets, `suspend` functions, and functions with a
 * function-typed parameter or result (kernel → JS lambda marshalling does not exist).
 */
class RdmaSlotTransformer(private val pluginContext: IrPluginContext, kernelPackage: String) {
    private val pkg = FqName(kernelPackage)
    private val cellClass: IrClassSymbol? = pluginContext.referenceClass(ClassId(pkg, Name.identifier("RdmaSlotCell")))
    private val invokeFn: IrSimpleFunctionSymbol? = topLevel("rdmaSlotInvoke")
    private val handledFn: IrSimpleFunctionSymbol? = topLevel("rdmaSlotHandled")

    private fun topLevel(name: String): IrSimpleFunctionSymbol? =
        pluginContext.referenceFunctions(CallableId(pkg, Name.identifier(name))).firstOrNull { !it.owner.isExpect }

    fun transform(moduleFragment: IrModuleFragment, functions: List<RdmaFunctionInfo>) {
        val cell = cellClass ?: return
        val invoke = invokeFn ?: return
        val handled = handledFn ?: return
        val eligible = functions.filter(::isSlottable).associateBy { it.qualifiedName }
        if (eligible.isEmpty()) return
        for (file in moduleFragment.files) {
            var index = 0
            for (fn in file.declarations.filterIsInstance<IrSimpleFunction>()) {
                if (!fn.hasAnnotation(RDMA_ANNOTATION)) continue
                val info = eligible[fn.fqNameWhenAvailable?.asString() ?: continue] ?: continue
                inject(file, fn, info, cell, invoke, handled, index++)
            }
        }
    }

    private fun isSlottable(info: RdmaFunctionInfo): Boolean =
        !info.composable && !info.suspend &&
            info.parameters.none { hasFunction(it.type) } && !hasFunction(info.returnType)

    private fun hasFunction(t: RdmaTypeRef): Boolean = when (val ty = t.type) {
        is RdmaType.FunctionType -> true
        is RdmaType.ListType -> hasFunction(ty.element)
        is RdmaType.MapType -> hasFunction(ty.key) || hasFunction(ty.value)
        else -> false
    }

    private fun inject(
        file: IrFile,
        function: IrSimpleFunction,
        info: RdmaFunctionInfo,
        cell: IrClassSymbol,
        invokeFn: IrSimpleFunctionSymbol,
        handledFn: IrSimpleFunctionSymbol,
        index: Int,
    ) {
        val body = function.body as? IrBlockBody ?: return
        val ctor = cell.owner.primaryConstructor ?: return
        val blockIdProp = cell.owner.properties.firstOrNull { it.name.asString() == "blockId" } ?: return
        val params = function.parameters
        if (params.size != info.parameters.size) return // receivers or synthetic params: not a plain top-level call

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val field = pluginContext.irFactory.buildField {
            name = Name.identifier("rdmaSlot\$${function.name.asString()}\$$index")
            type = cell.defaultType
            isStatic = true
            isFinal = true
            visibility = DescriptorVisibilities.PRIVATE
        }.also { f ->
            f.parent = file
            f.initializer = pluginContext.irFactory.createExpressionBody(
                f.startOffset,
                f.endOffset,
                builder.irCallConstructor(ctor.symbol, emptyList()).apply {
                    arguments[0] = builder.irString(info.qualifiedName)
                    arguments[1] = builder.irString(RdmaCallbackDescs.joined(info.parameters.map { it.type }))
                    arguments[2] = builder.irString(RdmaCallbackDescs.of(info.returnType))
                },
            )
        }
        file.declarations.add(field)

        fun readCell(): IrExpression = builder.irGetField(null, field)
        fun readBlockId(): IrExpression {
            val getter = blockIdProp.getter
            return if (getter != null) {
                builder.irCall(getter.symbol).apply { arguments[0] = readCell() }
            } else {
                builder.irGetField(readCell(), blockIdProp.backingField!!)
            }
        }

        val anyN = pluginContext.irBuiltIns.anyNType
        val unitType = pluginContext.irBuiltIns.unitType
        val prologue = builder.irIfThen(
            unitType,
            builder.irNotEquals(readBlockId(), builder.irLong(0)),
            builder.irBlock {
                val args = irCall(pluginContext.irBuiltIns.arrayOf).apply {
                    typeArguments[0] = anyN
                    arguments[0] = irVararg(anyN, params.map { irGet(it) })
                }
                val result = irTemporary(
                    irCall(invokeFn).apply {
                        arguments[0] = readCell()
                        arguments[1] = args
                    },
                    nameHint = "rdmaSlotResult",
                )
                val handled = irCall(handledFn).apply {
                    arguments[0] = irGet(result)
                }
                val ret = if (function.returnType == unitType) {
                    irReturn(irUnit())
                } else {
                    irReturn(irAs(irGet(result), function.returnType))
                }
                +irIfThen(unitType, handled, ret)
            },
        )

        function.body = builder.irBlockBody(body.startOffset, body.endOffset) {
            +prologue
            body.statements.forEach { +it }
        }
    }

    private companion object {
        val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")
    }
}
