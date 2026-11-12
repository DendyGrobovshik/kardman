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
            "js(\"RDMA\").composeSearchBar(value, js(\"RDMA\").registerBlock(onValueChange, \"\"), js(\"RDMA\").registerBlock(onClear, \"\"))",
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
        assertContains(src, "js(\"RDMA\").composeCard(js(\"RDMA\").registerBlock(content, \"\"))")
    }

    @Test
    fun `generates widget bridge for list values and list block handles`() {
        val fn = RdmaPluginFunction(
            name = "NavigationPanel",
            qualifiedName = "com.example.kernel.NavigationPanel",
            composable = true,
            parameters = listOf(
                RdmaPluginParameter("title", RdmaParamKind.VALUE, "String"),
                RdmaPluginParameter("actionLabels", RdmaParamKind.LIST_VALUE, "List<String>", 0),
                RdmaPluginParameter("actions", RdmaParamKind.LIST_HANDLE, "List<() -> Unit>", 0),
                RdmaPluginParameter("content", RdmaParamKind.CONTENT, "@Composable () -> Unit", 0),
            ),
        )
        val src = RdmaPluginTransformState.buildWidgetBridge(listOf(fn))
        assertContains(src, "fun rdmaNavigationPanel(title: String, actionLabels: List<String>, actions: List<() -> Unit>, content: @Composable () -> Unit)")
        assertContains(src, "actions.map { js(\"RDMA\").registerBlock(it, \"\") }")
        assertContains(src, "js(\"RDMA\").composeNavigationPanel(title, actionLabels, actions.map { js(\"RDMA\").registerBlock(it, \"\") }, js(\"RDMA\").registerBlock(content, \"\"))")
    }

    @Test
    fun `modifier parameter defaults to an empty chain`() {
        val fn = RdmaPluginFunction(
            name = "Column",
            qualifiedName = "com.example.kernel.internal.Column",
            composable = true,
            parameters = listOf(
                RdmaPluginParameter(
                    "modifier",
                    RdmaParamKind.VALUE,
                    "Any",
                    defaultExpr = "js(\"RDMA.createModifier()\")",
                ),
                RdmaPluginParameter("content", RdmaParamKind.CONTENT, "@Composable () -> Unit", 0),
            ),
        )
        val src = RdmaPluginTransformState.buildWidgetBridge(listOf(fn))
        assertContains(src, "fun rdmaColumn(modifier: Any, content: @Composable () -> Unit)")
        assertContains(src, "fun rdmaColumn(content: @Composable () -> Unit)")
        assertContains(src, "rdmaColumn(js(\"RDMA.createModifier()\"), content)")
        assertContains(src, "js(\"RDMA\").composeColumn(modifier, js(\"RDMA\").registerBlock(content, \"\"))")
    }

    @Test
    fun `modifier default in the middle keeps the following parameter`() {
        val fn = RdmaPluginFunction(
            name = "Button",
            qualifiedName = "com.example.kernel.internal.Button",
            composable = true,
            parameters = listOf(
                RdmaPluginParameter("text", RdmaParamKind.VALUE, "String"),
                RdmaPluginParameter(
                    "modifier",
                    RdmaParamKind.VALUE,
                    "Any",
                    defaultExpr = "js(\"RDMA.createModifier()\")",
                ),
                RdmaPluginParameter("onClick", RdmaParamKind.CALLBACK, "() -> Unit", 0),
            ),
        )
        val src = RdmaPluginTransformState.buildWidgetBridge(listOf(fn))
        assertContains(src, "fun rdmaButton(text: String, modifier: Any, onClick: () -> Unit)")
        assertContains(src, "fun rdmaButton(text: String, onClick: () -> Unit)")
        assertContains(src, "rdmaButton(text, js(\"RDMA.createModifier()\"), onClick)")
    }
}
