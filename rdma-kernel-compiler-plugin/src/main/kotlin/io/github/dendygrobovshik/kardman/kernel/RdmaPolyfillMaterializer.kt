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
import io.github.dendygrobovshik.kardman.types.RdmaPolyfill
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaSymbolSources
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
        val manualByTarget = analysis.polyfills.associateBy { it.target }
        val manualEntries = result.entries.filter { it in manualByTarget }.toSet()
        return materializeAuto(analysis, result, manualEntries) +
            materializeManual(analysis, manualEntries, manualByTarget)
    }

    private fun materializeAuto(analysis: RdmaAnalysis, result: PolyfillResult, manualEntries: Set<String>): List<PolyfillSourceFile> {
        val entries = result.entries - manualEntries
        val nodes = result.nodes - manualEntries
        if (nodes.isEmpty()) return emptyList()

        val declsByNode = analysis.declarations
            .filter { it.kind != RdmaSymbolKind.CLASS_METHOD && it.kind != RdmaSymbolKind.CLASS_PROPERTY }
            .filter { RdmaSubgraph.nodeOf(it) in nodes }

        val externalFunctions = analysis.declarations
            .filter { it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .filter { it.fqn !in entries }

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
                    stripPolyfillAnnotation(stripRdmaAnnotation(rewriteRdmaCalls(sliceDeclaration(text, decl), externalFunctions)))
                }

            val content = buildString {
                if (header.isNotBlank()) append(header.trimEnd()).append("\n\n")
                if (index == 0 && externalObject.isNotBlank()) append(externalObject).append("\n\n")
                append(body.trimEnd())
                if (index == 0) {
                    append("\n\n")
                    append(registrationBlock(entries, analysis))
                }
                append("\n")
            }
            files += PolyfillSourceFile("Polyfill$index.kt", content)
            index++
        }
        return files
    }

    /**
     * Materializes manual polyfills (§6.3): a `@Polyfill(for = "X") fun x_polyfill(...)` replaces
     * the auto-materialized `X`. The polyfill function is emitted as-is (its `@RDMA` calls are
     * rewritten to `RDMA.*`) and registered on the target name.
     */
    private fun materializeManual(
        analysis: RdmaAnalysis,
        manualEntries: Set<String>,
        manualByTarget: Map<String, RdmaPolyfill>,
    ): List<PolyfillSourceFile> {
        if (manualEntries.isEmpty()) return emptyList()
        val declByFqn = analysis.declarations.associateBy { it.fqn }

        val externalFunctions = analysis.declarations
            .filter { it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .filter { it.fqn !in manualEntries }
        val externalObject = buildExternalObject(externalFunctions)

        // First pass: collect the emitted body + registration for each manual entry so that the
        // registration can be consolidated into a single eager `main()` (a DCE root that runs on
        // module load), rather than a lazy top-level property that DCE strips away.
        val entries = mutableListOf<Pair<String, Pair<String, String>>>() // header to (body, registrationLine)
        for (target in manualEntries.sorted()) {
            val polyfill = manualByTarget[target] ?: continue
            val range = polyfill.sourceRange ?: continue
            val text = runCatching { File(range.file).readText() }.getOrNull() ?: continue
            val header = extractHeader(text)
            val body = stripPolyfillAnnotation(stripRdmaAnnotation(rewriteRdmaCalls(text.substring(range.start, range.end), externalFunctions)))
            val targetName = target.substringAfterLast('.')
            val polyfillName = polyfill.fqn.substringAfterLast('.')
            entries += header to (body to "    js(\"RDMA\").$targetName = ::$polyfillName")
        }
        if (entries.isEmpty()) return emptyList()

        val registration = buildString {
            append("fun main() {\n")
            entries.forEach { append(it.second.second).append("\n") }
            append("}")
        }

        val files = mutableListOf<PolyfillSourceFile>()
        var index = 0
        for ((header, bodyAndReg) in entries) {
            val body = bodyAndReg.first
            val content = buildString {
                if (header.isNotBlank()) append(header.trimEnd()).append("\n\n")
                if (index == 0 && externalObject.isNotBlank()) append(externalObject).append("\n\n")
                append(body.trimEnd())
                if (index == 0) {
                    append("\n\n")
                    append(registration)
                }
                append("\n")
            }
            files += PolyfillSourceFile("PolyfillManual$index.kt", content)
            index++
        }
        return files
    }

    private fun buildExternalObject(externalFunctions: List<RdmaDeclaration>): String {
        if (externalFunctions.isEmpty()) return ""
        val members = externalFunctions
            .sortedBy { it.fqn }
            .mapNotNull { decl -> dynamicSignature(decl)?.let { "    $it" } }
            .joinToString("\n")
        if (members.isEmpty()) return ""
        return "external object RDMA {\n$members\n}"
    }

    /**
     * A dynamic-typed declaration of an external `@RDMA` top-level function. The polyfill only
     * *calls* these (the `RDMA.<name>(...)` rewrite), so the exact parameter types are irrelevant
     * at runtime (JS is dynamic) and are erased to `dynamic` so the emitted module compiles
     * standalone, without the kernel's Kotlin types in scope.
     */
    private fun dynamicSignature(decl: RdmaDeclaration): String? {
        val range = decl.sourceRange ?: return null
        val text = runCatching { File(range.file).readText() }.getOrNull() ?: return null
        val full = text.substring(range.start, range.end)
        val funIdx = full.indexOf("fun ")
        if (funIdx < 0) return null
        val after = full.substring(funIdx)
        val open = after.indexOf('(')
        if (open < 0) return null

        // Walk the parameter list tracking nested (), <> and [] so commas inside generics or
        // lambda types don't split the list.
        val params = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var angle = 0
        var bracket = 0
        var i = open + 1
        while (i < after.length) {
            val c = after[i]
            when {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> {
                    if (depth == 0) { if (current.isNotBlank()) params.add(current.toString().trim()); break }
                    depth--; current.append(c)
                }
                c == '<' -> { angle++; current.append(c) }
                c == '>' -> { angle--; current.append(c) }
                c == '[' -> { bracket++; current.append(c) }
                c == ']' -> { bracket--; current.append(c) }
                c == ',' && depth == 0 && angle == 0 && bracket == 0 -> { params.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        val name = decl.fqn.substringAfterLast('.')
        val signature = params.mapIndexed { idx, _ -> "p$idx: dynamic" }.joinToString(", ")
        return "fun $name($signature): dynamic"
    }

    private fun sliceDeclaration(text: String, decl: RdmaDeclaration): String {
        val range = decl.sourceRange ?: return ""
        return text.substring(range.start, range.end)
    }

    private fun extractHeader(text: String): String =
        text.lines().filter { line ->
            val t = line.trimStart()
            (t.startsWith("package ") || t.startsWith("import ")) &&
                !t.endsWith(".RDMA") &&
                !t.endsWith(".Polyfill")
        }.joinToString("\n")

    private fun stripRdmaAnnotation(source: String): String =
        source.replace(Regex("@RDMA\\b\\s*"), "")

    private fun stripPolyfillAnnotation(source: String): String =
        source.replace(Regex("@Polyfill\\s*\\([^)]*\\)\\s*"), "")

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
            append("fun main() {\n")
            append(lines)
            append("\n}")
        }
    }

    /**
     * Materializes the `R` polyfill — re-providing removed `@RDMA` symbols from their last-known
     * source (snapshotted at the previous release) so old plugins keep working on a new host
     * (§5.1, §7.1 S4).
     *
     * Only top-level `@RDMA` functions are re-provided (the same boundary as the F materializer);
     * class re-provisioning is a follow-up.
     */
    fun materializeRemoved(
        analysis: RdmaAnalysis,
        removedFunctions: List<String>,
        snapshot: RdmaSymbolSources,
    ): List<PolyfillSourceFile> {
        if (removedFunctions.isEmpty()) return emptyList()
        val manualByTarget = analysis.polyfills.associateBy { it.target }
        val manual = removedFunctions.filter { it in manualByTarget }.toSet()
        val snapshotOnly = removedFunctions.filter { it !in manualByTarget }
        return materializeManual(analysis, manual, manualByTarget) +
            materializeRemovedFromSnapshot(analysis, snapshotOnly, snapshot)
    }

    /**
     * Materializes the `R` polyfill for removed `@RDMA` classes (§5.1, §7.1 S4): re-emits the
     * class from its last-known source and re-registers `RDMA.create<Name>` as a JS factory
     * (`new Name(...)`), so old plugins that `RDMA.create<Name>(...)` keep working.
     *
     * Concrete classes are re-provided. Open-method/vtable dispatch and companion statics of a
     * removed class are still a follow-up (they live in the native bridge, not the JS class).
     */
    fun materializeRemovedClasses(
        analysis: RdmaAnalysis,
        removedClasses: List<String>,
        snapshot: RdmaSymbolSources,
    ): List<PolyfillSourceFile> {
        if (removedClasses.isEmpty()) return emptyList()

        val externalFunctions = analysis.declarations
            .filter { it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .filter { it.fqn !in removedClasses }
        val externalObject = buildExternalObject(externalFunctions)

        val files = mutableListOf<PolyfillSourceFile>()
        val registrations = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var index = 0
        for (fqn in removedClasses.sorted()) {
            val snippet = snapshot.sources[fqn] ?: continue
            val simpleName = fqn.substringAfterLast('.')
            val body = stripRdmaAnnotation(rewriteRdmaCalls(snippet, externalFunctions))
            bodies += body.trimEnd()
            registrations += "    js(\"RDMA\").create$simpleName = js(\"(...args) => new $simpleName(...args)\")"
            files += PolyfillSourceFile("PolyfillRClass$index.kt", body.trimEnd())
            index++
        }
        if (bodies.isNotEmpty()) {
            val registration = buildString {
                append("fun main() {\n")
                registrations.forEach { append(it).append("\n") }
                append("}")
            }
            val content = buildString {
                append(bodies[0])
                append("\n\n")
                if (externalObject.isNotBlank()) append(externalObject).append("\n\n")
                append(registration)
                append("\n")
            }
            files[0] = PolyfillSourceFile("PolyfillRClass0.kt", content)
        }
        return files
    }

    private fun materializeRemovedFromSnapshot(
        analysis: RdmaAnalysis,
        removedFunctions: List<String>,
        snapshot: RdmaSymbolSources,
    ): List<PolyfillSourceFile> {
        if (removedFunctions.isEmpty()) return emptyList()

        val externalFunctions = analysis.declarations
            .filter { it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION }
            .filter { it.fqn !in removedFunctions }
        val externalObject = buildExternalObject(externalFunctions)

        val files = mutableListOf<PolyfillSourceFile>()
        val registrations = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        var index = 0
        for (fqn in removedFunctions.sorted()) {
            val snippet = snapshot.sources[fqn] ?: continue
            val body = stripRdmaAnnotation(rewriteRdmaCalls(snippet, externalFunctions))
            val name = fqn.substringAfterLast('.')
            bodies += body.trimEnd()
            registrations += "    js(\"RDMA\").$name = ::$name"
            files += PolyfillSourceFile("PolyfillR$index.kt", body.trimEnd())
            index++
        }
        if (bodies.isNotEmpty()) {
            val registration = buildString {
                append("fun main() {\n")
                registrations.forEach { append(it).append("\n") }
                append("}")
            }
            val content = buildString {
                append(bodies[0])
                append("\n\n")
                if (externalObject.isNotBlank()) append(externalObject).append("\n\n")
                append(registration)
                append("\n")
            }
            files[0] = PolyfillSourceFile("PolyfillR0.kt", content)
        }
        return files
    }
}
