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
package io.github.dendygrobovshik.kardman.integration

import io.github.dendygrobovshik.kardman.store.RdmaStore
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.PluginState
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import io.github.dendygrobovshik.kardman.types.UpdateSeverity
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Store-level integration test for the plugin state machine over a *simulated timeline* (§7.2–7.4,
 * §8.2, §8.4): deprecation→migration, removal→retention→broken, minHost resolution, regression
 * fixes and per-plugin independence. Complements the emulator E2E (`plugin-lifecycle.sh`), which
 * covers build/delivery/dispatch but cannot advance a 90-day retention window.
 *
 * The clock is injected via `RdmaStore.resync(request, now)` and telemetry via `recordLive`, so
 * the temporal scenarios are deterministic and fast.
 */
class StoreStateMachineTest {

    private val t0: Instant = Instant.parse("2026-09-01T00:00:00Z")

    private fun change(
        fqn: String,
        kind: ChangelogChangeKind,
        hash: String? = "h",
        deprecated: Boolean = false,
        isRdma: Boolean = true,
        symbolKind: RdmaSymbolKind = RdmaSymbolKind.TOP_LEVEL_FUNCTION,
    ) = ChangelogChange(fqn, kind, hash, deprecated, isRdma, symbolKind)

    private fun entry(version: Int, module: String, vararg changes: ChangelogChange) =
        ChangelogEntry(version, "2026-09-01T00:00:00Z", module, changes.toList())

    private fun release(
        pluginId: String,
        version: Int,
        builtAgainst: Int = 1,
        minHost: Int = 0,
        moduleDeps: List<String> = listOf("internal"),
        usedSymbols: List<String> = emptyList(),
    ) = PluginRelease(
        RdmaPluginContract(
            pluginId = pluginId,
            version = version,
            builtAgainst = builtAgainst,
            minHost = minHost,
            moduleDeps = moduleDeps,
            usedSymbols = usedSymbols,
        ),
        bundleHash = "hash-$pluginId-$version",
    )

    private fun req(pluginId: String, version: Int, builtAgainst: Int = 1, hostVersion: Int = 1) =
        ResyncRequest(pluginId, version, builtAgainst, hostVersion)

