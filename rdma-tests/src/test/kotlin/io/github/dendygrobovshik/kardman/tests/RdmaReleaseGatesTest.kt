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

import io.github.dendygrobovshik.kardman.kernel.RdmaReleaseGates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaReleaseGatesTest {

    @Test
    fun `hotfix must not bump minHost`() {
        assertTrue(RdmaReleaseGates.checkHotfix(hotfix = true, minHostBefore = 3, minHostAfter = 4).isNotEmpty())
        assertEquals(emptyList(), RdmaReleaseGates.checkHotfix(hotfix = true, minHostBefore = 4, minHostAfter = 4))
        assertEquals(emptyList(), RdmaReleaseGates.checkHotfix(hotfix = true, minHostBefore = 4, minHostAfter = 3))
        assertEquals(emptyList(), RdmaReleaseGates.checkHotfix(hotfix = false, minHostBefore = 0, minHostAfter = 5))
    }

    @Test
    fun `minHostOf and responsibleSymbols`() {
        val floor = mapOf("a.Text" to 5, "a.Helper" to 5, "a.Person" to 0)
        assertEquals(5, RdmaReleaseGates.minHostOf(floor))
        assertEquals(listOf("a.Helper", "a.Text"), RdmaReleaseGates.responsibleSymbols(floor, 5))
        assertEquals(emptyList(), RdmaReleaseGates.responsibleSymbols(floor, 0))
    }

    @Test
    fun `coverage computes reach fraction`() {
        val cov = RdmaReleaseGates.coverage(listOf(1, 2, 5, 6, 6), minHost = 5)
        assertEquals(3, cov.reachableHosts)
        assertEquals(5, cov.totalHosts)
        assertEquals(60.0, cov.reachPercent)
    }

    @Test
    fun `coverage warning is empty when minHost is zero`() {
        assertEquals("", RdmaReleaseGates.coverageWarning(mapOf("a.Person" to 0), listOf(1, 2, 3)))
    }

    @Test
    fun `coverage warning names the responsible symbols and reach percent`() {
        val warning = RdmaReleaseGates.coverageWarning(
            mapOf("a.Text" to 5, "a.Person" to 0),
            listOf(5, 5, 5, 1),
        )
        assertTrue(warning.contains("minHost to 5"))
        assertTrue(warning.contains("a.Text"))
        assertTrue(warning.contains("75.0%"))
    }
}
