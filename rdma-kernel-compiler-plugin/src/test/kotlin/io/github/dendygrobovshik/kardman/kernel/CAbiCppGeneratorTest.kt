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

class CAbiCppGeneratorTest {

    private fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CAbiCppGenerator("") { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(classInfos, functions)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }

    @Test
    fun `suspend function generates async host function and registration`() {
        val fn = RdmaFunctionInfo(
            name = "computeSum",
            qualifiedName = "com.example.kernel.computeSum",
            facadeClass = "com.example.kernel.SuspendDemoKt",
            composable = false,
            parameters = listOf(
                RdmaParameterInfo("a", RdmaTypeRef(RdmaType.Primitive("kotlin.Int"))),
            ),
            returnType = RdmaTypeRef(RdmaType.Primitive("kotlin.Int")),
            suspend = true,
        )
        val generated = generate(emptyList(), listOf(fn))
        val bridge = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")
        assertContains(bridge, "static jsi::Value rdma_suspend_computeSum(")
        assertContains(bridge, "rdmaRegisterSuspendResultType(completionId, \"kotlin.Int\")")
        assertContains(bridge, "rdma_computeSumAsync(completionId, cpp_a)")
        assertContains(bridge, """PropNameID::forAscii(rt, "_computeSumAsync")""")
    }

    @Test
    fun `suspend method generates async host function and skips sync wrapper`() {
        val service = RdmaClassInfo(
            packageName = "com.example.kernel", className = "SuspendService",
            qualifiedName = "com.example.kernel.SuspendService",
            constructors = emptyList(),
            methods = listOf(MethodInfo("fetch", "kotlin.String", listOf(ParameterInfo("id", "kotlin.Int")), isSuspend = true)),
            properties = emptyList(),
        )
        val generated = generate(listOf(service), emptyList())
        val bridge = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")
        assertContains(bridge, "static jsi::Value rdma_suspend_SuspendService_fetch(")
        assertContains(bridge, "rdmaRegisterSuspendResultType(completionId, \"kotlin.String\")")
        assertContains(bridge, """PropNameID::forAscii(rt, "_SuspendService_fetchAsync")""")

        // The suspend method must not be emitted as a synchronous C-ABI wrapper.
        val header = generated["RdmaCAbi.h"] ?: error("RdmaCAbi.h not generated")
        assertFalse(header.contains("rdma_SuspendService_fetch("), "suspend method should not get a sync C-ABI wrapper")
    }
}
