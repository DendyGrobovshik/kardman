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
import io.github.dendygrobovshik.kardman.types.RdmaSymbolSources
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists every symbol's declaration source text and file header at release time, so a later
 * `remove` can materialize the `R` polyfill from the symbol's last-known source (§5.1).
 */
object RdmaSourceSnapshot {
    private val json = Json { prettyPrint = true }

    /** Writes `<versionsDir>/<moduleId>/rdma_sources.json`. */
    fun write(analysis: RdmaAnalysis, versionsDir: File): File {
        val sources = mutableMapOf<String, String>()
        val fileTexts = mutableMapOf<String, String>()
        val fileHeaders = mutableMapOf<String, String>()
        for (decl in analysis.declarations) {
            val range = decl.sourceRange ?: continue
            val text = fileTexts.getOrPut(range.file) {
                runCatching { File(range.file).readText() }.getOrNull() ?: ""
            }
            if (text.isEmpty()) continue
            val header = fileHeaders.getOrPut(range.file) { headerOf(text) }
            val fragment = if (header.isBlank()) text.substring(range.start, range.end)
            else "$header\n\n${text.substring(range.start, range.end)}"
            sources[decl.fqn] = fragment
        }
        val file = File(versionsDir, "${analysis.moduleId}/rdma_sources.json")
        file.parentFile.mkdirs()
        file.writeText(
            json.encodeToString(
                RdmaSymbolSources.serializer(),
                RdmaSymbolSources(analysis.moduleId, sources),
            ),
        )
        return file
    }

    /** Reads `<versionsDir>/<moduleId>/rdma_sources.json`, or an empty snapshot if absent. */
    fun read(moduleId: String, versionsDir: File): RdmaSymbolSources {
        val file = File(versionsDir, "$moduleId/rdma_sources.json")
        if (!file.isFile) return RdmaSymbolSources(moduleId)
        return json.decodeFromString(RdmaSymbolSources.serializer(), file.readText())
    }

    private fun headerOf(text: String): String =
        text.lines().filter { line ->
            val t = line.trimStart()
            (t.startsWith("package ") || t.startsWith("import ")) && !t.endsWith(".RDMA")
        }.joinToString("\n")
}
