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
import org.jetbrains.kotlin.ir.builders.irAs
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR pass that fills in the bodies of the C-compatible widget entry stubs generated
 * by [CAbiWidgetGenerator]. The compose compiler has already lowered every
 * `@Composable` widget to `(args..., $composer: Composer, $changed: Int)`, so this
 * pass emits a direct IR call to the lowered widget, reading the composer from the
 * shim's global and passing `changed = 0`.
 *
 * The content/callback lambdas are produced by the source-generated helper
 * functions (also emitted by [CAbiWidgetGenerator]); this pass only calls them.
 */
class CAbiWidgetIrGenerator(
    private val pluginContext: IrPluginContext,
    private val moduleFragment: IrModuleFragment,
    private val moduleId: String,
    private val kernelPackage: String,
) {
    private val widgetEntriesPackage = "$kernelPackage.rdma"
    private val prefix: String = CAbi.prefix(moduleId)

    private val rdmaGetCurrentComposer: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("io.github.dendygrobovshik.kardman.runtime"), Name.identifier("rdmaGetCurrentComposer")),
        ).firstOrNull()
    }

    private val rdmaToKString: IrSimpleFunctionSymbol? by lazy {
        pluginContext.referenceFunctions(
            CallableId(FqName("io.github.dendygrobovshik.kardman.runtime"), Name.identifier("rdmaToKString")),
        ).firstOrNull()
    }

    private val composerType by lazy {
        pluginContext.referenceClass(ClassId.topLevel(FqName("androidx.compose.runtime.Composer")))!!.owner.defaultType
    }

    private sealed class WParam {
        abstract val name: String
        data class Value(override val name: String, val jvmType: String) : WParam()
        data class Ref(override val name: String, val fqn: String, val nullable: Boolean) : WParam()
        data class Content(override val name: String) : WParam()
        data class Callback(override val name: String) : WParam()
    }

    fun generate(widgets: List<RdmaFunctionInfo>) {
        if (widgets.isEmpty()) return
        val getComposer = rdmaGetCurrentComposer ?: return
        val toKString = rdmaToKString ?: return

        val topLevel = moduleFragment.files
            .flatMap { it.declarations }
            .filterIsInstance<IrSimpleFunction>()
        val byName = topLevel.associateBy { it.name.asString() }
        val byFqn = topLevel.associateBy { it.fqNameWhenAvailable?.asString() }

        for (fn in widgets) {
            val stubName = prefix + "compose" + fn.name
            val stub = byName[stubName] ?: continue // generated source not yet compiled this pass
            val widget = byFqn[fn.qualifiedName] ?: continue

            val params = classify(fn)
            val helpers = resolveHelpers(fn, params, byName) ?: continue // helpers not yet resolvable

            injectBody(stub, widget.symbol, params, helpers, getComposer, toKString)
        }
    }

    private fun classify(fn: RdmaFunctionInfo): List<WParam> = fn.parameters.map { p ->
        val fnType = p.type.type as? RdmaType.FunctionType
        when {
            fnType != null && p.composable -> WParam.Content(p.name)
            fnType != null -> WParam.Callback(p.name)
            p.type.type is RdmaType.Ref -> WParam.Ref(p.name, (p.type.type as RdmaType.Ref).fqn, p.type.nullable)
            else -> WParam.Value(p.name, valueFqn(p.type.type))
        }
    }

    private fun valueFqn(t: RdmaType): String = when (t) {
        is RdmaType.Primitive -> t.fqn
        is RdmaType.Ref -> t.fqn
        else -> "kotlin.Any"
    }

    private fun helperName(fn: RdmaFunctionInfo, p: WParam): String =
        prefix + "compose" + fn.name + "_" + p.name

    private fun resolveHelpers(
        fn: RdmaFunctionInfo,
        params: List<WParam>,
        byName: Map<String, IrSimpleFunction>,
    ): Map<String, IrSimpleFunctionSymbol>? {
        val result = mutableMapOf<String, IrSimpleFunctionSymbol>()
        for (p in params) {
            if (p is WParam.Content || p is WParam.Callback) {
                val sym = byName[helperName(fn, p)]?.symbol ?: return null
                result[p.name] = sym
            }
        }
        return result
    }

    private fun injectBody(
        stub: IrSimpleFunction,
        widget: IrSimpleFunctionSymbol,
        params: List<WParam>,
        helpers: Map<String, IrSimpleFunctionSymbol>,
        getComposer: IrSimpleFunctionSymbol,
        toKString: IrSimpleFunctionSymbol,
    ) {
        val builder = DeclarationIrBuilder(pluginContext, stub.symbol)
        val stubParams = stub.parameters.filter { it.kind == IrParameterKind.Regular }

        val marshaled = params.mapIndexed { i, p -> marshal(builder, p, stubParams[i], helpers, toKString) }
        val composer = builder.irAs(builder.irCall(getComposer), composerType)

        val call = builder.irCall(widget)
        marshaled.forEachIndexed { i, expr -> call.arguments[i] = expr }
        // The compose compiler appends `$composer` (nullable Composer) followed by one
        // or more `$changed` Int params (split when there are several lambda params).
        // Fill them from the actual lowered signature rather than assuming a count of 2.
        val widgetValueParams = widget.owner.parameters.filter { it.kind == IrParameterKind.Regular }
        var argIdx = marshaled.size
        for (j in marshaled.size until widgetValueParams.size) {
            call.arguments[argIdx] = if (j == marshaled.size) composer else builder.irInt(0)
            argIdx++
        }

        stub.body = builder.irBlockBody {
            +call
        }
    }

    private fun marshal(
        builder: DeclarationIrBuilder,
        p: WParam,
        stubParam: IrValueParameter,
        helpers: Map<String, IrSimpleFunctionSymbol>,
        toKString: IrSimpleFunctionSymbol,
    ): IrExpression = when (p) {
        is WParam.Value -> when (p.jvmType) {
            "kotlin.String" -> builder.irCall(toKString).apply { arguments[0] = builder.irGet(stubParam) }
            else -> builder.irGet(stubParam)
        }
        is WParam.Ref -> marshalRef(builder, p, stubParam)
        is WParam.Content, is WParam.Callback -> {
            val helper = helpers[p.name]!!
            builder.irCall(helper).apply { arguments[0] = builder.irGet(stubParam) }
        }
    }

    private fun marshalRef(builder: DeclarationIrBuilder, p: WParam.Ref, stubParam: IrValueParameter): IrExpression {
        // handle.obj<Fqn>() == (asStableRef<Any>().get() as Fqn)
        val asStableRef = pluginContext.referenceFunctions(
            CallableId(FqName("kotlinx.cinterop"), Name.identifier("asStableRef")),
        ).firstOrNull() ?: error("cannot resolve kotlinx.cinterop.asStableRef")
        val stableRefType = pluginContext.referenceClass(
            ClassId.topLevel(FqName("kotlinx.cinterop.StableRef")),
        )!!.owner.defaultType
        val ref = builder.irAs(builder.irCall(asStableRef).apply { arguments[0] = builder.irGet(stubParam) }, stableRefType)
        val get = pluginContext.referenceFunctions(
            CallableId(FqName("kotlinx.cinterop"), FqName("StableRef"), Name.identifier("get")),
        ).firstOrNull() ?: error("cannot resolve StableRef.get")
        val value = builder.irCall(get).apply { dispatchReceiver = ref }
        val targetType = pluginContext.referenceClass(ClassId.topLevel(FqName(p.fqn)))!!.owner.defaultType
        return builder.irAs(value, targetType)
    }
}
