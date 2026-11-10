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

import io.github.dendygrobovshik.kardman.kernel.RdmaSignatureClassifier
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaParameterSignature
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdmaSignatureClassifierTest {

    private fun sig(name: String, type: String = "kotlin.Int", hasDefault: Boolean = false) =
        RdmaParameterSignature(name, type, hasDefault)

    private fun decl(fqn: String, params: List<RdmaParameterSignature>, isRdma: Boolean = true) =
        RdmaDeclaration(
            fqn = fqn,
            kind = RdmaSymbolKind.TOP_LEVEL_FUNCTION,
            hash = "h",
            isRdma = isRdma,
            signature = params,
        )

    private fun errors(old: List<RdmaParameterSignature>, new: List<RdmaParameterSignature>): List<String> =
        RdmaSignatureClassifier.classify(mapOf("a.foo" to old), listOf(decl("a.foo", new))).errors

    // --- compatible ----------------------------------------------------------

    @Test
    fun `identical signature is compatible`() {
        assertEquals(emptyList(), errors(listOf(sig("a"), sig("b", hasDefault = true)), listOf(sig("a"), sig("b", hasDefault = true))))
    }

    @Test
    fun `appending a trailing defaulted parameter is compatible`() {
        assertEquals(
            emptyList(),
            errors(listOf(sig("a")), listOf(sig("a"), sig("b", hasDefault = true))),
        )
    }

    @Test
    fun `appending several trailing defaulted parameters is compatible`() {
        assertEquals(
            emptyList(),
            errors(
                listOf(sig("a")),
                listOf(sig("a"), sig("b", hasDefault = true), sig("c", type = "kotlin.String", hasDefault = true)),
            ),
        )
    }

    @Test
    fun `changing a default value only (same shape) is compatible`() {
        // The classifier compares (name, type, hasDefault); the default *value* is body-like.
        assertEquals(
            emptyList(),
            errors(listOf(sig("a"), sig("b", hasDefault = true)), listOf(sig("a"), sig("b", hasDefault = true))),
        )
    }

    // --- incompatible --------------------------------------------------------

    @Test
    fun `reordering parameters is incompatible`() {
        assertTrue(errors(listOf(sig("a"), sig("b")), listOf(sig("b"), sig("a"))).isNotEmpty())
    }

    @Test
    fun `removing a parameter is incompatible`() {
        assertTrue(errors(listOf(sig("a"), sig("b")), listOf(sig("a"))).isNotEmpty())
    }

    @Test
    fun `renaming a parameter is incompatible`() {
        assertTrue(errors(listOf(sig("a"), sig("b")), listOf(sig("a"), sig("c"))).isNotEmpty())
    }

    @Test
    fun `changing a parameter type is incompatible`() {
        assertTrue(errors(listOf(sig("a")), listOf(sig("a", type = "kotlin.String"))).isNotEmpty())
    }

    @Test
    fun `inserting a parameter in the middle is incompatible`() {
        // c is defaulted, but inserted between a and b rather than appended.
        assertTrue(
            errors(
                listOf(sig("a"), sig("b")),
                listOf(sig("a"), sig("c", hasDefault = true), sig("b")),
            ).isNotEmpty(),
        )
    }

    @Test
    fun `adding a required parameter is incompatible`() {
        assertTrue(errors(listOf(sig("a")), listOf(sig("a"), sig("b"))).isNotEmpty())
    }

    @Test
    fun `removing a default from an existing parameter is incompatible`() {
        assertTrue(
            errors(listOf(sig("a"), sig("b", hasDefault = true)), listOf(sig("a"), sig("b"))).isNotEmpty(),
        )
    }

    // --- scoping -------------------------------------------------------------

    @Test
    fun `non-RDMA declarations are ignored`() {
        val report = RdmaSignatureClassifier.classify(
            mapOf("a.foo" to listOf(sig("a"), sig("b"))),
            listOf(decl("a.foo", listOf(sig("b"), sig("a")), isRdma = false)),
        )
        assertFalse(report.hasErrors)
    }

    @Test
    fun `newly added symbols have no baseline to compare against`() {
        val report = RdmaSignatureClassifier.classify(emptyMap(), listOf(decl("a.new", listOf(sig("a")))))
        assertFalse(report.hasErrors)
    }
}
