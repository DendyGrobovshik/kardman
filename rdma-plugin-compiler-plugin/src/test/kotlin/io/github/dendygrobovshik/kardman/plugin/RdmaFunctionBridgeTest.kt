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
                RdmaPluginParameter(lambdaArity = null),
                RdmaPluginParameter(lambdaArity = 1),
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
                RdmaPluginParameter(lambdaArity = null),
                RdmaPluginParameter(lambdaArity = null),
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
