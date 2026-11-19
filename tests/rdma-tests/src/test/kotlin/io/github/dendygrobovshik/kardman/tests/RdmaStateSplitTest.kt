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
import io.github.dendygrobovshik.kardman.kernel.RdmaStateSplit
import io.github.dendygrobovshik.kardman.kernel.RdmaSubgraph
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** compatibility.md §6.6: runtime-local state that a polyfill and native code would both touch. */
class RdmaStateSplitTest {

    private fun fn(fqn: String, rdma: Boolean = true) =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h-$fqn", isRdma = rdma)

    private fun prop(
        fqn: String,
        mutable: Boolean = true,
        type: String = "kotlin.Int",
        runtimeLocal: Boolean = false,
        hasField: Boolean = true,
        kind: RdmaSymbolKind = RdmaSymbolKind.TOP_LEVEL_PROPERTY,
    ) = RdmaDeclaration(
        fqn, kind, "h-$fqn", isRdma = false, isMutable = mutable, valueType = type,
        runtimeLocal = runtimeLocal, hasField = hasField,
    )

    /** `login` (changed, hotfixed) and `isLoggedIn` (unchanged, native) both touch [state]. */
    private fun split(state: RdmaDeclaration, vararg extra: RdmaDeclaration, dirty: RdmaDirtySet = RdmaDirtySet(changed = setOf("login"))): List<RdmaStateSplit.Split> {
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(fn("login"), fn("isLoggedIn"), state) + extra,
            uses = listOf(RdmaUse("login", RdmaSubgraph.nodeOf(state)), RdmaUse("isLoggedIn", RdmaSubgraph.nodeOf(state))),
        )
        return RdmaStateSplit.detect(analysis, dirty, RdmaSubgraph.compute(analysis, dirty))
    }

    @Test
    fun `a private var touched by the polyfill and by native code splits`() {
        val splits = split(prop("token"))
        assertEquals(listOf(RdmaStateSplit.Split("token", listOf("login"), listOf("isLoggedIn"))), splits)
        assertTrue(splits.single().message().contains("@RuntimeLocal"))
    }

    @Test
    fun `RuntimeLocal accepts the split`() {
        assertEquals(emptyList(), split(prop("token", runtimeLocal = true)))
    }

    @Test
    fun `an unchanged val of a primitive, String or enum is exempt`() {
        assertEquals(emptyList(), split(prop("limit", mutable = false)))
        assertEquals(emptyList(), split(prop("tag", mutable = false, type = "kotlin.String")))
        assertEquals(emptyList(), split(prop("mode", mutable = false, type = "enum:m.Mode")))
    }

    @Test
    fun `a changed constant moves every user into the polyfill, so nothing stays native`() {
        val state = prop("limit", mutable = false)
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(fn("login"), fn("isLoggedIn"), state),
            uses = listOf(RdmaUse("login", "limit"), RdmaUse("isLoggedIn", "limit")),
        )
        val dirty = RdmaDirtySet(changed = setOf("limit"))
        val polyfill = RdmaSubgraph.compute(analysis, dirty)
        assertEquals(setOf("login", "isLoggedIn"), polyfill.entries)
        assertEquals(emptyList(), RdmaStateSplit.detect(analysis, dirty, polyfill))
    }

    @Test
    fun `a val holding an object is reported`() {
        assertEquals(1, split(prop("cache", mutable = false, type = "kotlin.collections.MutableMap")).size)
    }

    @Test
    fun `a property without a field is not state`() {
        assertEquals(emptyList(), split(prop("computed", hasField = false)))
    }

    @Test
    fun `state touched only inside the polyfill does not split`() {
        val state = prop("counter")
        val analysis = RdmaAnalysis(
            "m",
            declarations = listOf(fn("login"), fn("helper", rdma = false), state),
            uses = listOf(RdmaUse("login", "helper"), RdmaUse("helper", "counter")),
        )
        val dirty = RdmaDirtySet(changed = setOf("login"))
        assertEquals(emptyList(), RdmaStateSplit.detect(analysis, dirty, RdmaSubgraph.compute(analysis, dirty)))
    }

    @Test
    fun `an object with mutable state splits unless marked`() {
        val obj = RdmaDeclaration("Session", RdmaSymbolKind.CLASS, "h-Session", isRdma = false, isObject = true)
        val member = prop("Session.token", kind = RdmaSymbolKind.CLASS_PROPERTY)
        assertEquals(listOf("Session"), split(obj, member).map { it.state })
        assertEquals(emptyList(), split(obj.copy(runtimeLocal = true), member))
        assertEquals(emptyList(), split(obj, member.copy(runtimeLocal = true)))
    }
}
