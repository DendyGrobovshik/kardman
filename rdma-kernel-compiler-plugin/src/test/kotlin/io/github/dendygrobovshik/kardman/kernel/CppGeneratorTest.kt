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
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.StaticInfo
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CppGeneratorTest {

    @Test
    fun `generates all expected files for Person class`() {
        val generated = generate(createPerson())

        assertTrue("PersonProxy.h" in generated, "Missing PersonProxy.h, got: ${generated.keys}")
        assertTrue("PersonProxy.cpp" in generated, "Missing PersonProxy.cpp")
        assertTrue("RdmaBridge.h" in generated, "Missing RdmaBridge.h")
        assertTrue("RdmaBridge.cpp" in generated, "Missing RdmaBridge.cpp")
        assertTrue("RdmaJniCache.h" in generated, "Missing RdmaJniCache.h")
        assertTrue("RdmaJniCache.cpp" in generated, "Missing RdmaJniCache.cpp")
    }

    @Test
    fun `JNI cache struct has correct fields`() {
        val generated = generate(createPerson())
        val cacheH = generated["RdmaJniCache.h"] ?: error("RdmaJniCache.h not generated")

        assertContains(cacheH, "PersonCache")
        assertContains(cacheH, "jmethodID constructor")
        assertContains(cacheH, "jmethodID method_toString")
        assertContains(cacheH, "jmethodID getter_name")
        assertContains(cacheH, "jmethodID getter_age")
        assertContains(cacheH, "person_cache")
    }

    @Test
    fun `factory function registered in bridge`() {
        val generated = generate(createPerson())
        val bridgeCpp = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")

        assertContains(bridgeCpp, "createPerson")
        assertContains(bridgeCpp, "rdma.setProperty")
        assertContains(bridgeCpp, """PropNameID::forAscii(rt, "createPerson")""")
    }

    @Test
    fun `generates for multiple classes`() {
        val classes = listOf(
            createPerson(),
            RdmaClassInfo(
                packageName = "com.example.kernel", className = "Vampier",
                qualifiedName = "com.example.kernel.Vampier",
                constructors = listOf(ConstructorInfo(listOf(ParameterInfo("name", "kotlin.String")))),
                methods = listOf(MethodInfo("grhh", "kotlin.String", emptyList())),
                properties = listOf(PropertyInfo("name", "kotlin.String", false))
            )
        )

        val generated = generate(*classes.toTypedArray())

        assertTrue("PersonProxy.h" in generated, "Missing Person files")
        assertTrue("VampierProxy.h" in generated, "Missing Vampier files")
        val bridgeCpp = generated["RdmaBridge.cpp"] ?: error("Bridge not generated")
        assertContains(bridgeCpp, "createPerson")
        assertContains(bridgeCpp, "createVampier")
    }

    @Test
    fun `generates setter for mutable property`() {
        val mutable = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Device",
            qualifiedName = "com.example.kernel.Device",
            constructors = emptyList(),
            methods = emptyList(),
            properties = listOf(PropertyInfo("status", "kotlin.String", isMutable = true))
        )

        val generated = generate(mutable)
        val cpp = generated["DeviceProxy.cpp"] ?: error("Not generated")
        assertContains(cpp, "setStatus")
        assertContains(cpp, "PropNameID::forAscii(rt, \"setStatus\")")
        assertContains(cpp, "setProperty(rt, \"setStatus\"")
    }

    @Test
    fun `generates companion static getter registered in bridge`() {
        val alignment = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Alignment",
            qualifiedName = "com.example.kernel.Alignment",
            constructors = listOf(ConstructorInfo(listOf(ParameterInfo("ordinal", "kotlin.Int")))),
            methods = emptyList(),
            properties = listOf(PropertyInfo("ordinal", "kotlin.Int", false)),
            statics = listOf(StaticInfo("Center", "com.example.kernel.Alignment")),
        )

        val generated = generate(alignment)
        val cacheH = generated["RdmaJniCache.h"] ?: error("RdmaJniCache.h not generated")
        assertContains(cacheH, "companionClazz")
        assertContains(cacheH, "companionField")
        assertContains(cacheH, "static_get_Center")

        val cacheCpp = generated["RdmaJniCache.cpp"] ?: error("RdmaJniCache.cpp not generated")
        assertContains(cacheCpp, "\$Companion")
        assertContains(cacheCpp, "GetStaticFieldID")
        assertContains(cacheCpp, "\"getCenter\"")

        val bridgeCpp = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")
        assertContains(bridgeCpp, "rdma_static_Alignment_Center")
        assertContains(bridgeCpp, "GetStaticObjectField")
        assertContains(bridgeCpp, "createAlignmentWrapper")
        assertContains(bridgeCpp, """PropNameID::forAscii(rt, "alignmentCenter")""")
    }

    @Test
    fun `generates null check for nullable param`() {
        val info = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Person",
            qualifiedName = "com.example.kernel.Person",
            constructors = listOf(ConstructorInfo(listOf(ParameterInfo("name", "kotlin.String")))),
            methods = listOf(MethodInfo("greetMaybe", "kotlin.String", listOf(
                ParameterInfo("vampier", "com.example.kernel.Vampier", nullable = true)
            ), nullableReturn = true)),
            properties = emptyList()
        )

        val classes = listOf(info, RdmaClassInfo(
            packageName = "com.example.kernel", className = "Vampier",
            qualifiedName = "com.example.kernel.Vampier",
            constructors = emptyList(), methods = emptyList(), properties = emptyList()
        ))

        val generated = generate(classes)
        val cpp = generated["PersonProxy.cpp"] ?: error("Not generated")
        assertContains(cpp, "= nullptr")
        assertContains(cpp, "isNull()")
        assertContains(cpp, "return jsi::Value::null()")
    }

    @Test
    fun `cross-module RDMA param marshals via base state and return via wrapAny`() {
        val info = RdmaClassInfo(
            packageName = "com.example.kernel.user.clive", className = "Widget",
            qualifiedName = "com.example.kernel.user.clive.Widget",
            constructors = listOf(ConstructorInfo(listOf(
                ParameterInfo("color", "com.example.kernel.internal.Color")
            ))),
            methods = listOf(MethodInfo("getColor", "com.example.kernel.internal.Color", emptyList())),
            properties = emptyList()
        )

        val generated = generate(listOf(info))
        val cpp = generated["WidgetProxy.cpp"] ?: error("Not generated")

        // Cross-module param is marshaled through the module-independent base state.
        assertContains(cpp, "static_pointer_cast<RdmaObjectNativeState>")
        assertFalse(cpp.contains("ColorNativeState"), "should not reference a concrete type from another module")

        // Cross-module return dispatches at runtime by class name.
        assertContains(cpp, "wrapAny(r, jvm, jret)")
        assertFalse(cpp.contains("createColorWrapper"), "should not reference a concrete wrapper from another module")
    }

    @Test
    fun `same-module RDMA return keeps fast concrete wrapper`() {
        val person = createPerson()
        val info = person.copy(
            methods = listOf(MethodInfo("fight", "com.example.kernel.Person", listOf(
                ParameterInfo("opponent", "com.example.kernel.Person")
            )))
        )

        val generated = generate(listOf(info))
        val cpp = generated["PersonProxy.cpp"] ?: error("Not generated")

        assertContains(cpp, "createPersonWrapper")
    }

    @Test
    fun `method calls capture host exceptions`() {
        val generated = generate(createPerson())
        val cpp = generated["PersonProxy.cpp"] ?: error("PersonProxy.cpp not generated")
        assertContains(cpp, "checkAndThrowJs(env, r)", message = "method JNI calls should capture host exceptions")
    }

    @Test
    fun `generates suspend async wrapper and completion registration`() {
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
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator(moduleId = "", kernelPackage = "com.example.kernel") { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(emptyList(), listOf(fn))
        val generated = files.mapValues { it.value.use { it.toString("UTF-8") } }

        val bridge = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")
        assertContains(bridge, "rdma_suspend_computeSum")
        assertContains(bridge, "rdmaRegisterSuspendResultType(completionId, \"kotlin.Int\")")
        assertContains(bridge, """PropNameID::forAscii(rt, "_computeSumAsync")""")

        val cache = generated["RdmaJniCache.cpp"] ?: error("RdmaJniCache.cpp not generated")
        assertContains(cache, "suspendClazz")
        assertContains(cache, "suspend_fn_computeSumAsync")
    }

    @Test
    fun `suspend method generates async wrapper with receiver`() {
        val service = RdmaClassInfo(
            packageName = "com.example.kernel", className = "SuspendService",
            qualifiedName = "com.example.kernel.SuspendService",
            constructors = emptyList(),
            methods = listOf(MethodInfo("fetch", "kotlin.String", listOf(ParameterInfo("id", "kotlin.Int")), isSuspend = true)),
            properties = emptyList(),
        )
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator(moduleId = "", kernelPackage = "com.example.kernel") { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(listOf(service), emptyList())
        val generated = files.mapValues { it.value.use { it.toString("UTF-8") } }

        val bridge = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")
        assertContains(bridge, "rdma_suspend_SuspendService_fetch")
        assertContains(bridge, """PropNameID::forAscii(rt, "_SuspendService_fetchAsync")""")

        // The suspend method must not be generated as a synchronous proxy method.
        val proxy = generated["SuspendServiceProxy.cpp"] ?: error("SuspendServiceProxy.cpp not generated")
        assertFalse(proxy.contains("SuspendService_fetch("), "suspend method should not get a sync proxy method")
    }

    @Test
    fun `suspend return type descriptors cover RDMA, map and list`() {
        val person = createPerson()
        val cases = listOf(
            RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person")) to "com.example.kernel.Person",
            RdmaTypeRef(RdmaType.MapType(
                RdmaTypeRef(RdmaType.Primitive("kotlin.String")),
                RdmaTypeRef(RdmaType.Primitive("kotlin.Int")),
            )) to "map:kotlin.String:kotlin.Int",
            RdmaTypeRef(RdmaType.ListType(RdmaTypeRef(RdmaType.Primitive("kotlin.Int")))) to "list:kotlin.Int",
        )
        val functions = cases.mapIndexed { i, (ret, _) ->
            RdmaFunctionInfo(
                name = "suspendCase$i",
                qualifiedName = "com.example.kernel.suspendCase$i",
                facadeClass = "com.example.kernel.SuspendDemoKt",
                composable = false,
                parameters = emptyList(),
                returnType = ret,
                suspend = true,
            )
        }
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator(moduleId = "", kernelPackage = "com.example.kernel") { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(listOf(person), functions)
        val generated = files.mapValues { it.value.use { it.toString("UTF-8") } }
        val bridge = generated["RdmaBridge.cpp"] ?: error("RdmaBridge.cpp not generated")

        cases.forEachIndexed { i, (_, desc) ->
            assertContains(bridge, "rdmaRegisterSuspendResultType(completionId, \"$desc\")")
            assertContains(bridge, "rdma_suspend_suspendCase$i")
        }
    }

    private fun createPerson() = RdmaClassInfo(
        packageName = "com.example.kernel", className = "Person",
        qualifiedName = "com.example.kernel.Person",
        constructors = listOf(ConstructorInfo(listOf(
            ParameterInfo("name", "kotlin.String"),
            ParameterInfo("age", "kotlin.Int")
        ))),
        methods = listOf(MethodInfo("toString", "kotlin.String", emptyList())),
        properties = listOf(
            PropertyInfo("name", "kotlin.String", false),
            PropertyInfo("age", "kotlin.Int", false)
        )
    )

    private fun generate(classes: List<RdmaClassInfo>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(classes)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }

    private fun generate(vararg classes: RdmaClassInfo) = generate(classes.toList())
}
