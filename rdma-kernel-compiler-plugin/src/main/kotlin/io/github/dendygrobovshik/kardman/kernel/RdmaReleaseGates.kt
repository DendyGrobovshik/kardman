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
package io.github.dendygrobovshik.kardman.kernel

import java.util.Locale

/**
 * Release gates (§9): the hotfix `minHost` invariant and the coverage warning.
 *
 * These are *hard* checks run before the changelog write (the commit point): a failing hotfix
 * check aborts the release, and a coverage drop is surfaced to the developer for conscious
 * confirmation.
 */
object RdmaReleaseGates {

    /**
     * A hotfix (bugfix, API unchanged) **must not** bump `minHost`. Returns a non-empty list of
     * errors when the invariant is violated; empty otherwise.
     */
    fun checkHotfix(hotfix: Boolean, minHostBefore: Int, minHostAfter: Int): List<String> {
        if (!hotfix) return emptyList()
        return if (minHostAfter > minHostBefore) {
            listOf("hotfix must not bump minHost (was $minHostBefore, now $minHostAfter)")
        } else {
            emptyList()
        }
    }

    /** The `minHost` of a module = the max `floor` over its symbols (§5.3). */
    fun minHostOf(floor: Map<String, Int>): Int = floor.values.maxOrNull() ?: 0

    /** The symbols responsible for a given `minHost` (the non-emulatable anchors at that floor). */
    fun responsibleSymbols(floor: Map<String, Int>, minHost: Int): List<String> =
        floor.entries.filter { it.value == minHost && minHost > 0 }.map { it.key }.sorted()

    data class Coverage(
        val minHost: Int,
        val reachableHosts: Int,
        val totalHosts: Int,
    ) {
        val reachFraction: Double get() = if (totalHosts == 0) 0.0 else reachableHosts.toDouble() / totalHosts
        val reachPercent: Double get() = reachFraction * 100
    }

    /** The fraction of hosts with `H ≥ minHost` — the audience a `minHost` rise still reaches. */
    fun coverage(hostVersions: Collection<Int>, minHost: Int): Coverage {
        val reachable = hostVersions.count { it >= minHost }
        return Coverage(minHost, reachable, hostVersions.size)
    }

    /**
     * The human-readable coverage warning shown before publishing a release that raises `minHost`
     * (§9). Empty string when there is nothing to warn about.
     */
    fun coverageWarning(floor: Map<String, Int>, hostVersions: Collection<Int>): String {
        val minHost = minHostOf(floor)
        if (minHost == 0) return ""
        val cov = coverage(hostVersions, minHost)
        val responsible = responsibleSymbols(floor, minHost)
        val cause = if (responsible.isEmpty()) "unknown" else responsible.joinToString(", ")
        return "this release raises minHost to $minHost (symbol(s): $cause); " +
            "it will reach ${String.format(Locale.US, "%.1f", cov.reachPercent)}% of clients (${cov.reachableHosts}/${cov.totalHosts})"
    }
}
