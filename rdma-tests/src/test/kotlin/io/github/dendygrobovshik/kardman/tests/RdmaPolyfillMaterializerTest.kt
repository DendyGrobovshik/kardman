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

import io.github.dendygrobovshik.kardman.kernel.PolyfillResult
import io.github.dendygrobovshik.kardman.kernel.RdmaPolyfillMaterializer
import io.github.dendygrobovshik.kardman.kernel.RdmaSubgraph
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSourceRange
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaSymbolSources
import io.github.dendygrobovshik.kardman.types.RdmaUse
import org.junit.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdmaPolyfillMaterializerTest {

    private val source = """
        |package com.example.kernel
        |
        |import io.github.dendygrobovshik.kardman.RDMA
        |
        |@RDMA
        |fun foo(x: Int): Int = boo(x) + goo(x)
        |
        |@RDMA
        |fun goo(x: Int): Int = x
        |
        |private fun boo(x: Int): Int = x + 1
    """.trimMargin()

    private fun rangeOf(file: File, snippet: String): RdmaSourceRange {
        val text = file.readText()
        val start = text.indexOf(snippet)
        require(start >= 0) { "snippet not found: $snippet" }
        return RdmaSourceRange(file.path, start, start + snippet.length)
    }

    private fun materialize(entries: Set<String>, nodes: Set<String>): String {
        val dir = File.createTempFile("rdma-polyfill", "").also { it.delete() }.apply { mkdirs() }
        val file = File(dir, "Kernel.kt")
        try {
            file.writeText(source)
            val decls = listOf(
                RdmaDeclaration("com.example.kernel.foo", RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", true, rangeOf(file, "@RDMA\nfun foo(x: Int): Int = boo(x) + goo(x)")),
                RdmaDeclaration("com.example.kernel.goo", RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", true, rangeOf(file, "@RDMA\nfun goo(x: Int): Int = x")),
                RdmaDeclaration("com.example.kernel.boo", RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", false, rangeOf(file, "private fun boo(x: Int): Int = x + 1")),
            )
            val analysis = RdmaAnalysis("m", decls, listOf(RdmaUse("com.example.kernel.foo", "com.example.kernel.boo"), RdmaUse("com.example.kernel.foo", "com.example.kernel.goo")))
            val files = RdmaPolyfillMaterializer.materialize(analysis, PolyfillResult(entries, nodes))
            return files.joinToString("\n// ---- file ----\n") { it.content }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `materializes entry and closure but not external boundary`() {
        val output = materialize(setOf("com.example.kernel.foo"), setOf("com.example.kernel.foo", "com.example.kernel.boo"))
        assertContains(output, "package com.example.kernel")
        assertFalse(output.contains("import io.github.dendygrobovshik.kardman.RDMA"), "annotation import must be dropped")
        assertContains(output, "fun foo(x: Int): Int")
        assertContains(output, "private fun boo(x: Int): Int = x + 1")
        assertFalse(output.contains("goo(x: Int): Int = x"), "external boundary must not be materialized with a body")
    }

    @Test
    fun `rewrites external RDMA call to RDMA`() {
        val output = materialize(setOf("com.example.kernel.foo"), setOf("com.example.kernel.foo", "com.example.kernel.boo"))
        assertContains(output, "RDMA.goo(x)")
    }

    @Test
    fun `generates external object for boundaries`() {
        val output = materialize(setOf("com.example.kernel.foo"), setOf("com.example.kernel.foo", "com.example.kernel.boo"))
        assertContains(output, "external object RDMA {")
        assertContains(output, "fun goo(p0: dynamic): dynamic")
    }

    @Test
    fun `emits self registration for entries`() {
        val output = materialize(setOf("com.example.kernel.foo"), setOf("com.example.kernel.foo", "com.example.kernel.boo"))
        assertContains(output, "js(\"RDMA\").foo = ::foo")
    }

    @Test
    fun `returns empty for empty polyfill`() {
        val dir = File.createTempFile("rdma-polyfill", "").also { it.delete() }.apply { mkdirs() }
        try {
            val files = RdmaPolyfillMaterializer.materialize(RdmaAnalysis("m"), PolyfillResult(emptySet(), emptySet()))
            assertTrue(files.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `materializes a removed class with a create factory`() {
        val classSrc = """
            |@RDMA
            |class Person(val name: String, val age: Int) {
            |    fun greet(): String = "hi " + name
            |}
        """.trimMargin()
        val snapshot = RdmaSymbolSources("m", mapOf("com.example.kernel.Person" to classSrc))
        val analysis = RdmaAnalysis(
            "m",
            listOf(RdmaDeclaration("com.example.kernel.Person", RdmaSymbolKind.CLASS, "h", true, null)),
        )
        val files = RdmaPolyfillMaterializer.materializeRemovedClasses(analysis, listOf("com.example.kernel.Person"), snapshot)
        assertEquals(1, files.size)
        assertContains(files[0].content, "class Person")
        assertContains(files[0].content, "createPerson")
        assertContains(files[0].content, "new Person(...args)")
        assertFalse(files[0].content.contains("@RDMA"), "annotation must be stripped")
    }
}
