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

/**
 * Merges the kernel analyses of one module from several platforms (android, ios) into the
 * analysis a release works with: a declaration's hash covers every platform, so a change that
 * exists on one platform only (an `actual`) is still a change; it is emulatable only if it is on
 * every platform; uses and manual polyfills are united. Pass the same platforms on every release
 * — changing the set changes every hash.
 */
object RdmaAnalysisMerge {
    fun merge(analyses: List<RdmaAnalysis>): RdmaAnalysis {
        require(analyses.isNotEmpty()) { "no analysis given" }
        if (analyses.size == 1) return analyses.single()
        val module = analyses.first().moduleId
        require(analyses.all { it.moduleId == module }) { "analyses of different modules: ${analyses.map { it.moduleId }}" }
        val byFqn = LinkedHashMap<String, MutableList<io.github.dendygrobovshik.kardman.types.RdmaDeclaration>>()
        for (a in analyses) for (d in a.declarations) byFqn.getOrPut(d.fqn) { mutableListOf() } += d
        val declarations = byFqn.values.map { variants ->
            val base = variants.first()
            if (variants.size == 1) base
            else base.copy(
                hash = RdmaContentHasher.sha256(variants.joinToString("|") { it.hash }),
                emulatable = variants.all { it.emulatable },
                nativeRefs = variants.flatMap { it.nativeRefs }.distinct().sorted(),
            )
        }
        return RdmaAnalysis(
            moduleId = module,
            declarations = declarations,
            uses = analyses.flatMap { it.uses }.distinct(),
            polyfills = analyses.flatMap { it.polyfills }.distinctBy { it.target },
        )
    }
}
