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
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext

class RdmaPluginAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers = setOf(RdmaPluginFileChecker)
        override val regularClassCheckers = setOf(RdmaPluginRegularClassChecker)
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers = setOf(RdmaPluginFunctionCallChecker, RdmaPluginWidgetCallChecker)
        override val propertyAccessExpressionCheckers = setOf(RdmaPluginPropertyAccessChecker)
        override val variableAssignmentCheckers = setOf(RdmaPluginVariableAssignmentChecker)
    }
}

private fun filePathOf(context: CheckerContext): String? = context.containingFile?.path

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

        for (imp in declaration.imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (!RdmaPluginTransformState.isBridgeableQualifiedName(fqn)) continue
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

        val argTexts = expression.argumentList.arguments.map { arg ->
            val argSrc = arg.source
            if (argSrc != null) text.substring(argSrc.startOffset, argSrc.endOffset).trim() else ""
        }

        val subclass = RdmaPluginTransformState.subclassByQualifiedName(fqn)
        if (subclass != null) {
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
        val args = argTexts.mapIndexed { i, argText ->
            val paramType = type.constructorParams.getOrNull(i)?.second
            if (paramType == "kotlin.String") {
                "'${argText.removeSurrounding("\"")}'"
            } else {
                argText
            }
        }.joinToString(", ")

        val replacement = "js(\"RDMA.create${type.simpleName}(${args.escapeForJs()})\")"
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
        val nameSrc = expression.calleeReference.source ?: return
        val path = filePathOf(context) ?: return
        val bridgeName = RdmaPluginTransformState.bridgeNameFor(fqn)
        RdmaPluginTransformState.addEdit(path, nameSrc.startOffset, nameSrc.endOffset, bridgeName)
    }
}

object RdmaPluginPropertyAccessChecker : FirExpressionChecker<FirPropertyAccessExpression>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirPropertyAccessExpression) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirPropertySymbol>() ?: return
        val classId = symbol.callableId?.classId ?: return
        val fqn = classId.asSingleFqName().asString()
        val type = RdmaPluginTransformState.typeByQualifiedName(fqn) ?: return
        val propName = symbol.name.asString()
        if (type.properties.none { it.first == propName }) return
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
        val src = expression.source ?: return
        val nameSrc = lValue.calleeReference.source ?: return
        val rValueSrc = expression.rValue.source ?: return
        val path = filePathOf(context) ?: return
        val text = RdmaPluginTransformState.textOf(path)
        val receiver = text.substring(src.startOffset, nameSrc.startOffset)
        val rValueText = text.substring(rValueSrc.startOffset, rValueSrc.endOffset)
        val setter = "set${propName.replaceFirstChar { it.uppercase() }}"
        RdmaPluginTransformState.addEdit(path, src.startOffset, src.endOffset, "$receiver$setter($rValueText)")
    }
}

private fun String.escapeForJs(): String = replace("\\", "\\\\").replace("\"", "\\\"")
