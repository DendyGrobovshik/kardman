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
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thin CLI over the pure analysis functions. Purely data-driven (no compiler dependencies), so it
 * can be run on any classpath that includes `rdma-kernel-compiler-plugin` + `rdma-types` +
 * `kotlinx-serialization`.
 *
 * Usage:
 *   static-release  <analysis.json> <versionsDir>
 *   dynamic-release <analysis.json> <versionsDir> <polyfillOutDir>
 */
object RdmaReleaseCli {
    private val json = Json

    @JvmStatic
    fun main(args: Array<String>) {
        val command = args.getOrNull(0)
        val analysisPath = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        if (command == null || analysisPath == null || versionsDir == null) {
            System.err.println("usage: (static-release|dynamic-release) <analysis.json> <versionsDir> [polyfillOutDir]")
            kotlin.system.exitProcess(2)
        }
        val analysis = json.decodeFromString(RdmaAnalysis.serializer(), File(analysisPath).readText())

        when (command) {
            "static-release" -> {
                val file = RdmaBaseline.write(analysis, versionsDir)
                println("Wrote ${file.path}")
            }

            "dynamic-release" -> {
                val outDir = args.getOrNull(3)?.let(::File) ?: File("build/polyfill")
                val baseline = RdmaBaseline.read(analysis.moduleId, versionsDir)
                    ?: error("No baseline for module '${analysis.moduleId}' in ${versionsDir.path}; run static-release first")
                val dirty = RdmaHashDiff.diff(analysis.declarations, baseline)
                val result = RdmaSubgraph.compute(analysis, dirty)

                println("added:   ${dirty.added.sorted().joinToString()}")
                println("removed: ${dirty.removed.sorted().joinToString()}")
                println("changed: ${dirty.changed.sorted().joinToString()}")
                println("entries: ${result.entries.sorted().joinToString()}")
                println("nodes:   ${result.nodes.sorted().joinToString()}")

                val files = RdmaPolyfillMaterializer.materialize(analysis, result)
                if (files.isEmpty()) {
                    println("No polyfill to emit.")
                } else {
                    outDir.mkdirs()
                    for (file in files) {
                        val target = File(outDir, file.name)
                        target.writeText(file.content)
                        println("Wrote ${target.path}")
                    }
                }
            }

            else -> {
                System.err.println("unknown command: $command")
                kotlin.system.exitProcess(2)
            }
        }
    }
}
