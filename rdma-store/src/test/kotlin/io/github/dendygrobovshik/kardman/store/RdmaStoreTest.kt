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
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import io.github.dendygrobovshik.kardman.types.UpdateSeverity
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

class RdmaStoreTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun release(pluginId: String, version: Int, minHost: Int, usedSymbols: List<String>) =
        PluginRelease(
            RdmaPluginContract(
                pluginId = pluginId,
                version = version,
                minHost = minHost,
                moduleDeps = listOf("internal", "user_alice"),
                usedSymbols = usedSymbols,
            ),
            "hash-$pluginId-$version",
        )

    @Test
    fun `resync updates to the newest compatible version`() {
        val store = RdmaStore()
        store.registerRelease(release("alice:counter", 1, 0, listOf("a.ok")))
        store.registerRelease(release("alice:counter", 2, 0, listOf("a.ok")))

        val response = store.resync(ResyncRequest("alice:counter", 1, 0, hostVersion = 10), now)
        assertEquals(ResyncStatus.UPDATE, response.status)
        assertEquals(2, response.targetVersion)
        assertEquals("hash-alice:counter-2", response.bundleHash)
        assertEquals(UpdateSeverity.NONE, response.severity)
    }

    @Test
    fun `resync returns current when up to date`() {
        val store = RdmaStore()
        store.registerRelease(release("alice:counter", 2, 0, listOf("a.ok")))
        val response = store.resync(ResyncRequest("alice:counter", 2, 0, hostVersion = 10), now)
        assertEquals(ResyncStatus.CURRENT, response.status)
        assertEquals(2, response.targetVersion)
    }

    @Test
    fun `resync is unavailable when no release fits the host`() {
        val store = RdmaStore()
        store.registerRelease(release("alice:counter", 2, 5, listOf("a.ok")))
        val response = store.resync(ResyncRequest("alice:counter", 1, 0, hostVersion = 3), now)
        assertEquals(ResyncStatus.UNAVAILABLE, response.status)
    }

    @Test
    fun `resync refuses a broken plugin whose R polyfill was dropped`() {
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 2,
                entries = listOf(
                    ChangelogEntry(1, "2025-01-01T00:00:00Z", "user_alice", listOf(
                        ChangelogChange("a.old", ChangelogChangeKind.ADD, "h"),
                    )),
                    ChangelogEntry(2, "2025-01-02T00:00:00Z", "user_alice", listOf(
                        ChangelogChange("a.old", ChangelogChangeKind.REMOVE),
                    )),
                ),
            ),
        )
        store.registerRelease(release("alice:counter", 1, 0, listOf("a.old")))
        store.registerRelease(release("alice:counter", 2, 0, listOf("a.ok")))

        // now (2026-01-01) is > 90 days after removal (2025-01-02) and usage is 0 -> R dropped -> BROKEN.
        val response = store.resync(ResyncRequest("alice:counter", 1, 0, hostVersion = 10), now)
        assertEquals(PluginState.BROKEN, response.state)
        assertEquals(ResyncStatus.REFUSED, response.status)
    }

    @Test
    fun `resync keeps serving at-risk plugin while R is retained`() {
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 2,
                entries = listOf(
                    ChangelogEntry(1, "2026-01-01T00:00:00Z", "user_alice", listOf(
                        ChangelogChange("a.old", ChangelogChangeKind.ADD, "h"),
                    )),
                    ChangelogEntry(2, "2026-01-02T00:00:00Z", "user_alice", listOf(
                        ChangelogChange("a.old", ChangelogChangeKind.REMOVE),
                    )),
                ),
            ),
        )
        store.registerRelease(release("alice:counter", 1, 0, listOf("a.old")))
        store.registerRelease(release("alice:counter", 2, 0, listOf("a.ok")))

        // removal was recent -> R retained -> AT_RISK, serve the update with an R polyfill key.
        val response = store.resync(ResyncRequest("alice:counter", 1, 0, hostVersion = 10), now)
        assertEquals(PluginState.AT_RISK, response.state)
        assertEquals(UpdateSeverity.MANDATORY, response.severity)
        assertEquals(ResyncStatus.UPDATE, response.status)
        assertEquals(true, response.polyfills.any { it.startsWith("R:") })
    }
}
