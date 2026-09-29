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

import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Computes `floor(S)` — the minimum host version on which symbol `S` is available (§5.3).
 *
 * Rules:
 *  - a **non-emulatable** symbol (an anchor) has `floor = its introduction version` (it only
 *    becomes available via `static-release`);
 *  - an **emulatable** symbol has `floor = max(floor(anchor))` over the non-emulatable anchors
 *    in its transitive usage closure, or `0` when there are none (it can be polyfilled all the
 *    way back to its non-emulatable anchors).
 *
 * The result is keyed by polyfill-node FQN (the same granularity as `RdmaAnalysis.uses`); a node
 * absent from the map has `floor = 0`. Only non-emulatable anchors set the lower bound.
 */
object RdmaFloor {

    fun compute(changelog: Changelog, analysis: RdmaAnalysis): Map<String, Int> {
        val introduction = RdmaChangelog.introductionVersions(changelog, analysis.moduleId)

        val anchors = analysis.declarations
            .filter { !it.emulatable }
            .map { RdmaSubgraph.nodeOf(it) }
            .toSet()

        val reverse = mutableMapOf<String, MutableSet<String>>()
        for (use in analysis.uses) {
            reverse.getOrPut(use.to) { mutableSetOf() }.add(use.from)
        }

        val floor = mutableMapOf<String, Int>()
        for (anchor in anchors) {
            val anchorFloor = introduction[anchor] ?: 0
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            stack.addLast(anchor)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!visited.add(node)) continue
                floor[node] = maxOf(floor[node] ?: 0, anchorFloor)
                for (prev in reverse[node].orEmpty()) stack.addLast(prev)
            }
        }
        return floor
    }

    /** `minHost` of a set of used symbols: the max `floor` over them (§5.3). */
    fun minHost(floor: Map<String, Int>, usedSymbols: Set<String>): Int =
        usedSymbols.maxOfOrNull { floor[it] ?: 0 } ?: 0

    private val json = Json { prettyPrint = true }

    /** Writes `<versionsDir>/<moduleId>/rdma_floor.json` (node FQN → floor). */
    fun write(moduleId: String, floor: Map<String, Int>, versionsDir: File): File {
        val file = File(versionsDir, "$moduleId/rdma_floor.json")
        file.parentFile.mkdirs()
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        file.writeText(json.encodeToString(serializer, floor))
        return file
    }

    /** Reads `<versionsDir>/<moduleId>/rdma_floor.json`, or empty if absent. */
    fun read(moduleId: String, versionsDir: File): Map<String, Int> {
        val file = File(versionsDir, "$moduleId/rdma_floor.json")
        if (!file.isFile) return emptyMap()
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        return json.decodeFromString(serializer, file.readText())
    }
}
