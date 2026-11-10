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

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirEnumEntrySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors

class RdmaPluginAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers = setOf(RdmaPluginFileChecker, RdmaPluginComposeImportChecker)
        override val regularClassCheckers = setOf(RdmaPluginRegularClassChecker)
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers = setOf(RdmaPluginFunctionCallChecker, RdmaPluginWidgetCallChecker, RdmaPluginComposeCallChecker, RdmaPluginSuspendMethodCallChecker, RdmaPluginEnumFunctionChecker)
        override val propertyAccessExpressionCheckers = setOf(RdmaPluginPropertyAccessChecker, RdmaPluginEnumChecker)
        override val variableAssignmentCheckers = setOf(RdmaPluginVariableAssignmentChecker)
    }
}

private fun filePathOf(context: CheckerContext): String? = context.containingFile?.path

/**
 * Returns the FQN of the `@RDMA` enum this expression's type refers to, or null.
 * Covers both enum entry accesses (`HttpMethod.GET` — its type is the enum) and
 * variables holding an enum value received from the kernel.
 */
private fun enumFqnOf(expression: FirExpression): String? {
    val classId = (expression.resolvedType as? ConeClassLikeType)?.lookupTag?.classId ?: return null
    val fqn = classId.asSingleFqName().asString()
    return if (RdmaPluginTransformState.isEnumQualifiedName(fqn)) fqn else null
}

private fun enumEntryLiteral(expression: FirPropertyAccessExpression): String? {
    val symbol = expression.calleeReference.toResolvedSymbol<FirCallableSymbol<*>>() as? FirEnumEntrySymbol
        ?: return null
    val classId = symbol.callableId?.classId ?: return null
    val fqn = classId.asSingleFqName().asString()
    if (!RdmaPluginTransformState.isEnumQualifiedName(fqn)) return null
    return "\"${symbol.name.asString()}\""
}

/**
 * Source text of a call argument with registered `@RDMA` enum entries inlined as
 * string literals (entry names are compile-time known, so no RDMA round trip).
 */
private fun argTextOf(arg: FirExpression, text: String): String {
    val src = arg.source ?: return ""
    val raw = text.substring(src.startOffset, src.endOffset).trim()
    if (arg is FirPropertyAccessExpression) {
        enumEntryLiteral(arg)?.let { return it }
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

/**
 * Reorders call arguments into the declared parameter order, resolving named arguments by
 * their declared name (`foo(b = 2, a = 1)` -> `[1, 2]`). Positional arguments fill the front
 * slots in order; omitted trailing (defaulted) parameters are left out entirely. When
 * [quoteStrings] is true, arguments bound to a `kotlin.String` parameter are re-quoted as JS
 * string literals (single quotes), matching the positional marshalling of the generated bridge.
 */
private fun orderedArgTexts(
    arguments: List<FirExpression>,
    constructorParams: List<Pair<String, String>>,
    text: String,
    quoteStrings: Boolean,
): List<String> {
    val nameToIndex = constructorParams.mapIndexed { i, (name, _) -> name to i }.toMap()
    val ordered = arrayOfNulls<String>(constructorParams.size)
    var positional = 0
    for (arg in arguments) {
        val named = arg as? FirNamedArgumentExpression
        val value = named?.expression ?: arg
        val idx = if (named != null) nameToIndex[named.name.asString()] else positional++
        if (idx == null || idx !in ordered.indices) continue
        var argText = argTextOf(value, text)
        if (quoteStrings && constructorParams[idx].second == "kotlin.String") {
            argText = "'${argText.removeSurrounding("\"")}'"
        }
        ordered[idx] = argText
    }
    return ordered.filterNotNull()
}

