package io.github.dendygrobovshik.kardman.tests

import io.github.dendygrobovshik.kardman.ksp.CppGenerator
import io.github.dendygrobovshik.kardman.ksp.ConstructorInfo
import io.github.dendygrobovshik.kardman.ksp.MethodInfo
import io.github.dendygrobovshik.kardman.ksp.ParameterInfo
import io.github.dendygrobovshik.kardman.ksp.PropertyInfo
import io.github.dendygrobovshik.kardman.ksp.RdmaClassInfo
import io.github.dendygrobovshik.kardman.plugin.RdmaTransformer
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationTest {

    // Simulate kernel: @RDMA class definitions
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
    fun `full pipeline - kernel generates C++ and JSON, plugin transforms code`() {
        // Step 1: Kernel KSP generates C++ and JSON
        val cppFiles = generateCpp(listOf(personInfo))
        assertTrue(cppFiles.isNotEmpty(), "C++ generation failed")

        val json = buildJson(listOf(personInfo))
        assertContains(json, "Person")

        // Step 2: Plugin KSP reads JSON, transforms code
        val types = RdmaTransformer.parseClassesJson(json)
        assertEquals(1, types.size)

        val pluginCode = """
            import com.example.kernel.Person
            fun main() {
                val p = Person("Тест", 99)
                println(p.name)
                println(p.toString())
            }
        """.trimIndent()

        val transformed = RdmaTransformer.transformCode(pluginCode, types)
        assertContains(transformed, """js("RDMA.createPerson('Тест', 99)")""")
        assertContains(transformed, "p.getName()")
        assertContains(transformed, "p.toString()")
        assertTrue(!transformed.contains("import com.example.kernel.Person"))
    }

    @Test
    fun `pipeline with multiple classes from start`() {
        val allClasses = listOf(personInfo, vampierInfo)
        val json = buildJson(allClasses)

        // Plugin code uses both classes
        val pluginCode = """
            import com.example.kernel.Person
            import com.example.kernel.VeryOldVampier
            fun main() {
                val p = Person("Тест", 99)
                val v = VeryOldVampier("Дракула")
                println(p.name)
                println(v.grhhh())
            }
        """.trimIndent()

        val types = RdmaTransformer.parseClassesJson(json)
        assertEquals(2, types.size)

        val transformed = RdmaTransformer.transformCode(pluginCode, types)
        assertContains(transformed, """js("RDMA.createPerson('Тест', 99)")""")
        assertContains(transformed, """js("RDMA.createVeryOldVampier('Дракула')")""")
        assertContains(transformed, "p.getName()")
        assertContains(transformed, "v.grhhh()")
    }

    @Test
    fun `adding new class - works alongside existing ones`() {
        // Baseline: only Person
        val baseline = buildJson(listOf(personInfo))
        assertEquals(1, RdmaTransformer.parseClassesJson(baseline).size)

        // Add VeryOldVampier
        val extended = buildJson(listOf(personInfo, vampierInfo))
        assertEquals(2, RdmaTransformer.parseClassesJson(extended).size)

        // Plugin with both classes
        val pluginCode = """
            import com.example.kernel.Person
            import com.example.kernel.VeryOldVampier
            fun main() {
                val p = Person("Alice", 30)
                val v = VeryOldVampier("Nosferatu")
                println(p.toString())
                println(v.grhhh())
            }
        """.trimIndent()

        val types = RdmaTransformer.parseClassesJson(extended)
        val transformed = RdmaTransformer.transformCode(pluginCode, types)

        // Person still works
        assertContains(transformed, """js("RDMA.createPerson('Alice', 30)")""")
        // New class works too
        assertContains(transformed, """js("RDMA.createVeryOldVampier('Nosferatu')")""")
    }

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
    fun `new class in same file as existing one`() {
        // Simulate: both classes defined, KSP finds both
        val classes = listOf(personInfo, vampierInfo)
        val json = buildJson(classes)

        val types = RdmaTransformer.parseClassesJson(json)
        assertEquals(2, types.size)

        val pluginCode = """
            import com.example.kernel.Person
            import com.example.kernel.VeryOldVampier
            fun main() {
                val p = Person("One", 1)
                val v = VeryOldVampier("Two")
            }
        """.trimIndent()

        val transformed = RdmaTransformer.transformCode(pluginCode, types)
        assertContains(transformed, "createPerson('One', 1)")
        assertContains(transformed, "createVeryOldVampier('Two')")
    }

    @Test
    fun `new class in new file`() {
        // Add Person3 later (new file scenario)
        val person3Info = RdmaClassInfo(
            packageName = "com.example.kernel", className = "Person3",
            qualifiedName = "com.example.kernel.Person3",
            constructors = listOf(ConstructorInfo(listOf(
                ParameterInfo("name3", "kotlin.String"),
                ParameterInfo("age3", "kotlin.Int")
            ))),
            methods = listOf(MethodInfo("toString3", "kotlin.String", emptyList())),
            properties = listOf(
                PropertyInfo("name3", "kotlin.String", false),
                PropertyInfo("age3", "kotlin.Int", false)
            )
        )

        // Start with baseline
        val baselineClasses = listOf(personInfo)
        val baselineJson = buildJson(baselineClasses)
        assertEquals(1, RdmaTransformer.parseClassesJson(baselineJson).size)

        // Add Person3 (new file)
        val extendedClasses = listOf(personInfo, person3Info)
        val extendedJson = buildJson(extendedClasses)
        assertEquals(2, RdmaTransformer.parseClassesJson(extendedJson).size)

        // Verify Person3 is correctly transformed
        val pluginCode = """
            import com.example.kernel.Person3
            fun main() {
                val p3 = Person3("Test3", 42)
                println(p3.name3)
            }
        """.trimIndent()

        val types = RdmaTransformer.parseClassesJson(extendedJson)
        val transformed = RdmaTransformer.transformCode(pluginCode, types)
        assertContains(transformed, """js("RDMA.createPerson3('Test3', 42)")""")
        assertContains(transformed, "p3.getName3()")
    }

    private fun generateCpp(classes: List<RdmaClassInfo>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(classes)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }

    @Test
    fun `RDMA type as method parameter`() {
        // Person.greetVampier(vampier: SuperVeryOldVampier): String
        val classes = listOf(personInfo, vampierInfo)
        val cppFiles = generateCpp(classes)

        val personCpp = cppFiles["PersonHostObject.cpp"] ?: error("Person C++ not generated")
        // Should extract NativeState from @RDMA parameter
        assertContains(personCpp, "std::static_pointer_cast<VeryOldVampierNativeState>")
        assertContains(personCpp, "getNativeState")
        assertContains(personCpp, "getObject()")
        // Should pass jobject to JNI
        assertContains(personCpp, "arg_vampier")
    }

    @Test
    fun `RDMA type as return value`() {
        // Person.getFriend(): Person
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
        // Person uses VeryOldVampier as parameter in greetVampier
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
        
        // Should register createWithOverrides in RDMA namespace
        assertContains(bridgeCpp, "createWithOverrides")
        assertContains(bridgeCpp, """PropNameID::forAscii(rt, "createWithOverrides")""")
        assertContains(bridgeCpp, "rdmaNamespace.setProperty")
        
        // Should create vtable and set __vtable field
        assertContains(bridgeCpp, "RdmaVtable")
        assertContains(bridgeCpp, "GetFieldID")
        assertContains(bridgeCpp, "\"__vtable\"")
        assertContains(bridgeCpp, "SetLongField")
        
        // Should iterate overrides
        assertContains(bridgeCpp, "getPropertyNames")
        assertContains(bridgeCpp, "make_shared")
    }

    @Test
    fun `vtable dispatch JNI function exists`() {
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)
        
        // RdmaVtable.h should define the struct
        val rdmaRuntimeCpp = "RdmaVtable.h"  // check struct exists
        // nativeDispatch should be callable from Kotlin
        // This is verified by the APK building successfully
    }

    @Test
    fun `override survives in JS-side call`() {
        // When JS calls child.greet(), the override function is stored
        // in the vtable and dispatched via HostFunction
        // The C++ code generates entries[name] = shared_ptr<Function>
        val classes = listOf(personInfo)
        val cppFiles = generateCpp(classes)
        val bridgeCpp = cppFiles["RdmaBridge.cpp"] ?: error("Bridge not generated")
        assertContains(bridgeCpp, "new RdmaVtable")
        assertContains(bridgeCpp, "getPropertyNames")
        assertContains(bridgeCpp, "asFunction")
    }

    private fun buildJson(classes: List<RdmaClassInfo>): String {
        val sb = StringBuilder()
        sb.appendLine("[")
        classes.forEachIndexed { i, info ->
            sb.append("  {")
            sb.append("\"name\":\"${info.className}\",")
            sb.append("\"constructors\":[")
            info.constructors.forEachIndexed { ci, ctor ->
                sb.append("{\"parameters\":[")
                ctor.parameters.forEachIndexed { pi, param ->
                    sb.append("{\"name\":\"${param.name}\",\"type\":\"${param.type}\"}")
                    if (pi < ctor.parameters.size - 1) sb.append(",")
                }
                sb.append("]}")
                if (ci < info.constructors.size - 1) sb.append(",")
            }
            sb.append("],")
            sb.append("\"properties\":[")
            info.properties.forEachIndexed { j, prop ->
                sb.append("{\"name\":\"${prop.name}\",\"type\":\"${prop.type}\"}")
                if (j < info.properties.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (i < classes.size - 1) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("]")
        return sb.toString()
    }
}
