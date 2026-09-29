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
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind

/**
 * Shared mutable non-`@RDMA` state detection (§6.5). A module has *shared state* when a mutable
 * private variable is reachable from two or more `@RDMA` boundary symbols; such a module must keep
 * its polyfill monolithic (it cannot be safely cut into delta pieces).
 *
 * Reachability follows the usage graph, cutting at `@RDMA` boundaries (the closure of a boundary
 * is its own polyfill's responsibility).
 */
object RdmaSharedState {

    /** The shared mutable private state nodes (top-level `var`s). */
    fun detect(analysis: RdmaAnalysis): Set<String> {
        val mutablePrivate = analysis.declarations
            .filter { it.isMutable && !it.isRdma && it.kind == RdmaSymbolKind.TOP_LEVEL_PROPERTY }
            .map { it.fqn }
            .toSet()
        if (mutablePrivate.isEmpty()) return emptySet()

        val boundaryNodes = analysis.declarations
            .filter { it.isRdma && (it.kind == RdmaSymbolKind.CLASS || it.kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION || it.kind == RdmaSymbolKind.TOP_LEVEL_PROPERTY) }
            .map { it.fqn }
            .toSet()

        val reverse = mutableMapOf<String, MutableSet<String>>()
        for (use in analysis.uses) {
            reverse.getOrPut(use.to) { mutableSetOf() }.add(use.from)
        }

        val shared = mutableSetOf<String>()
        for (prop in mutablePrivate) {
            val reachedBoundaries = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            stack.addLast(prop)
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!visited.add(node)) continue
                for (prev in reverse[node].orEmpty()) {
                    if (prev in boundaryNodes) reachedBoundaries.add(prev) else stack.addLast(prev)
                }
            }
            if (reachedBoundaries.size >= 2) shared.add(prop)
        }
        return shared
    }

    fun hasSharedState(analysis: RdmaAnalysis): Boolean = detect(analysis).isNotEmpty()
}
