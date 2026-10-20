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
package io.github.dendygrobovshik.kardman.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RdmaTomlTest {

    @Test
    fun `parses the full rdma toml schema`() {
        val config = RdmaToml.parse(
            """
            # framework config
            [kernel]
            rootPackage = "com.example.kernel"
            project = ":tests:stand:kernel"
            internals = [":tests:stand:kernel:internal", ":tests:stand:kernel:user:itest"]
            oldestActiveKernel = 42

            [plugin]
            rootPackage = "com.example.plugin"
            project = ":tests:stand:plugin"

            [toolchain]
            oldestActiveGeneration = "g1"

            [hermes]
            path = "third_party/bin/hermesc"
            """.trimIndent(),
        )

        assertEquals("com.example.kernel", config.kernel.rootPackage)
        assertEquals(":tests:stand:kernel", config.kernel.project)
        assertEquals(listOf(":tests:stand:kernel:internal", ":tests:stand:kernel:user:itest"), config.kernel.internals)
        assertEquals(42, config.kernel.oldestActiveKernel)
        assertEquals("com.example.plugin", config.plugin.rootPackage)
        assertEquals(":tests:stand:plugin", config.plugin.project)
        assertEquals("g1", config.toolchain.oldestActiveGeneration)
        assertEquals("third_party/bin/hermesc", config.hermes.path)
    }

    @Test
    fun `missing keys fall back to empty defaults`() {
        val config = RdmaToml.parse(
            """
            [kernel]
            project = ":kernel"
            """.trimIndent(),
        )
        assertEquals("", config.kernel.rootPackage)
        assertEquals(":kernel", config.kernel.project)
        assertEquals(emptyList(), config.kernel.internals)
        assertNull(config.kernel.oldestActiveKernel)
        assertEquals("", config.toolchain.oldestActiveGeneration)
    }

    @Test
    fun `a missing floor is null, not zero`() {
        val config = RdmaToml.parse("[kernel]\nrootPackage = \"x\"\n")
        assertNull(config.kernel.oldestActiveKernel)
    }
}
