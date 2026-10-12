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
package io.github.dendygrobovshik.kardman.tests

import io.github.dendygrobovshik.kardman.kernel.RdmaDirtySet
import io.github.dendygrobovshik.kardman.kernel.RdmaSubgraph
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.junit.Test
import kotlin.test.assertEquals

class RdmaSubgraphTest {

    private fun fn(fqn: String, isRdma: Boolean) =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", isRdma)

    private fun cls(fqn: String, isRdma: Boolean) =
        RdmaDeclaration(fqn, RdmaSymbolKind.CLASS, "h", isRdma)

    private fun method(fqn: String, isRdma: Boolean) =
        RdmaDeclaration(fqn, RdmaSymbolKind.CLASS_METHOD, "h", isRdma)

    private fun compute(decls: List<RdmaDeclaration>, uses: List<RdmaUse>, dirty: RdmaDirtySet) =
        RdmaSubgraph.compute(RdmaAnalysis("m", decls, uses), dirty)

    @Test
    fun `added top-level function pulls its private closure`() {
        val decls = listOf(fn("foo", true), fn("boo", false), fn("doo", false))
        val uses = listOf(RdmaUse("foo", "boo"), RdmaUse("boo", "doo"))
        val result = compute(decls, uses, RdmaDirtySet(added = setOf("foo")))
        assertEquals(setOf("foo"), result.entries)
        assertEquals(setOf("foo", "boo", "doo"), result.nodes)
    }

    @Test
    fun `changed private helper pulls its RDMA caller`() {
        val decls = listOf(fn("foo", true), fn("boo", false), fn("doo", false))
        val uses = listOf(RdmaUse("foo", "boo"), RdmaUse("boo", "doo"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("boo")))
        assertEquals(setOf("foo"), result.entries)
        assertEquals(setOf("foo", "boo", "doo"), result.nodes)
    }

    @Test
    fun `helper used by two RDMA functions makes both entries`() {
        val decls = listOf(fn("foo1", true), fn("foo2", true), fn("boo", false))
        val uses = listOf(RdmaUse("foo1", "boo"), RdmaUse("foo2", "boo"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("boo")))
        assertEquals(setOf("foo1", "foo2"), result.entries)
        assertEquals(setOf("foo1", "foo2", "boo"), result.nodes)
    }

    @Test
    fun `RDMA boundary is not pulled into another functions polyfill`() {
        val decls = listOf(fn("foo", true), fn("moo", true), fn("boo", false))
        val uses = listOf(RdmaUse("foo", "moo"), RdmaUse("foo", "boo"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("boo")))
        assertEquals(setOf("foo"), result.entries)
        assertEquals(setOf("foo", "boo"), result.nodes)
    }

    @Test
    fun `transitive private closure is included`() {
        val decls = listOf(fn("foo", true), fn("boo", false), fn("doo", false))
        val uses = listOf(RdmaUse("foo", "boo"), RdmaUse("boo", "doo"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("doo")))
        assertEquals(setOf("foo"), result.entries)
        assertEquals(setOf("foo", "boo", "doo"), result.nodes)
    }

    @Test
    fun `dead private helper without RDMA caller yields empty polyfill`() {
        val decls = listOf(fn("foo", true), fn("dead", false))
        val uses = emptyList<RdmaUse>()
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("dead")))
        assertEquals(emptySet(), result.entries)
        assertEquals(emptySet(), result.nodes)
    }

    @Test
    fun `changed class member marks the whole class as entry`() {
        val decls = listOf(cls("Person", true), method("Person.greet", true), fn("boo", false))
        val uses = listOf(RdmaUse("Person", "boo"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("Person.greet")))
        assertEquals(setOf("Person"), result.entries)
        assertEquals(setOf("Person", "boo"), result.nodes)
    }

    @Test
    fun `RDMA class is a boundary and is not pulled into sibling polyfill`() {
        val decls = listOf(cls("A", true), cls("B", true), fn("h", false))
        val uses = listOf(RdmaUse("A", "h"), RdmaUse("A", "B"))
        val result = compute(decls, uses, RdmaDirtySet(changed = setOf("h")))
        assertEquals(setOf("A"), result.entries)
        assertEquals(setOf("A", "h"), result.nodes)
    }
}
