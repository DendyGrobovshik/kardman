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
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RdmaChangelogTest {

    private fun decl(fqn: String, hash: String = "h-$fqn") =
        RdmaDeclaration(fqn, RdmaSymbolKind.TOP_LEVEL_FUNCTION, hash, isRdma = true)

    private fun entry(version: Int, module: String, vararg changes: Pair<String, ChangelogChangeKind>) =
        ChangelogEntry(
            version = version,
            time = "t",
            module = module,
            changes = changes.map { (fqn, kind) ->
                ChangelogChange(fqn, kind, if (kind == ChangelogChangeKind.REMOVE) null else "h-$fqn")
            },
        )

    // --- counter -------------------------------------------------------------

    @Test
    fun `nextVersion is monotonic and starts at 1 for empty changelog`() {
        assertEquals(1, RdmaChangelog.nextVersion(Changelog()))
        val c = Changelog(h = 1, entries = listOf(entry(1, "m", "a" to ChangelogChangeKind.ADD)))
        assertEquals(2, RdmaChangelog.nextVersion(c))
    }

    @Test
    fun `append bumps the counter and only moves H on static`() {
        val c = Changelog()
        val dynamic = RdmaChangelog.append(c, "m", listOf(ChangelogChange("a", ChangelogChangeKind.ADD, "h-a")), bumpH = false)
        assertEquals(1, RdmaChangelog.latestVersion(dynamic))
        assertEquals(0, dynamic.h)

        val static = RdmaChangelog.append(dynamic, "m", listOf(ChangelogChange("b", ChangelogChangeKind.ADD, "h-b")), bumpH = true)
        assertEquals(2, static.h)
        assertEquals(2, RdmaChangelog.latestVersion(static))
    }

    @Test
    fun `bake advances H to the latest version without adding an entry`() {
        val c = Changelog(
            h = 1,
            entries = listOf(
                entry(1, "m", "a" to ChangelogChangeKind.ADD),
                entry(2, "m", "b" to ChangelogChangeKind.ADD),
            ),
        )
        val baked = RdmaChangelog.bake(c)
        assertEquals(2, baked.h)
        assertEquals(2, baked.entries.size)
    }

    // --- fold / manifest -----------------------------------------------------
    @Test
    fun `manifest folds add modify and remove across versions`() {
        val c = Changelog(
            h = 3,
            entries = listOf(
                entry(1, "m", "a" to ChangelogChangeKind.ADD, "b" to ChangelogChangeKind.ADD),
                entry(2, "m", "a" to ChangelogChangeKind.MODIFY),
                entry(3, "m", "b" to ChangelogChangeKind.REMOVE),
            ),
        )
        assertEquals(mapOf("a" to "h-a", "b" to "h-b"), RdmaChangelog.manifest(c, "m", 2))
        assertEquals(mapOf("a" to "h-a"), RdmaChangelog.manifest(c, "m", 3))
        assertEquals(emptyMap(), RdmaChangelog.manifest(c, "m", 0))
    }

    @Test
    fun `nativeState is the manifest folded at H and is module-scoped`() {
        val c = Changelog(
            h = 2,
            entries = listOf(
                entry(1, "m1", "a" to ChangelogChangeKind.ADD),
                entry(2, "m2", "b" to ChangelogChangeKind.ADD),
                entry(3, "m1", "c" to ChangelogChangeKind.ADD), // after H, excluded
            ),
        )
        assertEquals(mapOf("a" to "h-a"), RdmaChangelog.nativeState(c, "m1"))
        assertEquals(mapOf("b" to "h-b"), RdmaChangelog.nativeState(c, "m2"))
    }

    // --- tombstone -----------------------------------------------------------

    @Test
    fun `tombstoned collects removed fqns`() {
        val c = Changelog(entries = listOf(entry(1, "m", "a" to ChangelogChangeKind.REMOVE)))
        assertEquals(setOf("a"), RdmaChangelog.tombstoned(c))
    }

    @Test
    fun `re-adding a tombstoned fqn throws`() {
        val c = Changelog(entries = listOf(entry(1, "m", "a" to ChangelogChangeKind.ADD), entry(2, "m", "a" to ChangelogChangeKind.REMOVE)))
        try {
            RdmaChangelog.append(c, "m", listOf(ChangelogChange("a", ChangelogChangeKind.ADD, "h-a")), bumpH = false)
            fail("expected re-add of tombstoned FQN to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("tombstoned"))
        }
    }

    // --- changes derivation --------------------------------------------------

    @Test
    fun `changes derives add modify and remove from diff`() {
        val analysis = RdmaAnalysis(
            moduleId = "m",
            declarations = listOf(decl("a.Added"), decl("a.Modified", "new")),
        )
        val baseline = mapOf("a.Modified" to "old", "a.Removed" to "h")
        val changes = RdmaChangelog.changes(analysis, baseline).associateBy { it.fqn }
        assertEquals(ChangelogChangeKind.ADD, changes["a.Added"]!!.kind)
        assertEquals(ChangelogChangeKind.MODIFY, changes["a.Modified"]!!.kind)
        assertEquals("new", changes["a.Modified"]!!.hash)
        assertEquals(ChangelogChangeKind.REMOVE, changes["a.Removed"]!!.kind)
        assertEquals(null, changes["a.Removed"]!!.hash)
    }
}
