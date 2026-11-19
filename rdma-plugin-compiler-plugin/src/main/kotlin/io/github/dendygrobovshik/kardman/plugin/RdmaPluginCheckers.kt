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
package io.github.dendygrobovshik.kardman.plugin

import io.github.dendygrobovshik.kardman.types.RdmaTypeDescFormat
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirOperation
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirElseIfTrueCondition
import org.jetbrains.kotlin.fir.references.toResolvedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.type

/**
 * The plugin's JVM resolve pass (docs/design/runtime_rework.md §5.0).
 *
 * Kernel symbols are NOT rewritten: the JS compilation sees generated `inline` stubs in
 * the kernel packages ([RdmaKernelStubGenerator]) whose bodies are call form v1, so
 * plugin source (imports, constructors, properties, methods, enums, widgets, suspend
 * calls) compiles unchanged. This pass only
 *  - rewrites the Compose entry points that need compile-time knowledge (state
 *    factories get their type descriptor; effects are hosted by the kernel),
 *  - rewrites subclassing of an `open` kernel class (`RDMA.createWithOverrides`),
 *  - adds an `else` to `when` expressions over an `@RDMA` enum (exhaustive over the
 *    real enum, not over its stub),
 *  - records the kernel symbols the plugin uses (`plugin.json`),
 *  - rejects constructs that cannot work across the boundary.
 */
class RdmaPluginAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers = setOf(RdmaPluginFileChecker, RdmaPluginComposeImportChecker)
        override val regularClassCheckers = setOf(RdmaPluginRegularClassChecker)
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers = setOf(
            RdmaPluginFunctionCallChecker,
            RdmaPluginComposeEntryCallChecker,
            RdmaPluginComposeCallChecker,
            RdmaPluginEnumFunctionChecker,
            RdmaPluginReifiedKernelTypeChecker,
        )
        override val propertyAccessExpressionCheckers = setOf(RdmaPluginPropertyAccessChecker)
        override val variableAssignmentCheckers = setOf(RdmaPluginVariableAssignmentChecker)
        override val typeOperatorCallCheckers = setOf(RdmaPluginTypeOperatorChecker)
        override val whenExpressionCheckers = setOf(RdmaPluginEnumWhenChecker)
    }
}

private fun filePathOf(context: CheckerContext): String? = context.containingFile?.path

private fun fqnOf(type: ConeKotlinType?): String? =
    (type as? ConeClassLikeType)?.lookupTag?.classId?.asSingleFqName()?.asString()

/** True for an `@RDMA` class or enum visible to the plugin. */
private fun isKernelType(fqn: String?): Boolean =
    fqn != null && (RdmaPluginTransformState.isClassQualifiedName(fqn) || RdmaPluginTransformState.isEnumQualifiedName(fqn))

/**
 * Source text of a call argument with registered `@RDMA` enum entries inlined as
 * string literals (used only by the `createWithOverrides` rewrite, whose arguments are
 * embedded as JS source).
 */
private fun argTextOf(arg: FirExpression, text: String): String {
    val src = arg.source ?: return ""
    val raw = text.substring(src.startOffset, src.endOffset).trim()
    if (arg is FirPropertyAccessExpression) {
        val symbol = arg.calleeReference.toResolvedSymbol<FirCallableSymbol<*>>() as? FirEnumEntrySymbol
        val enumFqn = symbol?.callableId?.classId?.asSingleFqName()?.asString()
        if (enumFqn != null && RdmaPluginTransformState.isEnumQualifiedName(enumFqn)) {
            return "\"${symbol.name.asString()}\""
        }
    }
    return raw
}

private fun endWithNewline(text: String, end: Int): Int {
    var e = end
    while (e < text.length && text[e].isWhitespace()) {
        if (text[e] == '\n') { e++; break }
        e++
    }
    return e
}

