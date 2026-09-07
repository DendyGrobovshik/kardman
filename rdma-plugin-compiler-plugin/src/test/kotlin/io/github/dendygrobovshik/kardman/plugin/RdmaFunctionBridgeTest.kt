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
package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class RdmaFunctionBridgeTest {

    @Test
    fun `generates bridge with dynamic params and lambda registration`() {
        val fn = RdmaPluginFunction(
            name = "forEach",
            qualifiedName = "com.example.kernel.forEach",
            composable = false,
            parameters = listOf(
                RdmaPluginParameter("list", RdmaParamKind.VALUE, "dynamic"),
                RdmaPluginParameter("body", RdmaParamKind.CALLBACK, "(dynamic) -> dynamic", 1),
            ),
        )
        val src = RdmaPluginTransformState.buildFunctionBridge(listOf(fn))
        assertContains(src, "fun rdmaForEach(p0: dynamic, p1: (dynamic) -> dynamic): dynamic")
        assertContains(src, "js(\"RDMA\").forEach(p0, js(\"RDMA\").registerBlock(p1))")
    }

    @Test
    fun `generates bridge for value-only function`() {
        val fn = RdmaPluginFunction(
            name = "add",
            qualifiedName = "com.example.kernel.add",
            composable = false,
            parameters = listOf(
                RdmaPluginParameter("a", RdmaParamKind.VALUE, "dynamic"),
                RdmaPluginParameter("b", RdmaParamKind.VALUE, "dynamic"),
            ),
        )
        val src = RdmaPluginTransformState.buildFunctionBridge(listOf(fn))
        assertContains(src, "fun rdmaAdd(p0: dynamic, p1: dynamic): dynamic")
        assertContains(src, "js(\"RDMA\").add(p0, p1)")
    }

    @Test
    fun `skips composable functions`() {
        val fn = RdmaPluginFunction(
            name = "Text",
            qualifiedName = "com.example.kernel.Text",
            composable = true,
            parameters = emptyList(),
        )
        val src = RdmaPluginTransformState.buildFunctionBridge(listOf(fn))
        assertTrue(!src.contains("rdmaText"))
    }
}
