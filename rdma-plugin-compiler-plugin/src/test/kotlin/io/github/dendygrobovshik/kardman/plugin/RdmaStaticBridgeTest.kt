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
