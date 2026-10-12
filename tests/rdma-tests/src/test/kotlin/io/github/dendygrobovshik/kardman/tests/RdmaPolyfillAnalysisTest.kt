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

import io.github.dendygrobovshik.kardman.kernel.RdmaBaseline
import io.github.dendygrobovshik.kardman.kernel.RdmaContentHasher
import io.github.dendygrobovshik.kardman.kernel.RdmaHashDiff
import io.github.dendygrobovshik.kardman.kernel.classHeaderEnd
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RdmaPolyfillAnalysisTest {

    private fun decl(fqn: String, hash: String, kind: RdmaSymbolKind = RdmaSymbolKind.TOP_LEVEL_FUNCTION) =
        RdmaDeclaration(fqn, kind, hash, isRdma = true)

    // --- content hash -------------------------------------------------------

    @Test
    fun `hash is deterministic hex of fixed length`() {
        val a = RdmaContentHasher.sha256("fun foo(): Int = 1")
        val b = RdmaContentHasher.sha256("fun foo(): Int = 1")
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
    }

    @Test
    fun `hash changes with content and is whitespace sensitive`() {
        val original = RdmaContentHasher.sha256("fun foo(): Int = 1")
        assertNotEquals(original, RdmaContentHasher.sha256("fun foo(): Int = 2"))
        assertNotEquals(original, RdmaContentHasher.sha256("fun foo(): Int = 1 "))
    }

    // --- classHeaderEnd -----------------------------------------------------

    @Test
    fun `classHeaderEnd finds opening brace after params`() {
        val text = "@RDMA\nopen class Person(val name: String, val age: Int) {\n}"
        val start = text.indexOf("@RDMA")
        val end = classHeaderEnd(text, start)
        assertEquals("@RDMA\nopen class Person(val name: String, val age: Int) ", text.substring(start, end))
    }

    @Test
    fun `classHeaderEnd skips braces inside params and supertype args`() {
        val text = "class Foo(val cb: () -> Unit = { }) : Base({ }) { fun bar() = 1 }"
        val start = 0
        val end = classHeaderEnd(text, start)
        assertEquals("class Foo(val cb: () -> Unit = { }) : Base({ }) ", text.substring(start, end))
    }

    @Test
    fun `classHeaderEnd handles generic brackets`() {
        val text = "class Box<T : List<Int>>(val x: T) { }"
        val end = classHeaderEnd(text, 0)
        assertEquals("class Box<T : List<Int>>(val x: T) ", text.substring(0, end))
    }

    // --- diff ---------------------------------------------------------------

    @Test
    fun `diff reports added removed and changed`() {
        val current = listOf(
            decl("a.Foo", "hash-a"),
            decl("a.Bar", "hash-b2"),
        )
        val baseline = mapOf(
            "a.Foo" to "hash-a",
            "a.Bar" to "hash-b1",
            "a.Baz" to "hash-c",
        )
        val dirty = RdmaHashDiff.diff(current, baseline)
        assertEquals(setOf<String>(), dirty.added)
        assertEquals(setOf("a.Baz"), dirty.removed)
        assertEquals(setOf("a.Bar"), dirty.changed)
        assertFalse(dirty.isEmpty)
    }

    @Test
    fun `diff detects new declaration as added`() {
        val dirty = RdmaHashDiff.diff(listOf(decl("a.New", "h")), mapOf("a.Old" to "h"))
        assertEquals(setOf("a.New"), dirty.added)
        assertEquals(setOf("a.Old"), dirty.removed)
    }

    @Test
    fun `diff is empty when nothing changed`() {
        val current = listOf(decl("a.Foo", "h1"), decl("a.Bar", "h2"))
        val baseline = mapOf("a.Foo" to "h1", "a.Bar" to "h2")
        assertTrue(RdmaHashDiff.diff(current, baseline).isEmpty)
    }

    // --- baseline round-trip ------------------------------------------------

    @Test
    fun `baseline write then read round trips`() {
        val analysis = RdmaAnalysis(
            moduleId = "user_alice",
            declarations = listOf(decl("a.Person", "h1", RdmaSymbolKind.CLASS), decl("a.Person.greet", "h2", RdmaSymbolKind.CLASS_METHOD)),
        )
        val dir = File.createTempFile("rdma-versions", "").also { it.delete() }.apply { mkdirs() }
        try {
            val written = RdmaBaseline.write(analysis, dir)
            assertTrue(written.isFile)
            val read = RdmaBaseline.read("user_alice", dir)!!
            assertEquals(mapOf("a.Person" to "h1", "a.Person.greet" to "h2"), read)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `baseline read returns null when absent`() {
        val dir = File.createTempFile("rdma-versions", "").also { it.delete() }.apply { mkdirs() }
        try {
            assertEquals(null, RdmaBaseline.read("missing", dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
