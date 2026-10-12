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

import io.github.dendygrobovshik.kardman.kernel.RdmaFloor
import io.github.dendygrobovshik.kardman.kernel.RdmaPluginContractBuilder
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class RdmaPluginContractBuilderTest {

    @Test
    fun `minHost is max floor over used symbols`() {
        val contract = RdmaPluginContractBuilder.build(
            pluginId = "alice:counter",
            version = 3,
            builtAgainst = 7,
            moduleDeps = listOf("internal", "user_alice"),
            usedSymbols = listOf("a.Text", "a.Person", "a.HttpClient"),
            floor = mapOf("a.Text" to 5, "a.Person" to 0, "a.HttpClient" to 2),
        )
        assertEquals(5, contract.minHost)
        assertEquals(3, contract.version)
        assertEquals(7, contract.builtAgainst)
        assertEquals(listOf("internal", "user_alice"), contract.moduleDeps)
    }

    @Test
    fun `minHost is zero when no used symbol has a floor`() {
        val contract = RdmaPluginContractBuilder.build(
            pluginId = "bob:services",
            version = 1,
            builtAgainst = 7,
            moduleDeps = listOf("internal"),
            usedSymbols = listOf("a.HttpClient"),
            floor = emptyMap(),
        )
        assertEquals(0, contract.minHost)
    }

    @Test
    fun `usedSymbols are deduplicated and sorted`() {
        val contract = RdmaPluginContractBuilder.build(
            pluginId = "alice:counter",
            version = 1,
            builtAgainst = 0,
            moduleDeps = emptyList(),
            usedSymbols = listOf("a.B", "a.A", "a.B"),
            floor = emptyMap(),
        )
        assertEquals(listOf("a.A", "a.B"), contract.usedSymbols)
    }

    @Test
    fun `json round trips`() {
        val contract = RdmaPluginContractBuilder.build(
            pluginId = "alice:counter",
            version = 4,
            builtAgainst = 9,
            moduleDeps = listOf("internal", "user_alice"),
            usedSymbols = listOf("a.Text"),
            floor = mapOf("a.Text" to 9),
        )
        val decoded = RdmaPluginContractBuilder.fromJson(RdmaPluginContractBuilder.toJson(contract))
        assertEquals(contract, decoded)
    }

    @Test
    fun `floor map write then read round trips`() {
        val dir = File.createTempFile("rdma-floor", "").also { it.delete() }.apply { mkdirs() }
        try {
            val written = RdmaFloor.write("internal", mapOf("a.Text" to 5, "a.Person" to 0), dir)
            assertEquals(true, written.isFile)
            assertEquals(mapOf("a.Text" to 5, "a.Person" to 0), RdmaFloor.read("internal", dir))
            assertEquals(emptyMap(), RdmaFloor.read("missing", dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
