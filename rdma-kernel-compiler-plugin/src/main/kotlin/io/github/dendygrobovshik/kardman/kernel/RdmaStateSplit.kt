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

/**
 * State-split detection (compatibility.md §6.6).
 *
 * `@RDMA` is the only boundary between runtimes, so every non-`@RDMA` variable and `object` of a
 * kernel is *runtime-local*: a polyfill that touches it gets its own copy in JS. The copy is a
 * logic bug when code that stays native on the host also touches the same state — the two
 * copies diverge silently. A dynamic release reports each such split; the developer either keeps
 * the state behind `@RDMA` functions or accepts the split with `@RuntimeLocal`.
 *
 * A `val` of a primitive, `String` or enum type whose declaration is unchanged since the host's
 * native state is exempt: both copies hold the same value. A `val` → `var` change or a new
 * initializer value changes the hash, so it is reported.
 */
object RdmaStateSplit {

    data class Split(
        val state: String,
        val jsUsers: List<String>,
        val nativeUsers: List<String>,
    ) {
        fun message(): String =
            "$state would split between runtimes: the polyfill (${jsUsers.joinToString()}) gets its own copy, " +
                "while native code (${nativeUsers.joinToString()}) keeps the original. " +
                "Keep this state behind @RDMA functions, or mark it @RuntimeLocal if separate copies are fine."
    }

    private val EXEMPT_TYPES = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Float", "kotlin.Double",
        "kotlin.Boolean", "kotlin.Char", "kotlin.String",
    )

    fun detect(analysis: RdmaAnalysis, dirty: RdmaDirtySet, polyfill: PolyfillResult): List<Split> {
        if (polyfill.isEmpty) return emptyList()
        val changed = dirty.added + dirty.changed
        val members = analysis.declarations
            .filter { it.kind == RdmaSymbolKind.CLASS_PROPERTY }
            .groupBy { it.fqn.substringBeforeLast('.') }

        val holders = analysis.declarations.filter { decl ->
            if (decl.isRdma || decl.runtimeLocal || RdmaSubgraph.nodeOf(decl) !in polyfill.nodes) return@filter false
            when {
                decl.kind == RdmaSymbolKind.TOP_LEVEL_PROPERTY -> decl.hasField && !exempt(decl, changed)
                decl.kind == RdmaSymbolKind.CLASS && decl.isObject ->
                    members[decl.fqn].orEmpty().any { it.hasField && !it.runtimeLocal && !exempt(it, changed) }
                else -> false
            }
        }

        val users = mutableMapOf<String, MutableSet<String>>()
        for (use in analysis.uses) users.getOrPut(use.to) { sortedSetOf() }.add(use.from)

        return holders.mapNotNull { holder ->
            val node = RdmaSubgraph.nodeOf(holder)
            val all = users[node].orEmpty() - node
            val native = all.filter { it !in polyfill.nodes }
            if (native.isEmpty()) null
            else Split(node, all.filter { it in polyfill.nodes }, native)
        }.sortedBy { it.state }
    }

    private fun exempt(p: RdmaDeclaration, changed: Set<String>): Boolean {
        if (p.isMutable || p.fqn in changed) return false
        val type = p.valueType ?: return false
        return type in EXEMPT_TYPES || type.startsWith("enum:")
    }
}
