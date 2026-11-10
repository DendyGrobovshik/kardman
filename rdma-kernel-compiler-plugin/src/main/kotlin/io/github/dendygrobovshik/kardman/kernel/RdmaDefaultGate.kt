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

import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName

/**
 * Release-time gates on default-parameter expressions for `@RDMA` callables.
 *
 * A default expression is re-materialized verbatim in a generated wrapper (native facade or
 * dynamic polyfill) that lives in a *different* scope than the declaration, so two classes of
 * default are rejected up front with a clear error rather than a generated-code compile failure:
 *
 *  - a non-empty lambda default (`content = { Text("x") }`) — only empty `{}` lambdas are
 *    supported, because a non-empty body would reference symbols not in the wrapper's scope;
 *  - a default that references a `private` declaration — the wrapper cannot access it.
 */
object RdmaDefaultGate {
    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

    fun check(moduleFragment: IrModuleFragment): List<String> {
        val errors = mutableListOf<String>()
        for (file in moduleFragment.files) {
            for (decl in file.declarations) {
                checkDeclaration(decl, enclosingRdma = false, errors)
            }
        }
        return errors
    }

    private fun checkDeclaration(decl: IrDeclaration, enclosingRdma: Boolean, errors: MutableList<String>) {
        when (decl) {
            is IrClass -> {
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                decl.primaryConstructor?.let { checkParams(it.parameters, fqn, errors) }
                for (member in decl.declarations) {
                    checkDeclaration(member, selfRdma, errors)
                }
            }
            is IrSimpleFunction -> {
                if (decl.isExternal) return
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                if (!selfRdma) return
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                checkParams(decl.parameters, fqn, errors)
            }
            else -> {}
        }
    }

    private fun checkParams(params: List<IrValueParameter>, path: String, errors: MutableList<String>) {
        for (p in params) {
            if (p.kind != IrParameterKind.Regular) continue
            val d = p.defaultValue ?: continue
            val expr = d.expression
            // A non-empty lambda default is rejected (only `{}` is supported).
            if (expr is IrFunctionExpression) {
                val body = expr.function.body
                val isEmpty = body is IrBlockBody && body.statements.isEmpty()
                if (!isEmpty) {
                    errors += "$path: parameter '${p.name.asString()}' has a non-empty lambda default; only empty lambda defaults ({}) are supported"
                }
            }
            checkPrivateRefs(expr, path, p.name.asString(), errors)
        }
    }

    private fun checkPrivateRefs(expr: IrExpression, path: String, param: String, errors: MutableList<String>) {
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                expression.acceptChildrenVoid(this)
                check(expression.symbol.owner)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                expression.acceptChildrenVoid(this)
                check(expression.symbol.owner)
            }

            override fun visitGetField(expression: IrGetField) {
                expression.acceptChildrenVoid(this)
                check(expression.symbol.owner)
            }

            private fun check(owner: IrDeclaration?) {
                if (owner == null) return
                val vis = (owner as? IrDeclarationWithVisibility)?.visibility ?: return
                if (vis != DescriptorVisibilities.PRIVATE) return
                val name = (owner as? IrDeclarationWithName)?.name?.asString() ?: return
                errors += "$path: parameter '$param' default references private declaration '$name' (not accessible from the generated wrapper)"
            }
        }
        expr.accept(visitor, null)
    }
}
