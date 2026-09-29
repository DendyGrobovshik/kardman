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
package io.github.dendygrobovshik.kardman.store

import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.PluginState
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import io.github.dendygrobovshik.kardman.types.UpdateSeverity
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class RdmaStoreEngineTest {

    private fun add(fqn: String, deprecated: Boolean = false) =
        ChangelogChange(fqn, ChangelogChangeKind.ADD, "h-$fqn", deprecated)

    private fun remove(fqn: String) = ChangelogChange(fqn, ChangelogChangeKind.REMOVE)

    private fun changelog(entries: List<ChangelogEntry>): Changelog =
        Changelog(h = entries.maxOfOrNull { it.version } ?: 0, entries = entries)

    private fun release(pluginId: String, version: Int, minHost: Int = 0) =
        PluginRelease(
            RdmaPluginContract(pluginId = pluginId, version = version, minHost = minHost),
            "hash-$pluginId-$version",
        )

    // --- resolution ----------------------------------------------------------

    @Test
    fun `resolveNewest picks the newest release compatible with the host`() {
        val releases = listOf(
            release("p", 1, minHost = 0),
            release("p", 2, minHost = 5),
            release("p", 3, minHost = 5),
        )
        assertEquals(1, RdmaStoreEngine.resolveNewest(releases, hostVersion = 4)!!.contract.version)
        assertEquals(3, RdmaStoreEngine.resolveNewest(releases, hostVersion = 5)!!.contract.version)
        assertEquals(null, RdmaStoreEngine.resolveNewest(emptyList(), hostVersion = 5))
    }

    @Test
    fun `worstState picks the most degraded state`() {
        assertEquals(
            PluginState.BROKEN,
            RdmaStoreEngine.worstState(listOf(PluginState.CURRENT, PluginState.AGING, PluginState.BROKEN)),
        )
        assertEquals(PluginState.CURRENT, RdmaStoreEngine.worstState(emptyList()))
    }

    @Test
    fun `severityFor maps states`() {
        assertEquals(UpdateSeverity.NONE, RdmaStoreEngine.severityFor(PluginState.CURRENT))
        assertEquals(UpdateSeverity.RECOMMENDED, RdmaStoreEngine.severityFor(PluginState.AGING))
        assertEquals(UpdateSeverity.MANDATORY, RdmaStoreEngine.severityFor(PluginState.AT_RISK))
        assertEquals(UpdateSeverity.MANDATORY, RdmaStoreEngine.severityFor(PluginState.BROKEN))
    }

    // --- symbol state --------------------------------------------------------

    private fun symbolChangelog(): Changelog = changelog(
        listOf(
            ChangelogEntry(1, "t", "internal", listOf(
                add("a.dep", deprecated = true),
                add("a.rem"),
                add("a.ok"),
            )),
            ChangelogEntry(2, "2026-01-01T00:00:00Z", "internal", listOf(remove("a.rem"))),
        ),
    )

    @Test
    fun `symbol states derive from the changelog`() {
        val c = symbolChangelog()
        assertEquals(PluginState.AGING, RdmaStoreEngine.symbolState(c, "a.dep") { true })
        assertEquals(PluginState.AT_RISK, RdmaStoreEngine.symbolState(c, "a.rem") { true })
        assertEquals(PluginState.BROKEN, RdmaStoreEngine.symbolState(c, "a.rem") { false })
        assertEquals(PluginState.CURRENT, RdmaStoreEngine.symbolState(c, "a.ok") { false })
    }

    // --- retention -----------------------------------------------------------

    @Test
    fun `R is retained while young or still in use`() {
        val now = Instant.parse("2026-04-01T00:00:00Z")
        val removed = Instant.parse("2026-03-01T00:00:00Z") // 31 days ago
        assertEquals(true, RdmaStoreEngine.isRRetained(removed, now, usageFraction = 0.0))

        val old = Instant.parse("2025-01-01T00:00:00Z") // > 90 days ago
        assertEquals(true, RdmaStoreEngine.isRRetained(old, now, usageFraction = 0.01)) // still used
        assertEquals(false, RdmaStoreEngine.isRRetained(old, now, usageFraction = 0.0)) // dropped
    }
}
