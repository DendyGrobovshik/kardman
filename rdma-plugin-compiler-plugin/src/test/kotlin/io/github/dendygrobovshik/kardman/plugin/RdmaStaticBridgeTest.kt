package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RdmaStaticBridgeTest {

    private fun alignment() = RdmaPluginType(
        simpleName = "Alignment",
        qualifiedName = "com.example.kernel.Alignment",
        constructorParams = listOf("ordinal" to "kotlin.Int"),
        properties = listOf("ordinal" to false),
        statics = listOf("Center", "TopStart"),
    )

    @Test
    fun `generates static bridge stubs`() {
        val statics = listOf(alignment() to "Center", alignment() to "TopStart")
        val src = RdmaPluginTransformState.buildStaticBridge(statics)
        assertContains(src, "fun rdmaAlignmentCenter(): dynamic = js(\"RDMA\").alignmentCenter()")
        assertContains(src, "fun rdmaAlignmentTopStart(): dynamic = js(\"RDMA\").alignmentTopStart()")
    }

    @Test
    fun `naming matches C++ JSI names`() {
        assertEquals("rdmaAlignmentCenter", RdmaPluginTransformState.staticBridgeNameFor("Alignment", "Center"))
        assertEquals("alignmentCenter", RdmaPluginTransformState.staticJsName("Alignment", "Center"))
        assertEquals("contentScaleCrop", RdmaPluginTransformState.staticJsName("ContentScale", "Crop"))
    }
}
