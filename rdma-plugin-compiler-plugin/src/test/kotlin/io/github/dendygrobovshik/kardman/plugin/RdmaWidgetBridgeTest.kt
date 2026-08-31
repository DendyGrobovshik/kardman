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
