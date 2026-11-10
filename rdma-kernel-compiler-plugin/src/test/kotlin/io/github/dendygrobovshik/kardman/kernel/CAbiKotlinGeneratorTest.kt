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

class CAbiKotlinGeneratorTest {

    private fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo> = emptyList()): String {
        val out = ByteArrayOutputStream()
        CAbiKotlinGenerator("", "com.example.kernel") { _, _ -> out }.generate(classInfos, functions)
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

    @Test
    fun `suspend function generates async shim and registration`() {
        val fn = RdmaFunctionInfo(
            name = "computeSum",
            qualifiedName = "com.example.kernel.computeSum",
            facadeClass = "com.example.kernel.SuspendDemoKt",
            composable = false,
            parameters = listOf(
                RdmaParameterInfo("a", RdmaTypeRef(RdmaType.Primitive("kotlin.Int"))),
                RdmaParameterInfo("b", RdmaTypeRef(RdmaType.Primitive("kotlin.Int"))),
            ),
            returnType = RdmaTypeRef(RdmaType.Primitive("kotlin.Int")),
            suspend = true,
        )
        val kotlin = generate(emptyList(), listOf(fn))
        assertContains(kotlin, "fun rdma_computeSumAsync(completionId: Long, _p0: Int, _p1: Int)")
        assertContains(kotlin, "RdmaSuspend.completeResult(completionId, com.example.kernel.computeSum(a, b))")
        assertContains(kotlin, "RdmaSuspend.completeError(completionId, t)")
        assertContains(kotlin, "rdmaRegisterFunction(\"rdma_computeSumAsync\"")
        // A suspend function must not also be emitted as a synchronous shim.
        assertFalse(kotlin.contains("fun rdma_computeSum("))
    }

    @Test
    fun `suspend method generates async shim with receiver`() {
        val cls = RdmaClassInfo(
            packageName = "com.example.kernel", className = "SuspendService",
            qualifiedName = "com.example.kernel.SuspendService",
            constructors = emptyList(),
            methods = listOf(MethodInfo("fetch", "kotlin.String", listOf(ParameterInfo("id", "kotlin.Int")), isSuspend = true)),
            properties = emptyList(),
        )
        val kotlin = generate(listOf(cls))
        assertContains(kotlin, "fun rdma_SuspendService_fetchAsync(completionId: Long, receiver: COpaquePointer, _p0: Int)")
        assertContains(kotlin, "val self = receiver.obj<com.example.kernel.SuspendService>()")
        assertContains(kotlin, "self.fetch(id)")
        assertContains(kotlin, "rdmaRegisterFunction(\"rdma_SuspendService_fetchAsync\"")
        assertFalse(kotlin.contains("fun rdma_SuspendService_fetch("))
    }
}
