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

import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSourceRange
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.PsiIrFileEntry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.FqName
import java.io.File
import java.util.IdentityHashMap

data class RdmaIndexResult(
    val declarations: List<RdmaDeclaration>,
    val uses: List<RdmaUse>,
)

/**
 * Walks every real source declaration in the current module and produces a content-hashed,
 * member-granular index plus a usage graph. The graph is emitted at "polyfill-node" granularity:
 * class members collapse into their enclosing class, so a node is either a top-level
 * function/property or a whole class. This is the raw material for change detection (Phase 1)
 * and the dirty subgraph (Phase 2).
 */
object RdmaSymbolIndexer {
    private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

    fun index(moduleFragment: IrModuleFragment): RdmaIndexResult {
        val declarations = mutableListOf<RdmaDeclaration>()
        val irToNode = IdentityHashMap<IrDeclaration, String>()
        val sources = IdentityHashMap<IrFile, String>()

        for (file in moduleFragment.files) {
            val text = readSource(file) ?: continue
            sources[file] = text
            for (decl in file.declarations) {
                indexDeclaration(decl, text, file.fileEntry.name, enclosingRdma = false, memberOfClass = false, declarations, irToNode)
            }
        }

        val uses = mutableListOf<RdmaUse>()
        for (file in moduleFragment.files) {
            for (decl in file.declarations) {
                val from = irToNode[decl] ?: continue
                collectUses(from, decl, irToNode, uses)
            }
        }

        return RdmaIndexResult(declarations, uses)
    }

    private fun readSource(file: IrFile): String? {
        val name = file.fileEntry.name
        if (name.replace('\\', '/').contains("/build/generated/")) return null
        val psiText = (file.fileEntry as? PsiIrFileEntry)?.psiFile?.text
        if (psiText != null) return psiText
        return try {
            File(name).takeIf { it.isFile }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    private fun indexDeclaration(
        decl: IrDeclaration,
        text: String,
        path: String,
        enclosingRdma: Boolean,
        memberOfClass: Boolean,
        out: MutableList<RdmaDeclaration>,
        irToNode: MutableMap<IrDeclaration, String>,
    ) {
        if (decl.origin != IrDeclarationOrigin.DEFINED) return
        val start = decl.startOffset
        if (start < 0) return

        when (decl) {
            is IrClass -> {
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val end = decl.endOffset
                if (end < start) return
                val headerEnd = minOf(classHeaderEnd(text, start), end)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS,
                    hash = RdmaContentHasher.sha256(text.substring(start, headerEnd)),
                    isRdma = selfRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = fqn
                for (member in decl.declarations) {
                    indexMember(member, text, path, selfRdma, fqn, out, irToNode)
                }
            }

            is IrSimpleFunction -> {
                if (decl.isExternal) return
                val end = decl.endOffset
                if (end < start) return
                val isRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = if (memberOfClass) RdmaSymbolKind.CLASS_METHOD else RdmaSymbolKind.TOP_LEVEL_FUNCTION,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = isRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = fqn
            }

            is IrProperty -> {
                val end = decl.endOffset
                if (end < start) return
                val isRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val fqn = decl.fqNameWhenAvailable?.asString() ?: decl.name.asString()
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = if (memberOfClass) RdmaSymbolKind.CLASS_PROPERTY else RdmaSymbolKind.TOP_LEVEL_PROPERTY,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = isRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = fqn
            }

            else -> {}
        }
    }

    private fun indexMember(
        decl: IrDeclaration,
        text: String,
        path: String,
        enclosingRdma: Boolean,
        containerFqn: String,
        out: MutableList<RdmaDeclaration>,
        irToNode: MutableMap<IrDeclaration, String>,
    ) {
        if (decl.origin != IrDeclarationOrigin.DEFINED) return
        val start = decl.startOffset
        if (start < 0) return

        when (decl) {
            is IrClass -> {
                val selfRdma = enclosingRdma || decl.hasAnnotation(RDMA_ANNOTATION)
                val end = decl.endOffset
                if (end < start) return
                val headerEnd = minOf(classHeaderEnd(text, start), end)
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS,
                    hash = RdmaContentHasher.sha256(text.substring(start, headerEnd)),
                    isRdma = selfRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = fqn
                for (member in decl.declarations) {
                    indexMember(member, text, path, selfRdma, fqn, out, irToNode)
                }
            }

            is IrSimpleFunction -> {
                if (decl.isExternal) return
                val end = decl.endOffset
                if (end < start) return
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS_METHOD,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = enclosingRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = containerFqn
            }

            is IrProperty -> {
                val end = decl.endOffset
                if (end < start) return
                val fqn = "${containerFqn}.${decl.name.asString()}"
                out += RdmaDeclaration(
                    fqn = fqn,
                    kind = RdmaSymbolKind.CLASS_PROPERTY,
                    hash = RdmaContentHasher.sha256(text.substring(start, end)),
                    isRdma = enclosingRdma,
                    sourceRange = RdmaSourceRange(path, start, end),
                )
                irToNode[decl] = containerFqn
            }

            else -> {}
        }
    }

    private fun collectUses(
        from: String,
        root: IrElement,
        irToNode: Map<IrDeclaration, String>,
        out: MutableList<RdmaUse>,
    ) {
        val seen = mutableSetOf<String>()
        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner.parent as? IrClass)
            }

            override fun visitClassReference(expression: IrClassReference) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner as? IrClass)
            }

            override fun visitGetField(expression: IrGetField) {
                expression.acceptChildrenVoid(this)
                record(expression.symbol.owner.parent as? IrClass)
            }

            private fun record(decl: IrDeclaration?) {
                if (decl == null) return
                val node = irToNode[decl] ?: return
                if (node != from && seen.add(node)) {
                    out += RdmaUse(from, node)
                }
            }
        }
        root.acceptChildrenVoid(visitor)
    }
}
