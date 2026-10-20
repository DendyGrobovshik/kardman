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

import io.github.dendygrobovshik.kardman.types.Toolchain
import io.github.dendygrobovshik.kardman.types.ToolchainGeneration
import io.github.dendygrobovshik.kardman.types.ToolchainPins
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Read/write of the append-only toolchain journal (`versions/toolchain.json`) — the source of
 * truth for the toolchain generation axis (toolchain_compatibility.md §3).
 *
 * `generation-release` appends an `active` generation; `retire-generation` flips it to
 * `retired` (the only in-place mutation, and only of the status field — the generation and its
 * pins stay, so a retired generation can still be resolved for diagnostics).
 */
object RdmaToolchain {
    private val json = Json { prettyPrint = true }

    fun file(versionsDir: File): File = File(versionsDir, "toolchain.json")

    fun read(versionsDir: File): Toolchain {
        val file = file(versionsDir)
        if (!file.isFile) return Toolchain()
        return runCatching { json.decodeFromString(Toolchain.serializer(), file.readText()) }
            .getOrDefault(Toolchain())
    }

    fun write(versionsDir: File, toolchain: Toolchain): File {
        val file = file(versionsDir)
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(Toolchain.serializer(), toolchain))
        return file
    }

    /** Active generations, in first-appearance order. */
    fun activeGenerations(toolchain: Toolchain): List<ToolchainGeneration> =
        toolchain.generations.filter { it.status == "active" }

    /**
     * Live generations under the FIFO floor model (rdma.toml `toolchain.oldestActiveGeneration`):
     * everything at/after [oldestActiveGeneration] in first-appearance order. An empty floor falls
     * back to the journal's `active` status (no floor declared yet).
     */
    fun liveGenerations(toolchain: Toolchain, oldestActiveGeneration: String): List<ToolchainGeneration> {
        val gens = toolchain.generations
        if (oldestActiveGeneration.isNotEmpty()) {
            val idx = gens.indexOfFirst { it.g == oldestActiveGeneration }
            if (idx >= 0) return gens.drop(idx)
        }
        return gens.filter { it.status == "active" }
    }

    fun isActive(toolchain: Toolchain, g: String): Boolean =
        toolchain.generations.any { it.g == g && it.status == "active" }

    /**
     * Appends a new `active` generation (or returns the journal unchanged if [g] already
     * exists). [alias] and [pins] are recorded for reproducibility.
     */
    fun append(toolchain: Toolchain, g: String, alias: String, pins: ToolchainPins): Toolchain {
        if (toolchain.generations.any { it.g == g }) return toolchain
        val generation = ToolchainGeneration(
            g = g,
            alias = alias,
            pins = pins,
            time = Instant.now().toString(),
            status = "active",
        )
        return toolchain.copy(generations = toolchain.generations + generation)
    }

    /** Marks [g] `retired`. No-op if the generation does not exist. */
    fun retire(toolchain: Toolchain, g: String): Toolchain {
        val generations = toolchain.generations.map {
            if (it.g == g) it.copy(status = "retired") else it
        }
        return toolchain.copy(generations = generations)
    }
}
