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
        assertEquals(listOf("name" to false, "age" to false), person.properties)
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
    fun `transforms setter for mutable property`() {
        // JSON with isMutable:true on status
        val json = """[{"name":"Person","constructors":[{"parameters":[{"name":"name","type":"kotlin.String","nullable":false}]}],"methods":[],"properties":[{"name":"name","type":"kotlin.String","isMutable":false,"nullable":false},{"name":"status","type":"kotlin.String","isMutable":true,"nullable":false}]}]"""
        val types = RdmaTransformer.parseClassesJson(json).filter { it.simpleName == "Person" }
        val code = """
            val p = Person("test", 42)
            p.status = "alive"
            println(p.status)
        """.trimIndent()

        val transformed = RdmaTransformer.transformCode(code, types)
        assertContains(transformed, """p.setStatus("alive")""")
        assertContains(transformed, "p.getStatus()")
    }

    @Test
    fun `mutable false does not generate setter`() {
        val json = """[{"name":"Person","constructors":[],"methods":[],"properties":[{"name":"name","type":"kotlin.String","isMutable":false,"nullable":false}]}]"""
        val types = RdmaTransformer.parseClassesJson(json).filter { it.simpleName == "Person" }
        val code = "p.name = \"test\""

        val transformed = RdmaTransformer.transformCode(code, types)
        assertContains(transformed, """p.getName() = "test"""") // only getter, no setter
        assertFalse(transformed.contains("setName"))
    }

    @Test
    fun `parses isMutable from JSON`() {
        val json = """[{"name":"Device","constructors":[],"methods":[],"properties":[{"name":"x","type":"kotlin.Int","isMutable":true,"nullable":false},{"name":"y","type":"kotlin.Int","isMutable":false,"nullable":false}]}]"""
        val types = RdmaTransformer.parseClassesJson(json)
        val props = types[0].properties
        assertEquals(2, props.size)
        assertEquals(true, props[0].second) // x isMutable
        assertEquals(false, props[1].second) // y is not mutable
    }
}
