package io.github.dendygrobovshik.kardman.tests

import io.github.dendygrobovshik.kardman.kernel.CppGenerator
import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertTrue

class IntegrationTest {

    private val personInfo = RdmaClassInfo(
        packageName = "com.example.kernel", className = "Person",
        qualifiedName = "com.example.kernel.Person",
        constructors = listOf(ConstructorInfo(listOf(
            ParameterInfo("name", "kotlin.String"),
            ParameterInfo("age", "kotlin.Int")
        ))),
        methods = listOf(
            MethodInfo("greet", "kotlin.String", emptyList(), isOpen = true, vtableId = 0),
            MethodInfo("toString", "kotlin.String", emptyList()),
            MethodInfo("greetVampier", "kotlin.String", listOf(
                ParameterInfo("vampier", "com.example.kernel.VeryOldVampier")
            )),
            MethodInfo("getFriend", "com.example.kernel.Person", emptyList()),
        ),
        properties = listOf(
            PropertyInfo("name", "kotlin.String", false),
            PropertyInfo("age", "kotlin.Int", false)
        )
    )

    private val vampierInfo = RdmaClassInfo(
        packageName = "com.example.kernel", className = "VeryOldVampier",
        qualifiedName = "com.example.kernel.VeryOldVampier",
        constructors = listOf(ConstructorInfo(listOf(
            ParameterInfo("name", "kotlin.String")
        ))),
        methods = listOf(MethodInfo("grhhh", "kotlin.String", emptyList())),
        properties = listOf(PropertyInfo("name", "kotlin.String", false))
    )

    @Test
    fun `C++ files include all classes`() {
        val classes = listOf(personInfo, vampierInfo)
        val cppFiles = generateCpp(classes)

        assertTrue("PersonHostObject.cpp" in cppFiles)
        assertTrue("VeryOldVampierHostObject.cpp" in cppFiles)
        assertTrue("RdmaBridge.cpp" in cppFiles)

        val bridge = cppFiles["RdmaBridge.cpp"]!!
        assertContains(bridge, "createPerson")
        assertContains(bridge, "createVeryOldVampier")
    }

    @Test
    fun `RDMA type as method parameter`() {
        val classes = listOf(personInfo, vampierInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        assertContains(personCpp, "std::static_pointer_cast<VeryOldVampierNativeState>")
        assertContains(personCpp, "getNativeState")
        assertContains(personCpp, "getObject()")
        assertContains(personCpp, "arg_vampier")
    }

    @Test
    fun `RDMA type as return value`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        assertContains(personCpp, "createPersonWrapper")
        assertContains(personCpp, "NewGlobalRef")
        assertContains(personCpp, "setNativeState")
    }

    @Test
    fun `wrapper function includes all prototype methods`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        assertContains(personCpp, "jsi::Object createPersonWrapper")
        assertContains(personCpp, "setProperty(rt, \"toString\"")
        assertContains(personCpp, "setProperty(rt, \"getName\"")
        assertContains(personCpp, "setProperty(rt, \"getAge\"")
    }

    @Test
    fun `includes other RDMA class headers when used as parameter`() {
        val classes = listOf(personInfo, vampierInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        assertContains(personCpp, "#include \"VeryOldVampierHostObject.h\"")
    }

    @Test
    fun `forward declares wrapper before usage`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        val wrapperDeclIdx = personCpp.indexOf("static jsi::Object createPersonWrapper")
        val wrapperDefIdx = personCpp.indexOf("jsi::Object createPersonWrapper")
        assertTrue(wrapperDeclIdx < wrapperDefIdx, "Forward declaration must appear before definition")
    }

    @Test
    fun `createWithOverrides generates vtable setup code`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)
        val bridgeCpp = cppFiles["RdmaBridge.cpp"] ?: error("Bridge not generated")

        assertContains(bridgeCpp, "createWithOverrides")
        assertContains(bridgeCpp, """PropNameID::forAscii(rt, "createWithOverrides")""")
        assertContains(bridgeCpp, "rdmaNamespace.setProperty")
        assertContains(bridgeCpp, "RdmaVtable")
        assertContains(bridgeCpp, "GetFieldID")
        assertContains(bridgeCpp, "\"__vtable\"")
        assertContains(bridgeCpp, "SetLongField")
        assertContains(bridgeCpp, "getPropertyNames")
        assertContains(bridgeCpp, "make_shared")
    }

    @Test
    fun `override survives in JS-side call`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)
        val bridgeCpp = cppFiles["RdmaBridge.cpp"] ?: error("Bridge not generated")
        assertContains(bridgeCpp, "new RdmaVtable")
        assertContains(bridgeCpp, "getPropertyNames")
        assertContains(bridgeCpp, "asFunction")
    }

    private fun generateCpp(classes: List<RdmaClassInfo>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(classes)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }
}
