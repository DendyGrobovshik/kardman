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

import io.github.dendygrobovshik.kardman.kernel.RdmaPluginReleases
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class RdmaPluginReleasesTest {

    private fun release(pluginId: String, version: Int) =
        PluginRelease(RdmaPluginContract(pluginId = pluginId, version = version), "hash-$pluginId-$version")

    @Test
    fun `nextVersion is monotonic`() {
        assertEquals(1, RdmaPluginReleases.nextVersion(emptyList()))
        assertEquals(3, RdmaPluginReleases.nextVersion(listOf(release("p", 1), release("p", 2))))
    }

    @Test
    fun `append then read round trips and appends in order`() {
        val dir = File.createTempFile("rdma-plugins", "").also { it.delete() }.apply { mkdirs() }
        try {
            RdmaPluginReleases.append(dir, release("alice:counter", 1))
            RdmaPluginReleases.append(dir, release("alice:counter", 2))
            val read = RdmaPluginReleases.read(dir, "alice:counter")
            assertEquals(listOf(1, 2), read.map { it.contract.version })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `read returns empty when absent`() {
        val dir = File.createTempFile("rdma-plugins", "").also { it.delete() }.apply { mkdirs() }
        try {
            assertEquals(emptyList(), RdmaPluginReleases.read(dir, "missing"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loadAll reads every plugin file`() {
        val dir = File.createTempFile("rdma-plugins", "").also { it.delete() }.apply { mkdirs() }
        try {
            RdmaPluginReleases.append(dir, release("alice:counter", 1))
            RdmaPluginReleases.append(dir, release("bob:services", 1))
            val all = RdmaPluginReleases.loadAll(dir)
            assertEquals(setOf("alice:counter", "bob:services"), all.keys)
            assertEquals(1, all["alice:counter"]!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
