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

import io.github.dendygrobovshik.kardman.kernel.RdmaChangelog
import io.github.dendygrobovshik.kardman.kernel.RdmaPolyfillMaterializer
import io.github.dendygrobovshik.kardman.kernel.RdmaRemovalPolicy
import io.github.dendygrobovshik.kardman.kernel.RdmaSourceSnapshot
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.RdmaSymbolSources
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RdmaRemovalPolicyTest {

    private fun change(fqn: String, kind: ChangelogChangeKind, deprecated: Boolean = false, isRdma: Boolean = true, symbolKind: RdmaSymbolKind? = RdmaSymbolKind.TOP_LEVEL_FUNCTION) =
        ChangelogChange(fqn, kind, if (kind == ChangelogChangeKind.REMOVE) null else "h-$fqn", deprecated, isRdma, symbolKind)

    private fun changelog(vararg entries: ChangelogEntry) = Changelog(h = entries.maxOfOrNull { it.version } ?: 0, entries = entries.toList())

    private fun entry(version: Int, module: String, vararg changes: ChangelogChange) =
        ChangelogEntry(version, "t", module, changes.toList())

    // --- lastKnown / deprecated fold ----------------------------------------

    @Test
    fun `lastKnown folds to the most recent change per fqn`() {
        val c = changelog(
            entry(1, "m", change("a.Foo", ChangelogChangeKind.ADD)),
            entry(2, "m", change("a.Foo", ChangelogChangeKind.MODIFY, deprecated = true)),
        )
        val last = RdmaChangelog.lastKnown(c, "m")
        assertEquals(true, last["a.Foo"]!!.deprecated)
    }

    @Test
    fun `deprecated fold reflects add modify and remove`() {
        val c = changelog(
            entry(1, "m", change("a.Foo", ChangelogChangeKind.ADD)),
            entry(2, "m", change("a.Foo", ChangelogChangeKind.MODIFY, deprecated = true)),
            entry(3, "m", change("a.Foo", ChangelogChangeKind.REMOVE)),
        )
        assertEquals(emptySet(), RdmaChangelog.deprecated(c, "m"))
    }

    // --- removal gate --------------------------------------------------------

    @Test
    fun `internal removal without deprecation is an error`() {
        val c = changelog(entry(1, "internal", change("a.Foo", ChangelogChangeKind.ADD)))
        val report = RdmaRemovalPolicy.validate(c, "internal", setOf("a.Foo"), setOf("internal"))
        assertTrue(report.hasErrors)
        assertTrue(report.errors.any { it.contains("a.Foo") })
    }

    @Test
    fun `user-module removal without deprecation is a warning`() {
        val c = changelog(entry(1, "user_alice", change("a.Foo", ChangelogChangeKind.ADD)))
        val report = RdmaRemovalPolicy.validate(c, "user_alice", setOf("a.Foo"), setOf("internal"))
        assertFalse(report.hasErrors)
        assertEquals(1, report.warnings.size)
    }

    @Test
    fun `removal after deprecation is clean`() {
        val c = changelog(
            entry(1, "internal", change("a.Foo", ChangelogChangeKind.ADD)),
            entry(2, "internal", change("a.Foo", ChangelogChangeKind.MODIFY, deprecated = true)),
        )
        val report = RdmaRemovalPolicy.validate(c, "internal", setOf("a.Foo"), setOf("internal"))
        assertFalse(report.hasErrors)
        assertEquals(emptyList(), report.warnings)
    }

    @Test
    fun `private helper removal is exempt`() {
        val c = changelog(entry(1, "internal", change("a.helper", ChangelogChangeKind.ADD, isRdma = false)))
        val report = RdmaRemovalPolicy.validate(c, "internal", setOf("a.helper"), setOf("internal"))
        assertFalse(report.hasErrors)
        assertEquals(emptyList(), report.warnings)
    }

    @Test
    fun `class member removal is exempt when the class is the boundary`() {
        val c = changelog(entry(1, "internal", change("a.Foo.bar", ChangelogChangeKind.ADD, isRdma = true, symbolKind = RdmaSymbolKind.CLASS_METHOD)))
        val report = RdmaRemovalPolicy.validate(c, "internal", setOf("a.Foo.bar"), setOf("internal"))
        assertFalse(report.hasErrors)
        assertEquals(emptyList(), report.warnings)
    }

    @Test
    fun `removedBoundaries returns only RDMA boundary symbols`() {
        val c = changelog(
            entry(1, "m",
                change("a.Foo", ChangelogChangeKind.ADD, symbolKind = RdmaSymbolKind.CLASS),
                change("a.helper", ChangelogChangeKind.ADD, isRdma = false),
                change("a.bar", ChangelogChangeKind.ADD, symbolKind = RdmaSymbolKind.TOP_LEVEL_FUNCTION),
            ),
        )
        val boundaries = RdmaRemovalPolicy.removedBoundaries(c, "m", setOf("a.Foo", "a.helper", "a.bar"))
        assertEquals(listOf("a.Foo", "a.bar"), boundaries.map { it.fqn })
    }

    // --- source snapshot -----------------------------------------------------

    @Test
    fun `source snapshot write then read round trips`() {
        val dir = File.createTempFile("rdma-src", "").also { it.delete() }.apply { mkdirs() }
        try {
            val srcFile = File(dir, "Foo.kt")
            srcFile.writeText("package a\n\nimport x\n\n@RDMA\nfun foo(): Int = 1\n")
            val analysis = RdmaAnalysis(
                moduleId = "m",
                declarations = listOf(
                    RdmaDeclaration("a.foo", RdmaSymbolKind.TOP_LEVEL_FUNCTION, "h", true,
                        sourceRange = io.github.dendygrobovshik.kardman.types.RdmaSourceRange(srcFile.path, srcFile.readText().indexOf("fun foo"), srcFile.readText().length)),
                ),
            )
            val written = RdmaSourceSnapshot.write(analysis, dir)
            assertTrue(written.isFile)
            val read = RdmaSourceSnapshot.read("m", dir)
            assertTrue(read.sources["a.foo"]!!.contains("fun foo(): Int = 1"))
            assertTrue(read.sources["a.foo"]!!.contains("package a"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `source snapshot read returns empty when absent`() {
        val dir = File.createTempFile("rdma-src", "").also { it.delete() }.apply { mkdirs() }
        try {
            assertEquals(emptyMap(), RdmaSourceSnapshot.read("m", dir).sources)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- R polyfill materialization -----------------------------------------

    @Test
    fun `r polyfill re-provides a removed top-level function`() {
        val snapshot = RdmaSymbolSources(
            "m",
            mapOf("a.foo" to "package a\n\nimport x\n\n@RDMA\nfun foo(): Int = 1"),
        )
        val analysis = RdmaAnalysis("m", declarations = emptyList())
        val files = RdmaPolyfillMaterializer.materializeRemoved(analysis, listOf("a.foo"), snapshot)
        assertEquals(1, files.size)
        val content = files[0].content
        assertTrue(content.contains("fun foo(): Int = 1"))
        assertTrue(!content.contains("@RDMA"))
        assertTrue(content.contains("js(\"RDMA\").foo = ::foo"))
    }

    @Test
    fun `r polyfill skips symbols missing from snapshot`() {
        val snapshot = RdmaSymbolSources("m", emptyMap())
        val analysis = RdmaAnalysis("m", declarations = emptyList())
        assertEquals(emptyList(), RdmaPolyfillMaterializer.materializeRemoved(analysis, listOf("a.foo"), snapshot))
    }
}
