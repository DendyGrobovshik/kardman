package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse

class RdmaTransformerTest {

    private val sampleJson = """
[
  {"name":"Person","constructors":[{"parameters":[{"name":"name","type":"kotlin.String"},{"name":"age","type":"kotlin.Int"}]}],"properties":[{"name":"name","type":"kotlin.String"},{"name":"age","type":"kotlin.Int"}]},
  {"name":"VeryOldVampier","constructors":[{"parameters":[{"name":"name","type":"kotlin.String"}]}],"properties":[{"name":"name","type":"kotlin.String"}]},
  {"name":"Person3","constructors":[{"parameters":[{"name":"name3","type":"kotlin.String"},{"name":"age3","type":"kotlin.Int"}]}],"properties":[{"name":"name3","type":"kotlin.String"},{"name":"age3","type":"kotlin.Int"}]}
]
""".trimIndent()

    @Test
    fun `parses JSON with correct class count`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson)
        assertEquals(3, types.size, "Should find 3 @RDMA types")
    }

    @Test
    fun `parses Person with correct constructor params`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson)
        val person = types.find { it.simpleName == "Person" }
            ?: error("Person not found")
        assertEquals(2, person.constructorParams.size)
        assertEquals("String", person.constructorParams[0].second)
        assertEquals("Int", person.constructorParams[1].second)
    }

    @Test
    fun `parses Person with correct properties`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson)
        val person = types.find { it.simpleName == "Person" }!!
        assertEquals(listOf("name", "age"), person.properties)
    }

    @Test
    fun `parses VeryOldVampier with one constructor param`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson)
        val vamp = types.find { it.simpleName == "VeryOldVampier" }!!
        assertEquals(1, vamp.constructorParams.size)
        assertEquals("String", vamp.constructorParams[0].second)
    }

    @Test
    fun `transforms Person constructor call`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson).filter { it.simpleName == "Person" }
        val code = "import com.example.kernel.Person\nval p = Person(\"Эдвард\", 104)"

        val transformed = RdmaTransformer.transformCode(code, types)
        assertFalse(transformed.contains("import com.example.kernel.Person"))
        assertContains(transformed, """js("RDMA.createPerson('Эдвард', 104)")""")
    }

    @Test
    fun `transforms property access to getter`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson).filter { it.simpleName == "Person" }
        val code = "println(p.name)"

        val transformed = RdmaTransformer.transformCode(code, types)
        assertContains(transformed, "p.getName()")
    }

    @Test
    fun `transforms VeryOldVampier constructor and call`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson).filter { it.simpleName == "VeryOldVampier" }
        val code = """
            import com.example.kernel.VeryOldVampier
            val v = VeryOldVampier("Петир")
            println(v.grhhh())
        """.trimIndent()

        val transformed = RdmaTransformer.transformCode(code, types)
        assertContains(transformed, """js("RDMA.createVeryOldVampier('Петир')")""")
        assertContains(transformed, "v.grhhh()")
    }

    @Test
    fun `transforms Person3 with name3 and age3`() {
        val types = RdmaTransformer.parseClassesJson(sampleJson).filter { it.simpleName == "Person3" }
        val code = """
            import com.example.kernel.Person3
            val p = Person3("Белла", 17)
            println(p.name3)
        """.trimIndent()

        val transformed = RdmaTransformer.transformCode(code, types)
        assertContains(transformed, """js("RDMA.createPerson3('Белла', 17)")""")
        assertContains(transformed, "p.getName3()")
    }

    @Test
    fun `adding new class to JSON includes it in transform`() {
        val before = RdmaTransformer.parseClassesJson(sampleJson).size
        assertEquals(3, before)

        val extendedJson = sampleJson.trimEnd().dropLast(1) + """
,
  {"name":"NewType","constructors":[{"parameters":[{"name":"value","type":"kotlin.Int"}]}],"properties":[{"name":"value","type":"kotlin.Int"}]}
]
""".trimIndent()

        val after = RdmaTransformer.parseClassesJson(extendedJson)
        assertEquals(4, after.size)
        val newType = after.find { it.simpleName == "NewType" }!!
        assertEquals(1, newType.constructorParams.size)
        assertEquals("Int", newType.constructorParams[0].second)

        val code = "val n = NewType(42)"
        val transformed = RdmaTransformer.transformCode(code, after.filter { it.simpleName == "NewType" })
        assertContains(transformed, """js("RDMA.createNewType(42)")""")
    }
}
