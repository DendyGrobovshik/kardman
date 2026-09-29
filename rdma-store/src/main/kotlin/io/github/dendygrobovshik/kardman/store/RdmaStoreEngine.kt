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

import io.github.dendygrobovshik.kardman.kernel.RdmaChangelog
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.PluginState
import io.github.dendygrobovshik.kardman.types.UpdateSeverity
import java.time.Duration
import java.time.Instant

/**
 * Pure, stateless store logic (§7.2–7.4, §8.2): plugin state machine, severity, re-sync
 * resolution and the `R` retention policy.
 */
object RdmaStoreEngine {

    const val RETENTION_DAYS: Long = 90
    const val USAGE_THRESHOLD: Double = 0.0001 // 0.01%

    /** The newest compatible release on host [hostVersion] (`minHost ≤ H`), or null. */
    fun resolveNewest(releases: List<PluginRelease>, hostVersion: Int): PluginRelease? =
        releases.filter { it.contract.minHost <= hostVersion }.maxByOrNull { it.contract.version }

    /** The worst state among a collection (enum ordinals: CURRENT < AGING < AT_RISK < BROKEN). */
    fun worstState(states: Iterable<PluginState>): PluginState =
        states.maxByOrNull { it.ordinal } ?: PluginState.CURRENT

    fun severityFor(state: PluginState): UpdateSeverity = when (state) {
        PluginState.CURRENT -> UpdateSeverity.NONE
        PluginState.AGING -> UpdateSeverity.RECOMMENDED
        PluginState.AT_RISK -> UpdateSeverity.MANDATORY
        PluginState.BROKEN -> UpdateSeverity.MANDATORY
    }

    /** The state of a single symbol (§7.1) given the changelog and whether its `R` is retained. */
    fun symbolState(changelog: Changelog, fqn: String, rRetained: (String) -> Boolean): PluginState {
        val deprecated = RdmaChangelog.deprecatedAll(changelog)
        val tombstoned = RdmaChangelog.tombstoned(changelog)
        return when {
            fqn in deprecated -> PluginState.AGING
            fqn in tombstoned && rRetained(fqn) -> PluginState.AT_RISK
            fqn in tombstoned -> PluginState.BROKEN
            else -> PluginState.CURRENT
        }
    }

    /** The plugin state is the worst state among the symbols it uses (§7.2). */
    fun pluginState(
        changelog: Changelog,
        usedSymbols: List<String>,
        rRetained: (String) -> Boolean,
    ): PluginState = worstState(usedSymbols.map { symbolState(changelog, it, rRetained) })

    /**
     * Whether a removed symbol's `R` polyfill is still served (§7.3). `R` is dropped when **both**
     * ≥ [RETENTION_DAYS] days have passed since removal **and** live usage is < [USAGE_THRESHOLD].
     */
    fun isRRetained(removedAt: Instant, now: Instant, usageFraction: Double): Boolean {
        val ageDays = Duration.between(removedAt, now).toDays()
        val expired = ageDays >= RETENTION_DAYS && usageFraction < USAGE_THRESHOLD
        return !expired
    }
}
