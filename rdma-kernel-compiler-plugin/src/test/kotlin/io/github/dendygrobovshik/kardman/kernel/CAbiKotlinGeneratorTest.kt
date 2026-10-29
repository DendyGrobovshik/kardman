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
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains

class CAbiKotlinGeneratorTest {

    private fun generate(classInfos: List<RdmaClassInfo>): String {
        val out = ByteArrayOutputStream()
        CAbiKotlinGenerator("", "com.example.kernel") { _, _ -> out }.generate(classInfos, emptyList())
        return out.toString("UTF-8")
    }

    @Test
    fun `nested list constructor param renders recursive Kotlin type`() {
        val cls = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Box",
            qualifiedName = "com.example.kernel.Box",
            constructors = listOf(ConstructorInfo(listOf(
                ParameterInfo("items", "kotlin.collections.List", isList = true, listElementType = "list:kotlin.Int"),
            ))),
            methods = emptyList(),
            properties = emptyList(),
        )
        val kotlin = generate(listOf(cls))
        assertContains(kotlin, "List<List<kotlin.Int>>")
    }

    @Test
    fun `flat list constructor param renders single level type`() {
        val cls = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Box",
            qualifiedName = "com.example.kernel.Box",
            constructors = listOf(ConstructorInfo(listOf(
                ParameterInfo("items", "kotlin.collections.List", isList = true, listElementType = "kotlin.String"),
            ))),
            methods = emptyList(),
            properties = emptyList(),
        )
        val kotlin = generate(listOf(cls))
        assertContains(kotlin, "List<kotlin.String>")
    }
}
