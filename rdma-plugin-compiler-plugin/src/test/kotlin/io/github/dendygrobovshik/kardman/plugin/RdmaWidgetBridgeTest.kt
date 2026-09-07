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

class RdmaWidgetBridgeTest {

    @Test
    fun `generates composable widget bridge with typed params`() {
        val fn = RdmaPluginFunction(
            name = "SearchBar",
            qualifiedName = "com.example.kernel.SearchBar",
            composable = true,
            parameters = listOf(
                RdmaPluginParameter("value", RdmaParamKind.VALUE, "String"),
                RdmaPluginParameter("onValueChange", RdmaParamKind.CALLBACK, "(String) -> Unit", 1),
                RdmaPluginParameter("onClear", RdmaParamKind.CALLBACK, "() -> Unit", 0),
            ),
        )
        val src = RdmaPluginTransformState.buildWidgetBridge(listOf(fn))
        assertContains(src, "@Composable")
        assertContains(src, "fun rdmaSearchBar(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit)")
        assertContains(
            src,
            "js(\"RDMA\").composeSearchBar(value, js(\"RDMA\").registerBlock(onValueChange), js(\"RDMA\").registerBlock(onClear))",
        )
    }

    @Test
    fun `generates content widget bridge`() {
        val fn = RdmaPluginFunction(
            name = "Card",
            qualifiedName = "com.example.kernel.Card",
            composable = true,
            parameters = listOf(
                RdmaPluginParameter("content", RdmaParamKind.CONTENT, "@Composable () -> Unit", 0),
            ),
        )
        val src = RdmaPluginTransformState.buildWidgetBridge(listOf(fn))
        assertContains(src, "fun rdmaCard(content: @Composable () -> Unit)")
        assertContains(src, "js(\"RDMA\").composeCard(js(\"RDMA\").registerBlock(content))")
    }
}
