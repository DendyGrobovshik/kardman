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

import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import java.io.File
import java.util.regex.Pattern

/** One synthetic source file of the polyfill module. */
data class PolyfillSourceFile(
    val name: String,
    val content: String,
)

/**
 * Materializes the dirty subgraph as a synthetic, self-registering Kotlin/JS module.
 *
 * For each polyfill node it extracts the full declaration text from the original source file,
 * keeps the file's package + imports, and rewrites references to `@RDMA` boundaries that are
 * *outside* the polyfill into typed `RDMA.xxx(...)` calls via a generated `external object RDMA`.
 * Each overridden entry is exported under a stable name via `@JsName` and registered on `RDMA.*`
 * at module load.
 *
 * Note: this first cut bridges top-level `@RDMA` function boundaries. Class constructor/static
 * bridging inside polyfilled classes is a follow-up.
 */
object RdmaPolyfillMaterializer {

    fun materialize(analysis: RdmaAnalysis, result: PolyfillResult): List<PolyfillSourceFile> {
        if (result.isEmpty) return emptyList()

        val declsByNode = analysis.declarations
            .filter { it.kind != RdmaSymbolKind.CLASS_METHOD && it.kind != RdmaSymbolKind.CLASS_PROPERTY }
            .filter { RdmaSubgraph.nodeOf(it) in result.nodes }

        val externalFunctions = analysis.declarations
            .filter { it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .filter { it.fqn !in result.entries }

        val externalObject = buildExternalObject(externalFunctions)

        val files = mutableListOf<PolyfillSourceFile>()
        val byFile = declsByNode.groupBy { it.sourceRange?.file }

        var index = 0
        for ((file, decls) in byFile) {
            if (file == null) continue
            val text = runCatching { File(file).readText() }.getOrNull() ?: continue
            val header = extractHeader(text)
            val body = decls.sortedBy { it.sourceRange?.start ?: Int.MAX_VALUE }
                .joinToString("\n\n") { decl ->
                    stripRdmaAnnotation(rewriteRdmaCalls(sliceDeclaration(text, decl), externalFunctions))
                }

            val content = buildString {
                if (header.isNotBlank()) append(header.trimEnd()).append("\n\n")
                if (index == 0 && externalObject.isNotBlank()) append(externalObject).append("\n\n")
                append(body.trimEnd())
                if (index == 0) {
                    append("\n\n")
                    append(registrationBlock(result.entries, analysis))
                }
                append("\n")
            }
            files += PolyfillSourceFile("Polyfill$index.kt", content)
            index++
        }
        return files
    }

    private fun buildExternalObject(externalFunctions: List<RdmaDeclaration>): String {
        if (externalFunctions.isEmpty()) return ""
        val members = externalFunctions
            .sortedBy { it.fqn }
            .joinToString("\n") { decl ->
                val sig = signatureOf(decl.fqn.substringAfterLast('.'), decl)
                if (sig.isBlank()) "" else "    $sig"
            }
            .lines().filter { it.isNotBlank() }.joinToString("\n")
        return "external object RDMA {\n$members\n}"
    }

    private fun signatureOf(name: String, decl: RdmaDeclaration): String {
        val range = decl.sourceRange ?: return ""
        val file = File(range.file)
        val text = runCatching { file.readText() }.getOrNull() ?: return ""
        val full = text.substring(range.start, range.end)
        // Locate "fun " and cut at the body (top-level '=' or '{').
        val funIdx = full.indexOf("fun ")
        if (funIdx < 0) return ""
        val body = full.substring(funIdx)
        var paren = 0
        var bracket = 0
        for (i in body.indices) {
            when (body[i]) {
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                '=', '{' -> if (paren == 0 && bracket == 0) return body.substring(0, i).trimEnd()
            }
        }
        return body.trimEnd()
    }

    private fun sliceDeclaration(text: String, decl: RdmaDeclaration): String {
        val range = decl.sourceRange ?: return ""
        return text.substring(range.start, range.end)
    }

    private fun extractHeader(text: String): String =
        text.lines().filter { line ->
            val t = line.trimStart()
            (t.startsWith("package ") || t.startsWith("import ")) && !t.endsWith(".RDMA")
        }.joinToString("\n")

    private fun stripRdmaAnnotation(source: String): String =
        source.replace(Regex("@RDMA\\b\\s*"), "")

    private fun rewriteRdmaCalls(source: String, externalFunctions: List<RdmaDeclaration>): String {
        var result = source
        val names = externalFunctions.map { it.fqn.substringAfterLast('.') }.toSet()
        for (name in names) {
            val regex = Regex("(?<![A-Za-z0-9_])${Pattern.quote(name)}(?=\\s*\\()")
            result = regex.replace(result) { "RDMA.$name" }
        }
        return result
    }

    private fun registrationBlock(entries: Set<String>, analysis: RdmaAnalysis): String {
        val byName = entries
            .mapNotNull { fqn -> analysis.declarations.firstOrNull { it.fqn == fqn } }
            .filter { it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .map { it.fqn.substringAfterLast('.') }
            .sorted()
        if (byName.isEmpty()) return ""
        val lines = byName.joinToString("\n") { name -> "    js(\"RDMA\").$name = ::$name" }
        return buildString {
            append("private val __rdmaPolyfillRegistration = run {\n")
            append(lines)
            append("\n    Unit\n")
            append("}")
        }
    }
}
