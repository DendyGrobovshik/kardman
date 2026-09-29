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

import io.github.dendygrobovshik.kardman.kernel.RdmaContentHasher
import io.github.dendygrobovshik.kardman.kernel.RdmaDeltaBundle
import io.github.dendygrobovshik.kardman.kernel.RdmaSharedState
import io.github.dendygrobovshik.kardman.kernel.PolyfillSourceFile
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaSharedStateTest {

    private fun fn(fqn: String, isRdma: Boolean) =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", isRdma)

    private fun prop(fqn: String, isMutable: Boolean, isRdma: Boolean = false) =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_PROPERTY, "h", isRdma, isMutable = isMutable)

    private fun analysis(decls: List<RdmaDeclaration>, uses: List<RdmaUse>) =
        RdmaAnalysis("m", decls, uses)

    @Test
    fun `mutable private var reachable from two RDMA functions is shared`() {
        val a = analysis(
            listOf(fn("a.read", true), fn("b.write", true), prop("v", isMutable = true)),
            listOf(RdmaUse("a.read", "v"), RdmaUse("b.write", "v")),
        )
        assertEquals(setOf("v"), RdmaSharedState.detect(a))
        assertTrue(RdmaSharedState.hasSharedState(a))
    }

    @Test
    fun `mutable private var reachable from one RDMA function is not shared`() {
        val a = analysis(
            listOf(fn("a.read", true), prop("v", isMutable = true)),
            listOf(RdmaUse("a.read", "v")),
        )
        assertEquals(emptySet(), RdmaSharedState.detect(a))
    }

    @Test
    fun `closure is cut at RDMA boundaries`() {
        val a = analysis(
            listOf(fn("outer", true), fn("inner", true), prop("v", isMutable = true)),
            listOf(RdmaUse("inner", "v"), RdmaUse("outer", "inner")),
        )
        // `outer` reaches `v` only through the `@RDMA` `inner` boundary -> not shared.
        assertEquals(emptySet(), RdmaSharedState.detect(a))
    }

    @Test
    fun `immutable private state is never shared`() {
        val a = analysis(
            listOf(fn("a", true), fn("b", true), prop("v", isMutable = false)),
            listOf(RdmaUse("a", "v"), RdmaUse("b", "v")),
        )
        assertEquals(emptySet(), RdmaSharedState.detect(a))
    }

    @Test
    fun `delta bundle splits into content-addressed pieces without shared state`() {
        val files = listOf(
            PolyfillSourceFile("A.kt", "package a\nfun x() = 1"),
            PolyfillSourceFile("B.kt", "package a\nfun y() = 2"),
        )
        val pieces = RdmaDeltaBundle.split(files, sharedState = emptySet())
        assertEquals(2, pieces.size)
        assertEquals(RdmaContentHasher.sha256("package a\nfun x() = 1"), pieces[0].key)
    }

    @Test
    fun `delta bundle stays monolithic with shared state`() {
        val files = listOf(
            PolyfillSourceFile("A.kt", "package a\nfun x() = 1"),
            PolyfillSourceFile("B.kt", "package a\nfun y() = 2"),
        )
        val pieces = RdmaDeltaBundle.split(files, sharedState = setOf("v"))
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].content.contains("fun x()"))
        assertTrue(pieces[0].content.contains("fun y()"))
    }

    @Test
    fun `delta bundle of no files is empty`() {
        assertEquals(emptyList(), RdmaDeltaBundle.split(emptyList(), emptySet()))
    }
}