object RdmaPluginFileChecker : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val sourceFile = declaration.sourceFile ?: return
        val path = sourceFile.path ?: return
        val text = sourceFile.getContentsAsStream().reader().readText()
        RdmaPluginTransformState.registerText(path, text)

        // Imports stay as written: the kernel stubs live in the same packages.
        for (imp in declaration.imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (RdmaPluginTransformState.isBridgeableQualifiedName(fqn)) continue
            if (!RdmaPluginTransformState.isKernelPackage(fqn)) continue
            val src = imp.source ?: continue
            reporter.reportOn(
                src,
                FirErrors.UNSUPPORTED,
                "Kernel symbol '$fqn' is not visible to this plugin (only internal kernel modules and your own user module are accessible)",
            )
        }
    }
}

/**
 * A plugin subclass of an `open` kernel class is not compiled as a class: its
 * declaration is removed and its constructor calls become `RDMA.createWithOverrides`.
 */
object RdmaPluginRegularClassChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val superRef = declaration.superTypeRefs.firstOrNull { ref ->
            RdmaPluginTransformState.isClassQualifiedName(fqnOf(ref.coneTypeSafe<ConeClassLikeType>()) ?: "")
        } ?: return
        val parentFqn = fqnOf(superRef.coneTypeSafe<ConeClassLikeType>()) ?: return
        val className = declaration.name.asString()
        val qualifiedName = declaration.symbol.classId.asSingleFqName().asString()
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)

        val overrides = declaration.symbol.declarationSymbols
            .mapNotNull { it.fir as? FirNamedFunction }
            .filter { it.status.isOverride }
            .mapNotNull { func ->
                val body = extractExpressionBody(text, func) ?: return@mapNotNull null
                func.name.asString() to body
            }

        RdmaPluginTransformState.registerSubclass(
            qualifiedName,
            RdmaSubclass(className, qualifiedName, parentFqn.substringAfterLast('.'), overrides, parentFqn),
        )

        val src = declaration.source ?: return
        RdmaPluginTransformState.addEdit(path, src.startOffset, endWithNewline(text, src.endOffset), "")
    }
}

private fun extractExpressionBody(text: String, func: FirNamedFunction): String? {
    val src = func.source ?: return null
    val funcText = text.substring(src.startOffset, src.endOffset)
    val eqIdx = funcText.lastIndexOf('=')
    if (eqIdx < 0) return null
    return funcText.substring(eqIdx + 1).trim().trimEnd()
}

/**
 * Constructor calls: records kernel class usage; a plugin subclass of an `open` kernel
 * class is constructed through `RDMA.createWithOverrides` and typed as its parent.
 */
object RdmaPluginFunctionCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirConstructorSymbol>() ?: return
        val classId = symbol.callableId.classId ?: return
        val fqn = classId.asSingleFqName().asString()
        if (RdmaPluginTransformState.isClassQualifiedName(fqn)) {
            RdmaPluginTransformState.recordUsedSymbol(fqn)
            return
        }
        // A plugin class: it may be a subclass of an `open` kernel class, whose declaration
        // can live in a file checked later — so the rewrite is decided in `flush()`, once
        // every subclass is known (checker order across files is not defined).
        val src = expression.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)
        val argTexts = expression.argumentList.arguments.map { argTextOf(it, text) }
        RdmaPluginTransformState.recordConstructorCall(path, src.startOffset, src.endOffset, fqn, argTexts)
    }
}

/**
 * Calls of named functions: records kernel function and member usage, and rewrites the
 * Compose entry points that need compile-time knowledge into the runtime bridge.
 */
object RdmaPluginComposeEntryCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val callableId = symbol.callableId
        callableId.classId?.asSingleFqName()?.asString()?.let { owner ->
            if (isKernelType(owner)) RdmaPluginTransformState.recordUsedSymbol(owner)
            return
        }
        val fqn = callableId.asSingleFqName().asString()
        if (!RdmaPluginTransformState.isFunctionQualifiedName(fqn)) return
        RdmaPluginTransformState.recordUsedSymbol(fqn)
        if (fqn == RdmaPluginTransformState.registerComponentFqn) {
            insertComponentParamDescs(expression)
            return
        }
        if (!RdmaPluginTransformState.isComposeEntryPoint(fqn)) return

        val nameSrc = expression.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        RdmaPluginTransformState.addEdit(path, nameSrc.startOffset, nameSrc.endOffset, RdmaPluginTransformState.bridgeNameFor(fqn))

        if (fqn !in RdmaPluginTransformState.stateFactoryFqns) return
        // The state factory opens an erased value channel: bake the channel's type
        // descriptor (docs/design/type_descriptors.md) from the call's static type
        // into the rewritten call — `rdmaMutableStateOf(<value>, typeDesc = "<desc>")`.
        // A type that yields no descriptor is a compile error: an erased channel
        // without a descriptor would recreate guess-by-shape.
        val src = expression.source ?: return
        val desc = stateChannelDescOf(fqn, expression)
        if (desc == null) {
            reporter.reportOn(
                src,
                FirErrors.UNSUPPORTED,
                "kardman state requires a concrete allowed value type — primitives, @RDMA classes/enums, " +
                    "List/Map of those ('Any'/'dynamic'/function types and nullable collections are not " +
                    "supported yet; docs/design/type_descriptors.md)",
            )
            return
        }
        val text = RdmaPluginTransformState.textOf(path)
        val insertAt = text.lastIndexOf(')', src.endOffset - 1)
        if (insertAt <= nameSrc.endOffset) return // unexpected call shape; leave the rename only
        RdmaPluginTransformState.addEdit(path, insertAt, insertAt, ", typeDesc = \"$desc\"")
    }
}

/**
 * `registerComponent(name) { a: A, b: B -> … }`: the host passes the component's arguments
 * through an erased channel, so the JVM pass bakes their descriptors into the call —
 * `registerComponent(name, "<descA>,<descB>") { … }` (docs/internals/compose_protocol.md).
 */
context(context: CheckerContext, reporter: DiagnosticReporter)
private fun insertComponentParamDescs(expression: FirFunctionCall) {
    val typeArgs = expression.typeArguments.mapNotNull { (it as? FirTypeProjectionWithVariance)?.typeRef?.coneType }
    if (typeArgs.isEmpty()) return
    val src = expression.source ?: return
    val descs = typeArgs.map { stateTypeDescOf(it) }
    if (descs.any { it == null }) {
        reporter.reportOn(
            src,
            FirErrors.UNSUPPORTED,
            "component parameters must have concrete boundary types — primitives, @RDMA classes/enums, " +
                "List/Map of those (docs/guide/how_to_write_plugin.md)",
        )
        return
    }
    val nameArg = expression.argumentList.arguments.firstOrNull()?.source ?: return
    val path = filePathOf(context) ?: return
    RdmaPluginTransformState.addEdit(path, nameArg.endOffset, nameArg.endOffset, ", \"${descs.joinToString(",")}\"")
}

/** Records kernel class / enum usage through properties, statics and enum entries. */
object RdmaPluginPropertyAccessChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirCallableSymbol<*>>() ?: return
        var classId = symbol.callableId?.classId ?: return
        if (classId.shortClassName.asString() == "Companion") classId = classId.outerClassId ?: return
        val fqn = classId.asSingleFqName().asString()
        if (!isKernelType(fqn)) return
        if (symbol is FirPropertySymbol && symbol.name.asString() == "entries" &&
            RdmaPluginTransformState.isEnumQualifiedName(fqn)
        ) {
            expression.source?.let {
                reporter.reportOn(
                    it,
                    FirErrors.UNSUPPORTED,
                    "Enum.entries is not supported in the plugin yet — use explicit entries or a kernel-side @RDMA helper",
                )
            }
            return
        }
        RdmaPluginTransformState.recordUsedSymbol(fqn)
    }
}

