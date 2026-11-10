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

import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertFalse

class RdmaDefaultWrapperGeneratorTest {

    private fun generate(classInfos: List<RdmaClassInfo> = emptyList(), functions: List<RdmaFunctionInfo> = emptyList()): String {
        val out = ByteArrayOutputStream()
        RdmaDefaultWrapperGenerator("com.example.kernel") { _, _ -> out }.generate(classInfos, functions)
        return out.toString("UTF-8")
    }

    @Test
    fun `cross-parameter default resolves against the resolved value`() {
        val fn = RdmaFunctionInfo(
            name = "add",
            qualifiedName = "com.example.kernel.add",
            facadeClass = "com.example.kernel.DefaultKt",
            composable = false,
            parameters = listOf(
                RdmaParameterInfo("a", RdmaTypeRef(RdmaType.Primitive("kotlin.Int"))),
                RdmaParameterInfo("b", RdmaTypeRef(RdmaType.Primitive("kotlin.Int")), hasDefault = true, defaultValue = "a * 2"),
            ),
            returnType = RdmaTypeRef(RdmaType.Primitive("kotlin.Int")),
        )
        val src = generate(functions = listOf(fn))
        assertContains(src, "fun add_rdma_default(_p0: Int, _p1: Int, mask: Int): Int {")
        assertContains(src, "val a = _p0")
        assertContains(src, "val b = if (mask and 2 != 0) (a * 2) else _p1")
        assertContains(src, "return com.example.kernel.add(a, b)")
    }

    @Test
    fun `method default this is rewritten to self`() {
        val cls = RdmaClassInfo(
            packageName = "com.example.kernel",
            className = "Counter",
            qualifiedName = "com.example.kernel.Counter",
            constructors = emptyList(),
            methods = listOf(
                MethodInfo(
                    "bump", "kotlin.Int",
                    listOf(
                        ParameterInfo("value", "kotlin.Int"),
                        ParameterInfo("times", "kotlin.Int", hasDefault = true, defaultValue = "this.step"),
                    ),
                ),
            ),
            properties = emptyList(),
        )
        val src = generate(classInfos = listOf(cls))
        assertContains(src, "fun Counter_bump_rdma_default(self: com.example.kernel.Counter, _p0: Int, _p1: Int, mask: Int): Int {")
        assertContains(src, "val times = if (mask and 2 != 0) (self.step) else _p1")
        assertContains(src, "return self.bump(value, times)")
    }

    @Test
    fun `nullable return carries the question mark`() {
        val fn = RdmaFunctionInfo(
            name = "describe",
            qualifiedName = "com.example.kernel.describe",
            facadeClass = "com.example.kernel.DefaultKt",
            composable = false,
            parameters = listOf(
                RdmaParameterInfo("label", RdmaTypeRef(RdmaType.Primitive("kotlin.String")), hasDefault = true, defaultValue = "\"x\""),
            ),
            returnType = RdmaTypeRef(RdmaType.Primitive("kotlin.String"), nullable = true),
        )
        val src = generate(functions = listOf(fn))
        assertContains(src, "): String? {")
    }

    @Test
    fun `constructor default generates new wrapper`() {
        val cls = RdmaClassInfo(
            packageName = "com.example.kernel",
            className = "Counter",
            qualifiedName = "com.example.kernel.Counter",
            constructors = listOf(ConstructorInfo(listOf(ParameterInfo("step", "kotlin.Int", hasDefault = true, defaultValue = "1")))),
            methods = emptyList(),
            properties = emptyList(),
        )
        val src = generate(classInfos = listOf(cls))
        assertContains(src, "fun Counter_new_rdma_default(_p0: Int, mask: Int): com.example.kernel.Counter {")
        assertContains(src, "val step = if (mask and 1 != 0) (1) else _p0")
    }

    @Test
    fun `composable widgets are not wrapped as plain functions`() {
        val fn = RdmaFunctionInfo(
            name = "Text",
            qualifiedName = "com.example.kernel.Text",
            facadeClass = "com.example.kernel.WidgetsKt",
            composable = true,
            parameters = listOf(RdmaParameterInfo("text", RdmaTypeRef(RdmaType.Primitive("kotlin.String")), hasDefault = true, defaultValue = "\"hi\"")),
            returnType = RdmaTypeRef(RdmaType.UnitType),
        )
        val src = generate(functions = listOf(fn))
        assertFalse(src.contains("Text_rdma_default"), "composable widgets must not be wrapped as plain default wrappers")
    }
}
