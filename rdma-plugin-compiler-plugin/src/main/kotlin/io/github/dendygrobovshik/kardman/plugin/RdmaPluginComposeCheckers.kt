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

import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter

/**
 * Rejects calls to compose symbols outside the base protocol (effects, derived state,
 * movable content, snapshot flows, coroutine scopes, ...). See [ComposeAllowlist].
 */
object RdmaPluginComposeCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedSymbol<FirNamedFunctionSymbol>() ?: return
        val fqn = symbol.callableId.asSingleFqName().asString()
        if (ComposeAllowlist.isAllowed(fqn)) return
        val src = expression.source ?: return
        reporter.reportOn(src, FirErrors.UNSUPPORTED, ComposeAllowlist.reason(fqn))
    }
}

/**
 * Rejects imports of compose symbols outside the base protocol (covers type-only usages
 * that never materialize as a function call).
 */
object RdmaPluginComposeImportChecker : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        for (imp in declaration.imports) {
            val fqn = imp.importedFqName?.asString() ?: continue
            if (ComposeAllowlist.isAllowed(fqn)) continue
            val src = imp.source ?: continue
            reporter.reportOn(src, FirErrors.UNSUPPORTED, ComposeAllowlist.reason(fqn))
        }
    }
}