object RdmaPluginFileChecker : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val sourceFile = declaration.sourceFile ?: return
        val path = sourceFile.path ?: return
        val text = sourceFile.getContentsAsStream().reader().readText()
        RdmaPluginTransformState.registerText(path, text)

        for (imp in declaration.imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (!RdmaPluginTransformState.isBridgeableQualifiedName(fqn)) {
                if (RdmaPluginTransformState.isKernelPackage(fqn)) {
                    val src = imp.source
                    if (src != null) {
                        reporter.reportOn(
                            src,
                            FirErrors.UNSUPPORTED,
                            "Kernel symbol '$fqn' is not visible to this plugin (only internal kernel modules and your own user module are accessible)",
                        )
                    }
                }
                continue
            }
            val src = imp.source ?: continue
            RdmaPluginTransformState.addEdit(path, src.startOffset, endWithNewline(text, src.endOffset), "")
        }
    }
}

object RdmaPluginRegularClassChecker : FirDeclarationChecker<FirRegularClass>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirRegularClass) {
        val superRef = declaration.superTypeRefs.firstOrNull { ref ->
            val fqn = ref.coneTypeSafe<ConeClassLikeType>()?.lookupTag?.classId?.asSingleFqName()?.asString()
            fqn != null && RdmaPluginTransformState.isClassQualifiedName(fqn)
        } ?: return
        val parentSimpleName = superRef.coneTypeSafe<ConeClassLikeType>()?.lookupTag?.classId?.shortClassName?.asString() ?: return
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
            RdmaSubclass(className, qualifiedName, parentSimpleName, overrides),
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

object RdmaPluginFunctionCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirConstructorSymbol>() ?: return
        val classId = symbol.callableId.classId ?: return
        val fqn = classId.asSingleFqName().asString()
        val src = expression.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)

        val argTexts = expression.argumentList.arguments.map { argTextOf(it, text) }

        val subclass = RdmaPluginTransformState.subclassByQualifiedName(fqn)
        if (subclass != null) {
            RdmaPluginTransformState.recordUsedSymbol(fqn)
            val overridesJs = subclass.overrides.joinToString(", ") { (n, b) ->
                "$n: function() { return $b; }"
            }
            val replacement = buildString {
                append("js(\"\"\"RDMA.createWithOverrides('")
                append(subclass.parentSimpleName)
                append("', [")
                append(argTexts.joinToString(", "))
                append("], { ")
                append(overridesJs)
                append(" })\"\"\")")
            }
            RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, replacement)
            return
        }

        val type = RdmaPluginTransformState.typeByQualifiedName(fqn) ?: return
        RdmaPluginTransformState.recordUsedSymbol(fqn)
        val args = orderedArgTexts(
            expression.argumentList.arguments,
            type.constructorParams,
            text,
            quoteStrings = true,
        ).joinToString(", ")

        val replacement = "js(\"RDMA.create${type.simpleName}(${args.escapeForJs()})\")"
        // The whole-span replacement embeds the raw arg texts, so any nested edits
        // (e.g. an inlined enum entry) must be dropped to keep flush() consistent.
        RdmaPluginTransformState.removeEditsWithin(path, src.startOffset, src.endOffset)
        RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, replacement)
    }
}

object RdmaPluginWidgetCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val callableId = symbol.callableId ?: return
        val fqn = callableId.asSingleFqName().asString()
        if (!RdmaPluginTransformState.isFunctionQualifiedName(fqn)) return
        RdmaPluginTransformState.recordUsedSymbol(fqn)
        val nameSrc = expression.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        val bridgeName = RdmaPluginTransformState.bridgeNameFor(fqn)
        RdmaPluginTransformState.addEdit(path, nameSrc.startOffset, nameSrc.endOffset, bridgeName)
    }
}

/**
 * Rewrites calls to `suspend` methods of `@RDMA` classes into a generated suspend
 * bridge. A suspend method cannot dispatch dynamically on the JS proxy (a JS method
 * call is not a coroutine suspension point), so `client.fetch(url)` becomes
 * `rdmaHttpClientFetch(client, url)`, which forwards to `RDMA._HttpClient_fetchAsync`.
 */
object RdmaPluginSuspendMethodCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val callableId = symbol.callableId ?: return
        val classId = callableId.classId ?: return
        val classFqn = classId.asSingleFqName().asString()
        val type = RdmaPluginTransformState.typeByQualifiedName(classFqn) ?: return
        val methodName = symbol.name.asString()
        val method = type.suspendMethods.find { it.name == methodName } ?: return

        val src = expression.source ?: return
        val nameSrc = expression.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)

        val receiver = text.substring(src.startOffset, nameSrc.startOffset).trim().trimEnd('.')
        val argTexts = expression.argumentList.arguments.map { argTextOf(it, text) }
        val bridge = RdmaPluginTransformState.methodBridgeName(type.simpleName, methodName)
        val replacement = "$bridge(${(listOf(receiver) + argTexts).joinToString(", ")})"

        RdmaPluginTransformState.removeEditsWithin(path, src.startOffset, src.endOffset)
        RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, replacement)
        RdmaPluginTransformState.recordUsedSymbol(classFqn)
    }
}

object RdmaPluginPropertyAccessChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirPropertySymbol>() ?: return
        val classId = symbol.callableId?.classId ?: return
        // Companion object static: `Alignment.Center` -> `rdmaAlignmentCenter()`.
        if (classId.shortClassName.asString() == "Companion") {
            val outer = classId.outerClassId ?: return
            val outerFqn = outer.asSingleFqName().asString()
            val type = RdmaPluginTransformState.typeByQualifiedName(outerFqn) ?: return
            val propName = symbol.name.asString()
            if (propName !in type.statics) return
            RdmaPluginTransformState.recordUsedSymbol(outerFqn)
            val accessSrc = expression.source ?: return
            val path = filePathOf(context) ?: return
            val bridge = RdmaPluginTransformState.staticBridgeNameFor(type.simpleName, propName)
            RdmaPluginTransformState.addEdit(path, accessSrc.startOffset, accessSrc.endOffset, "$bridge()")
            return
        }

        val fqn = classId.asSingleFqName().asString()
        val type = RdmaPluginTransformState.typeByQualifiedName(fqn) ?: return
        val propName = symbol.name.asString()
        if (type.properties.none { it.first == propName }) return
        RdmaPluginTransformState.recordUsedSymbol(fqn)
        val accessSrc = expression.source ?: return
        val nameSrc = expression.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)

        var after = accessSrc.endOffset
        while (after < text.length && text[after].isWhitespace()) after++
        if (after < text.length && text[after] == '=') return

        val receiver = text.substring(accessSrc.startOffset, nameSrc.startOffset)
        val getter = "get${propName.replaceFirstChar { it.uppercase() }}"
        RdmaPluginTransformState.addEdit(path, accessSrc.startOffset, accessSrc.endOffset, "$receiver$getter()")
    }
}

object RdmaPluginVariableAssignmentChecker : FirExpressionChecker<FirVariableAssignment>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        val lValue = expression.lValue as? FirPropertyAccessExpression ?: return
        val symbol = lValue.calleeReference.toResolvedSymbol<FirPropertySymbol>() ?: return
        val classId = symbol.callableId?.classId ?: return
        val fqn = classId.asSingleFqName().asString()
        val type = RdmaPluginTransformState.typeByQualifiedName(fqn) ?: return
        val propName = symbol.name.asString()
        val prop = type.properties.find { it.first == propName } ?: return
        if (!prop.second) return
        RdmaPluginTransformState.recordUsedSymbol(fqn)
        val src = expression.source ?: return
        val nameSrc = lValue.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)
        val receiver = text.substring(src.startOffset, nameSrc.startOffset)
        val rValueText = argTextOf(expression.rValue, text)
        val setter = "set${propName.replaceFirstChar { it.uppercase() }}"
        RdmaPluginTransformState.removeEditsWithin(path, src.startOffset, src.endOffset)
        RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, "$receiver$setter($rValueText)")
    }
}