    // S3: the normal lifecycle — deprecate, then the plugin author migrates, back to Current.
    @Test
    fun `deprecation then migration returns the plugin to current`() {
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 1,
                entries = listOf(
                    entry(1, "internal", change("internal:foo", ChangelogChangeKind.ADD)),
                ),
            ),
        )
        store.registerRelease(release("p1", 1, usedSymbols = listOf("internal:foo")))

        assertEquals(PluginState.CURRENT, store.resync(req("p1", 1), t0).state)

        // Kernel deprecates the symbol (§7.1 S3).
        store.setChangelog(
            Changelog(
                h = 1,
                entries = listOf(
                    entry(1, "internal", change("internal:foo", ChangelogChangeKind.ADD)),
                    entry(2, "internal", change("internal:foo", ChangelogChangeKind.MODIFY, deprecated = true)),
                ),
            ),
        )
        val aging = store.resync(req("p1", 1), t0)
        assertEquals(PluginState.AGING, aging.state)
        assertEquals(UpdateSeverity.RECOMMENDED, aging.severity)

        // The author migrates: a new release no longer references the deprecated symbol.
        store.registerRelease(release("p1", 2, usedSymbols = emptyList()))
        val update = store.resync(req("p1", 1), t0)
        assertEquals(ResyncStatus.UPDATE, update.status)
        assertEquals(2, update.targetVersion)

        val recovered = store.resync(req("p1", 2), t0)
        assertEquals(PluginState.CURRENT, recovered.state)
        assertEquals(ResyncStatus.CURRENT, recovered.status)
    }

    // S4/S5 + §7.3: removal without migration → at-risk (R served) → retention expiry → broken.
    @Test
    fun `removal keeps at-risk while R is retained then becomes broken`() {
        val removalTime = Instant.parse("2026-01-01T00:00:00Z")
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 1,
                entries = listOf(
                    entry(1, "user_b", change("user:bar", ChangelogChangeKind.ADD)),
                    ChangelogEntry(
                        version = 2,
                        time = removalTime.toString(),
                        module = "user_b",
                        changes = listOf(change("user:bar", ChangelogChangeKind.REMOVE, hash = null)),
                    ),
                ),
            ),
        )
        store.registerRelease(
            release("p2", 1, moduleDeps = listOf("internal", "user_b"), usedSymbols = listOf("user:bar")),
        )

        val atRisk = store.resync(req("p2", 1), removalTime.plusSeconds(1))
        assertEquals(PluginState.AT_RISK, atRisk.state)
        assertEquals(UpdateSeverity.MANDATORY, atRisk.severity)
        assertContains(atRisk.polyfills, "R:user_b")

        // Inside the retention window (< 90 days): still served.
        val inside = store.resync(req("p2", 1), removalTime.plus(Duration.ofDays(89)))
        assertEquals(PluginState.AT_RISK, inside.state)

        // After 90 days with no live usage: R dropped → broken → hard refusal.
        val broken = store.resync(req("p2", 1), removalTime.plus(Duration.ofDays(91)))
        assertEquals(PluginState.BROKEN, broken.state)
        assertEquals(ResyncStatus.REFUSED, broken.status)
    }

    // §7.3: live usage above the threshold keeps R alive past the retention window.
    @Test
    fun `live usage keeps R retained past the retention window`() {
        val removalTime = Instant.parse("2026-01-01T00:00:00Z")
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 1,
                entries = listOf(
                    entry(1, "user_b", change("user:bar", ChangelogChangeKind.ADD)),
                    ChangelogEntry(
                        version = 2,
                        time = removalTime.toString(),
                        module = "user_b",
                        changes = listOf(change("user:bar", ChangelogChangeKind.REMOVE, hash = null)),
                    ),
                ),
            ),
        )
        store.registerRelease(
            release("p2", 1, moduleDeps = listOf("internal", "user_b"), usedSymbols = listOf("user:bar")),
        )
        store.recordLive(listOf("user:bar"))
        store.recordLive(listOf("user:bar"))

        val res = store.resync(req("p2", 1), removalTime.plus(Duration.ofDays(200)))
        assertEquals(PluginState.AT_RISK, res.state)
        assertContains(res.polyfills, "R:user_b")
    }

    // §5.4: the newest *compatible* release is chosen by minHost ≤ H.
    @Test
    fun `minHost gates the newest compatible release`() {
        val store = RdmaStore()
        store.setChangelog(Changelog(h = 1))
        store.registerRelease(release("p3", 1, minHost = 0))
        store.registerRelease(release("p3", 2, minHost = 5))

        // Old host (H=3): the newest compatible is v1.
        val old = store.resync(req("p3", 1, hostVersion = 3))
        assertEquals(1, old.targetVersion)
        assertEquals(ResyncStatus.CURRENT, old.status)

        // Newer host (H=6): v2 becomes compatible.
        val newer = store.resync(req("p3", 1, hostVersion = 6))
        assertEquals(ResyncStatus.UPDATE, newer.status)
        assertEquals(2, newer.targetVersion)
    }

    // §5.4: a plugin whose only releases exceed the host's minHost is unavailable.
    @Test
    fun `unavailable when every release requires a newer host`() {
        val store = RdmaStore()
        store.setChangelog(Changelog(h = 1))
        store.registerRelease(release("p5", 1, minHost = 5))

        val res = store.resync(req("p5", 1, hostVersion = 3))
        assertEquals(ResyncStatus.UNAVAILABLE, res.status)
    }

    // §8.4: a regression fix is a forward release (version grows), never a rollback.
    @Test
    fun `regression fix is a forward release`() {
        val store = RdmaStore()
        store.setChangelog(Changelog(h = 1))
        store.registerRelease(release("p4", 1))
        store.registerRelease(release("p4", 2))
        store.registerRelease(release("p4", 3)) // restores v1 code

        val res = store.resync(req("p4", 2))
        assertEquals(ResyncStatus.UPDATE, res.status)
        assertEquals(3, res.targetVersion)
        assertTrue(3 > 2)
    }

    // §7.2: plugin state is computed per plugin, independently.
    @Test
    fun `plugin states are independent`() {
        val store = RdmaStore()
        store.setChangelog(
            Changelog(
                h = 1,
                entries = listOf(
                    entry(1, "internal", change("internal:foo", ChangelogChangeKind.ADD)),
                    entry(2, "internal", change("internal:foo", ChangelogChangeKind.MODIFY, deprecated = true)),
                ),
            ),
        )
        store.registerRelease(release("a", 1, usedSymbols = listOf("internal:foo")))
        store.registerRelease(release("b", 1, usedSymbols = emptyList()))

        assertEquals(PluginState.AGING, store.resync(req("a", 1), t0).state)
        assertEquals(PluginState.CURRENT, store.resync(req("b", 1), t0).state)
    }
}
