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
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaPolyfill
import io.github.dendygrobovshik.kardman.types.RdmaSourceRange
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdmaManualPolyfillTest {

    @Test
    fun `manual polyfill replaces auto materialization and binds the target name`() {
        val dir = File.createTempFile("rdma-polyfill", "").also { it.delete() }.apply { mkdirs() }
        try {
            val src = "package a\n\nimport x\n\n@Polyfill(for = \"a.foo\")\nfun foo_polyfill(): Int = 42\n"
            val file = File(dir, "Polyfills.kt")
            file.writeText(src)

            val analysis = RdmaAnalysis(
                moduleId = "m",
                declarations = listOf(
                    RdmaDeclaration("a.foo", RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", isRdma = true),
                ),
                polyfills = listOf(
                    RdmaPolyfill("a.foo", "a.foo_polyfill", RdmaSourceRange(file.path, src.indexOf("fun foo_polyfill"), src.length)),
                ),
            )
            val result = PolyfillResult(entries = setOf("a.foo"), nodes = setOf("a.foo"))
            val files = RdmaPolyfillMaterializer.materialize(analysis, result)

            assertEquals(1, files.size)
            val content = files[0].content
            assertTrue(content.contains("fun foo_polyfill(): Int = 42"))
            assertTrue(content.contains("js(\"RDMA\").foo = ::foo_polyfill"))
            assertFalse(content.contains("@Polyfill"))
            assertFalse(content.contains("fun foo(): ")) // the auto target is not emitted
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `manual polyfill for a removed function is preferred over the snapshot`() {
        val dir = File.createTempFile("rdma-polyfill", "").also { it.delete() }.apply { mkdirs() }
        try {
            val src = "package a\n\n@Polyfill(for = \"a.removed\")\nfun removed_polyfill(): Int = 7\n"
            val file = File(dir, "Polyfills.kt")
            file.writeText(src)

            val analysis = RdmaAnalysis(
                moduleId = "m",
                polyfills = listOf(
                    RdmaPolyfill("a.removed", "a.removed_polyfill", RdmaSourceRange(file.path, src.indexOf("fun removed_polyfill"), src.length)),
                ),
            )
            val snapshot = io.github.dendygrobovshik.kardman.types.RdmaSymbolSources("m", mapOf("a.removed" to "fun removed() = 0"))
            val files = RdmaPolyfillMaterializer.materializeRemoved(analysis, listOf("a.removed"), snapshot)

            assertEquals(1, files.size)
            assertTrue(files[0].content.contains("fun removed_polyfill(): Int = 7"))
            assertFalse(files[0].content.contains("fun removed() = 0"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `removed function without a manual polyfill falls back to the snapshot`() {
        val analysis = RdmaAnalysis(moduleId = "m")
        val snapshot = io.github.dendygrobovshik.kardman.types.RdmaSymbolSources("m", mapOf("a.removed" to "package a\n\nfun removed() = 0"))
        val files = RdmaPolyfillMaterializer.materializeRemoved(analysis, listOf("a.removed"), snapshot)
        assertEquals(1, files.size)
        assertTrue(files[0].content.contains("fun removed() = 0"))
    }
}