object RdmaPluginVariableAssignmentChecker : FirExpressionChecker<FirVariableAssignment>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val lValue = expression.lValue as? FirPropertyAccessExpression ?: return
        val symbol = lValue.calleeReference.toResolvedSymbol<FirPropertySymbol>() ?: return
        val fqn = symbol.callableId?.classId?.asSingleFqName()?.asString() ?: return
        if (RdmaPluginTransformState.isClassQualifiedName(fqn)) RdmaPluginTransformState.recordUsedSymbol(fqn)
    }
}

/** `HttpMethod.valueOf(x)` is served by the stub; `HttpMethod.values()` is rejected. */
object RdmaPluginEnumFunctionChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val enumFqn = symbol.callableId.classId?.asSingleFqName()?.asString() ?: return
        if (!RdmaPluginTransformState.isEnumQualifiedName(enumFqn)) return
        if (symbol.name.asString() != "values") return
        val src = expression.source ?: return
        reporter.reportOn(
            src,
            FirErrors.UNSUPPORTED,
            "Enum.values() is not supported in the plugin yet — use explicit entries or a kernel-side @RDMA helper",
        )
    }
}

private const val IS_AS_MESSAGE =
    "is/as against a kernel type is not supported in the plugin yet: a kernel object is a native proxy, " +
        "not an instance of the plugin-side class, so the check cannot hold on every host " +
        "(docs/design/runtime_rework.md §5.5). Use the static type, or a kernel-side @RDMA helper"

/** `x is Person`, `x as Person`, `x as? HttpMethod` (also inside `when`) → compile error. */
object RdmaPluginTypeOperatorChecker : FirExpressionChecker<FirTypeOperatorCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTypeOperatorCall) {
        if (expression.operation !in FirOperation.TYPES) return
        val fqn = fqnOf(expression.conversionTypeRef.coneType) ?: return
        if (!isKernelType(fqn)) return
        val src = expression.source ?: return
        reporter.reportOn(src, FirErrors.UNSUPPORTED, IS_AS_MESSAGE)
    }
}

/** `filterIsInstance<Person>()` and other reified type checks against a kernel type → compile error. */
object RdmaPluginReifiedKernelTypeChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val typeParams = symbol.typeParameterSymbols
        if (typeParams.none { it.isReified }) return
        typeParams.zip(expression.typeArguments).forEach { (param, arg) ->
            if (!param.isReified) return@forEach
            val argType = (arg as? org.jetbrains.kotlin.fir.types.FirTypeProjectionWithVariance)?.typeRef?.coneType
            if (!isKernelType(fqnOf(argType))) return@forEach
            val src = expression.source ?: return
            reporter.reportOn(src, FirErrors.UNSUPPORTED, IS_AS_MESSAGE)
            return
        }
    }
}

/**
 * A `when` over an `@RDMA` enum is exhaustive against the kernel's real enum but not
 * against the plugin stub (whose values are entry-name strings), so it gets an `else`
 * branch throwing `NoWhenBranchMatchedException`: an entry the plugin does not know fails
 * loudly. Added to every such `when`, not only to those FIR marks `usedAsExpression`: the
 * last statement of a lambda (`list.map { when (it) { … } }`) is not marked, and without
 * the branch the JS compilation would silently turn it into a statement yielding nothing.
 * `Nothing` fits both the expression and the statement position.
 */
object RdmaPluginEnumWhenChecker : FirExpressionChecker<FirWhenExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirWhenExpression) {
        val subject = expression.subjectVariable ?: return
        val enumFqn = fqnOf(subject.returnTypeRef.coneType) ?: return
        if (!RdmaPluginTransformState.isEnumQualifiedName(enumFqn)) return
        if (expression.branches.any { it.condition is FirElseIfTrueCondition }) return
        val src = expression.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)
        val close = text.lastIndexOf('}', src.endOffset - 1)
        if (close < src.startOffset) return
        RdmaPluginTransformState.addEdit(path, close, close, "else -> throw kotlin.NoWhenBranchMatchedException()\n")
        RdmaPluginTransformState.recordUsedSymbol(enumFqn)
    }
}

