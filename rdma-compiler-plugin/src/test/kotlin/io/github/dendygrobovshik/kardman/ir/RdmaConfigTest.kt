package io.github.dendygrobovshik.kardman.ir

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaConfigTest {

    private val sampleJson = """
[
  {"name":"Person","qualifiedName":"com.example.kernel.Person","constructors":[{"parameters":[{"name":"name","type":"kotlin.String"},{"name":"age","type":"kotlin.Int"}]}],"properties":[{"name":"name","type":"kotlin.String","isMutable":false},{"name":"status","type":"kotlin.String","isMutable":true}]},
  {"name":"Person3","qualifiedName":"com.example.kernel.Person3","constructors":[{"parameters":[{"name":"name3","type":"kotlin.String"},{"name":"age3","type":"kotlin.Int"}]}],"properties":[{"name":"name3","type":"kotlin.String","isMutable":false}]}
]
""".trimIndent()

    @Test
    fun `parses qualified names`() {
        val types = RdmaConfig.parseClassesJson(sampleJson)
        assertEquals(2, types.size)
        assertEquals("com.example.kernel.Person", types[0].qualifiedName)
        assertEquals("com.example.kernel.Person3", types[1].qualifiedName)
    }

    @Test
    fun `parses constructor params with types`() {
        val types = RdmaConfig.parseClassesJson(sampleJson)
        val person = types[0]
        assertEquals(2, person.constructorParams.size)
        assertEquals("name" to "kotlin.String", person.constructorParams[0])
        assertEquals("age" to "kotlin.Int", person.constructorParams[1])
    }

    @Test
    fun `parses mutable properties`() {
        val types = RdmaConfig.parseClassesJson(sampleJson)
        val person = types[0]
        assertEquals(2, person.properties.size)
        assertEquals("name" to false, person.properties[0])
        assertEquals("status" to true, person.properties[1])
    }

    @Test
    fun `empty input yields empty list`() {
        assertTrue(RdmaConfig.parseClassesJson("").isEmpty())
        assertTrue(RdmaConfig.parseClassesJson("[]").isEmpty())
    }
}
