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
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertTrue

class RdmaWidgetGeneratorTest {

    private fun textWidget() = RdmaFunctionInfo(
        name = "Text",
        qualifiedName = "com.example.kernel.Text",
        facadeClass = "com.example.kernel.WidgetsKt",
        composable = true,
        parameters = listOf(
            RdmaParameterInfo("text", RdmaTypeRef(RdmaType.Primitive("kotlin.String"))),
            RdmaParameterInfo("color", RdmaTypeRef(RdmaType.Ref("com.example.kernel.Color"), nullable = true)),
            RdmaParameterInfo("fontSize", RdmaTypeRef(RdmaType.Ref("com.example.kernel.Dp"))),
        ),
        returnType = RdmaTypeRef(RdmaType.UnitType),
    )

    private fun generate(widgets: List<RdmaFunctionInfo>): Pair<Map<String, String>, Map<String, String>> {
        val cpp = mutableMapOf<String, ByteArrayOutputStream>()
        val kt = mutableMapOf<String, ByteArrayOutputStream>()
        RdmaWidgetGenerator(
            { fileName, _ -> ByteArrayOutputStream().also { cpp[fileName] = it } },
            { fileName, _ -> ByteArrayOutputStream().also { kt[fileName] = it } },
        ).generate(widgets)
        return Pair(
            cpp.mapValues { it.value.use { it.toString("UTF-8") } },
            kt.mapValues { it.value.use { it.toString("UTF-8") } },
        )
    }

    @Test
    fun `kotlin entry types and imports RDMA ref params`() {
        val (_, kt) = generate(listOf(textWidget()))
        val entries = kt["RdmaWidgetEntries.kt"] ?: error("RdmaWidgetEntries.kt not generated")
        assertContains(entries, "import com.example.kernel.Color")
        assertContains(entries, "import com.example.kernel.Dp")
        assertContains(entries, "fun composeText(text: String, color: Color?, fontSize: Dp) {")
        assertContains(entries, "color = color")
    }

    @Test
    fun `cpp includes Proxy headers and marshals refs`() {
        val (cpp, _) = generate(listOf(textWidget()))
        val bridge = cpp["RdmaWidgetBridge.cpp"] ?: error("RdmaWidgetBridge.cpp not generated")
        assertContains(bridge, "#include \"ColorProxy.h\"")
        assertContains(bridge, "#include \"DpProxy.h\"")
        assertContains(bridge, "static_pointer_cast<ColorNativeState>")
        assertContains(bridge, "static_pointer_cast<DpNativeState>")
        assertContains(bridge, "hasNativeState(r)")
    }

    @Test
    fun `jni cache uses LDMA class signatures for refs`() {
        val (cpp, _) = generate(listOf(textWidget()))
        val bridge = cpp["RdmaWidgetBridge.cpp"] ?: error("not generated")
        assertContains(bridge, "Lcom/example/kernel/Color;")
        assertContains(bridge, "Lcom/example/kernel/Dp;")
        assertContains(bridge, "Ljava/lang/String;")
    }

    @Test
    fun `nullable ref param keeps null default`() {
        val (cpp, _) = generate(listOf(textWidget()))
        val bridge = cpp["RdmaWidgetBridge.cpp"] ?: error("not generated")
        assertTrue(bridge.contains("jobject cpp_p1 = nullptr;"))
    }
}
