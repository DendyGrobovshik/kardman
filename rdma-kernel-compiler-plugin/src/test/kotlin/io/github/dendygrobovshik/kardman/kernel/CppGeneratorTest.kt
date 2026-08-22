package io.github.dendygrobovshik.kardman.kernel

import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertTrue

class CppGeneratorTest {

    @Test
    fun `generates all expected files for Person class`() {
        val generated = generate(createPerson())

        assertTrue("PersonHostObject.h" in generated, "Missing PersonHostObject.h, got: ${generated.keys}")
        assertTrue("PersonHostObject.cpp" in generated, "Missing PersonHostObject.cpp")
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
        assertContains(bridgeCpp, "rdmaNamespace.setProperty")
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

        assertTrue("PersonHostObject.h" in generated, "Missing Person files")
        assertTrue("VampierHostObject.h" in generated, "Missing Vampier files")
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
        val cpp = generated["DeviceHostObject.cpp"] ?: error("Not generated")
        assertContains(cpp, "setStatus")
        assertContains(cpp, "PropNameID::forAscii(rt, \"setStatus\")")
        assertContains(cpp, "setProperty(rt, \"setStatus\"")
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
        val cpp = generated["PersonHostObject.cpp"] ?: error("Not generated")
        assertContains(cpp, "= nullptr")
        assertContains(cpp, "isNull()")
        assertContains(cpp, "return jsi::Value::null()")
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
