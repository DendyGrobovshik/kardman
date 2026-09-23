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

/** The polyfill subgraph: the boundary entries and the full set of nodes to materialize. */
data class PolyfillResult(
    val entries: Set<String>,
    val nodes: Set<String>,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()
}

/**
 * Computes the minimal set of declarations that must be shipped as a polyfill for a given diff.
 *
 * Nodes are collapsed to "polyfill-node" granularity: class members fold into their enclosing
 * class, so a node is a top-level function/property or a whole class. An `@RDMA` boundary node
 * is an entry when it is itself dirty, or when any of its non-`@RDMA` closure is dirty. The
 * closure is cut at other `@RDMA` boundaries (they stay external).
 */
object RdmaSubgraph {

    fun compute(analysis: RdmaAnalysis, dirty: RdmaDirtySet): PolyfillResult {
        val declsByFqn = analysis.declarations.associateBy { it.fqn }

        val boundaryNodes = analysis.declarations
            .filter { it.isRdma && isBoundaryKind(it.kind) }
            .map { it.fqn }
            .toSet()

        val adjacency = mutableMapOf<String, MutableSet<String>>()
        for (use in analysis.uses) {
            adjacency.getOrPut(use.from) { mutableSetOf() }.add(use.to)
        }

        // Removed declarations are destructive (out of polyfill scope): a removed private helper
        // also changes its caller, so its boundary is captured through the caller's own hash change.
        val dirtyNodes = (dirty.added + dirty.changed).mapNotNull { fqn ->
            declsByFqn[fqn]?.let { nodeOf(it) } ?: fqn
        }.toSet()

        val entries = boundaryNodes.filter { b ->
            b in dirtyNodes || downClosure(b, adjacency, boundaryNodes).any { it in dirtyNodes }
        }.toSet()

        val nodes = entries.flatMap { downClosure(it, adjacency, boundaryNodes) + it }.toSet()

        return PolyfillResult(entries, nodes)
    }

    fun nodeOf(decl: RdmaDeclaration): String = when (decl.kind) {
        RdmaSymbolKind.CLASS_METHOD, RdmaSymbolKind.CLASS_PROPERTY -> decl.fqn.substringBeforeLast('.')
        else -> decl.fqn
    }

    private fun isBoundaryKind(kind: RdmaSymbolKind): Boolean = when (kind) {
        RdmaSymbolKind.CLASS, RdmaSymbolKind.TOP_LEVEL_FUNCTION, RdmaSymbolKind.TOP_LEVEL_PROPERTY -> true
        else -> false
    }

    /** Non-`@RDMA` nodes reachable from [boundary], not traversing through other boundaries. */
    private fun downClosure(
        boundary: String,
        adjacency: Map<String, Set<String>>,
        boundaryNodes: Set<String>,
    ): Set<String> {
        val result = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        for (n in adjacency[boundary].orEmpty()) {
            if (n !in boundaryNodes && n !in result) {
                result.add(n)
                stack.addLast(n)
            }
        }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            for (n in adjacency[current].orEmpty()) {
                if (n !in boundaryNodes && n !in result) {
                    result.add(n)
                    stack.addLast(n)
                }
            }
        }
        return result
    }
}