// ---- state channel descriptors (docs/design/type_descriptors.md)

private val statePrimitiveFqns = setOf(
    "kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Boolean", "kotlin.String",
)
private val stateListFqns = setOf(
    "kotlin.collections.List", "kotlin.collections.MutableList",
    "kotlin.collections.ArrayList", "java.util.ArrayList",
)
private val stateMapFqns = setOf(
    "kotlin.collections.Map", "kotlin.collections.MutableMap",
    "java.util.Map", "java.util.LinkedHashMap", "java.util.HashMap",
)

/**
 * The type descriptor of the channel opened by a state factory call, derived from
 * the call's static result type: `mutableStateOf(x)` yields `MutableState<T>`'s `T`,
 * `mutableStateListOf`/`mutableStateMapOf` yield `list:<elem>`/`map:<k>:<v>` from
 * their `SnapshotStateList<T>`/`SnapshotStateMap<K, V>` result. Null = the type
 * cannot open an erased channel.
 */
private fun stateChannelDescOf(fqn: String, expression: FirFunctionCall): String? {
    if (fqn == "androidx.compose.runtime.mutableIntStateOf") {
        return RdmaTypeDescFormat.leaf("kotlin.Int")
    }
    val result = expression.resolvedType as? ConeClassLikeType ?: return null
    return when (fqn) {
        "androidx.compose.runtime.mutableStateListOf" -> {
            val elem = stateTypeDescOf(result.typeArguments.getOrNull(0)?.type ?: return null)
                ?: return null
            RdmaTypeDescFormat.list(elem)
        }
        "androidx.compose.runtime.mutableStateMapOf" -> {
            val k = stateTypeDescOf(result.typeArguments.getOrNull(0)?.type ?: return null)
                ?: return null
            val v = stateTypeDescOf(result.typeArguments.getOrNull(1)?.type ?: return null)
                ?: return null
            RdmaTypeDescFormat.map(k, v)
        }
        else -> {
            val valueType = result.typeArguments.getOrNull(0)?.type
                ?: expression.argumentList.arguments.firstOrNull()?.resolvedType
            stateTypeDescOf(valueType ?: return null)
        }
    }
}

private fun stateTypeDescOf(type: ConeKotlinType): String? {
    val classType = type as? ConeClassLikeType ?: return null
    val fqn = classType.lookupTag.classId.asSingleFqName().asString()
    val nullable = type.isMarkedNullable
    return when {
        fqn in statePrimitiveFqns -> RdmaTypeDescFormat.leaf(fqn, nullable)
        RdmaPluginTransformState.isEnumQualifiedName(fqn) ->
            RdmaTypeDescFormat.leaf(fqn, nullable, isEnum = true)
        RdmaPluginTransformState.isClassQualifiedName(fqn) ->
            RdmaTypeDescFormat.leaf(fqn, nullable)
        // `EmptyList`/`EmptyMap` used to deserialize to `list:kotlin.Any`/`map:kotlin.Any:kotlin.Any`,
        // silently reviving the pre-descriptor runtime-dispatch path on the state channel. Now they
        // fall through to `null` (a compile error) since a state value must have a concrete element
        // type. Use `mutableStateOf<List<T>>(emptyList())` / `mutableStateOf<Map<K, V>>(emptyMap())`.
        fqn in stateListFqns -> {
            if (nullable) return null // nullable collection channels map poorly in the leaf-suffix grammar
            val elem = stateTypeDescOf(classType.typeArguments.getOrNull(0)?.type ?: return null)
                ?: return null
            RdmaTypeDescFormat.list(elem)
        }
        fqn in stateMapFqns -> {
            if (nullable) return null
            val k = stateTypeDescOf(classType.typeArguments.getOrNull(0)?.type ?: return null)
                ?: return null
            val v = stateTypeDescOf(classType.typeArguments.getOrNull(1)?.type ?: return null)
                ?: return null
            RdmaTypeDescFormat.map(k, v)
        }
        else -> null
    }
}
