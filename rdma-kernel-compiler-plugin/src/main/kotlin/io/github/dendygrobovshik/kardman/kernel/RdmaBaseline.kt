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
import io.github.dendygrobovshik.kardman.types.RdmaHashesFile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Read/write of the committed baseline in `versions/<moduleId>/rdma_hashes.json`. The baseline
 * records the hashes of the kernel symbols baked into the native host; `static-release` rewrites
 * it, `dynamic-release` diffs against it.
 */
object RdmaBaseline {
    private val json = Json { prettyPrint = true }

    fun hashesFile(moduleId: String, hashes: Map<String, String>): RdmaHashesFile =
        RdmaHashesFile(moduleId, hashes)

    fun hashesOf(analysis: RdmaAnalysis): Map<String, String> =
        analysis.declarations.associate { it.fqn to it.hash }

    fun toJson(hashesFile: RdmaHashesFile): String =
        json.encodeToString(RdmaHashesFile.serializer(), hashesFile)

    fun fromJson(text: String): RdmaHashesFile =
        json.decodeFromString(RdmaHashesFile.serializer(), text)

    /** Writes `<versionsDir>/<moduleId>/rdma_hashes.json`. */
    fun write(analysis: RdmaAnalysis, versionsDir: File): File {
        val file = File(versionsDir, "${analysis.moduleId}/rdma_hashes.json")
        file.parentFile.mkdirs()
        file.writeText(toJson(hashesFile(analysis.moduleId, hashesOf(analysis))))
        return file
    }

    /** Reads `<versionsDir>/<moduleId>/rdma_hashes.json`, or null if absent. */
    fun read(moduleId: String, versionsDir: File): Map<String, String>? {
        val file = File(versionsDir, "$moduleId/rdma_hashes.json")
        if (!file.isFile) return null
        return fromJson(file.readText()).hashes
    }
}