/**
 * Rewrites `@RDMA` enum usages in the plugin:
 *  - `HttpMethod.GET`            -> `"GET"`               (entry name is compile-time known)
 *  - `HttpMethod.GET.name`       -> `"GET"`               (identity under name representation)
 *  - `HttpMethod.GET.ordinal`    -> `rdmaHttpMethodOrdinal("GET")`
 *  - `m.name` / `m.ordinal` for an enum-typed receiver — same as above
 *
 * `when` needs no special handling: the rewritten conditions are string literals and
 * the subject is a dynamic string from RDMA, so Kotlin/JS compares plain JS strings.
 */
object RdmaPluginEnumChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val path = filePathOf(context) ?: return
        val src = expression.source ?: return

        // (1) `.name` / `.ordinal` member access on an enum value.
        val symbol = expression.calleeReference.toResolvedSymbol<FirCallableSymbol<*>>()
        if (symbol != null) {
            val member = symbol.name.asString()
            if (member == "name" || member == "ordinal") {
                val receiver = expression.explicitReceiver ?: return
                val enumFqn = enumFqnOf(receiver) ?: return
                val receiverSrc = receiver.source ?: return
                val text = RdmaPluginTransformState.textOf(path)
                val receiverText = text.substring(receiverSrc.startOffset, receiverSrc.endOffset).trim()
                // A value expression usable as the entry name: an entry access inlines
                // to its string literal; a variable reference passes through as-is.
                val receiverValueText = (receiver as? FirPropertyAccessExpression)
                    ?.let { enumEntryLiteral(it) } ?: receiverText
                val replacement = if (member == "name") {
                    // Under the name-string representation the value IS the name.
                    receiverValueText
                } else {
                    "${RdmaPluginTransformState.enumOrdinalBridgeName(enumFqn.substringAfterLast('.'))}($receiverValueText)"
                }
                // The whole-span rewrite covers any nested entry access (e.g. the
                // `HttpMethod.GET` inside `HttpMethod.GET.ordinal`).
                RdmaPluginTransformState.removeEditsWithin(path, src.startOffset, src.endOffset)
                RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, replacement)
                RdmaPluginTransformState.recordUsedSymbol(enumFqn)
                return
            }
        }

        // (2) Bare enum entry access: `HttpMethod.GET` -> `"GET"`.
        val literal = enumEntryLiteral(expression) ?: return
        // Skip if a whole-span rewrite (e.g. `.ordinal`) already covers this span.
        if (RdmaPluginTransformState.isCovered(path, src.startOffset, src.endOffset)) return
        RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, literal)
    }
}

/**
 * `HttpMethod.valueOf(x)` -> `rdmaHttpMethodValueOf(x)` (validated by the kernel) and
 * `HttpMethod.values()` -> compile error (deferred).
 */
object RdmaPluginEnumFunctionChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val classId = symbol.callableId?.classId ?: return
        val enumFqn = classId.asSingleFqName().asString()
        val enum = RdmaPluginTransformState.enumByQualifiedName(enumFqn) ?: return
        val path = filePathOf(context) ?: return
        val src = expression.source ?: return
        when (symbol.name.asString()) {
            "valueOf" -> {
                val text = RdmaPluginTransformState.textOf(path)
                val argTexts = expression.argumentList.arguments.map { argTextOf(it, text) }
                val bridge = RdmaPluginTransformState.enumValueOfBridgeName(enum.simpleName)
                RdmaPluginTransformState.removeEditsWithin(path, src.startOffset, src.endOffset)
                RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, "$bridge(${argTexts.joinToString(", ")})")
                RdmaPluginTransformState.recordUsedSymbol(enumFqn)
            }
            "values" -> reporter.reportOn(
                src,
                FirErrors.UNSUPPORTED,
                "Enum.values() is not supported in the plugin yet — use explicit entries or a kernel-side @RDMA helper",
            )
        }
    }
}

private fun String.escapeForJs(): String = replace("\\", "\\\\").replace("\"", "\\\"")
