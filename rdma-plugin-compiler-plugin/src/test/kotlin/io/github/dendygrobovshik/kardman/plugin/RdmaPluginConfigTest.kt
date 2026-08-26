package io.github.dendygrobovshik.kardman.plugin

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaPluginConfigTest {

    private val sampleManifest = """
[
  {"kind":"class","name":"Person","qualifiedName":"com.example.kernel.Person","constructors":[{"parameters":[{"name":"name","type":"kotlin.String"},{"name":"age","type":"kotlin.Int"}]}],"properties":[{"name":"name","type":"kotlin.String","isMutable":false},{"name":"status","type":"kotlin.String","isMutable":true}]},
  {"kind":"class","name":"Person3","qualifiedName":"com.example.kernel.Person3","constructors":[{"parameters":[{"name":"name3","type":"kotlin.String"},{"name":"age3","type":"kotlin.Int"}]}],"properties":[{"name":"name3","type":"kotlin.String","isMutable":false}]},
  {"kind":"function","name":"Text","qualifiedName":"com.example.kernel.Text","composable":true,"parameters":[{"name":"text","type":{"kind":"primitive","fqn":"kotlin.String","nullable":false}}],"returnType":{"kind":"unit","nullable":false}},
  {"kind":"function","name":"forEach","qualifiedName":"com.example.kernel.forEach","composable":false,"parameters":[{"name":"items","type":{"kind":"list","nullable":false,"element":{"kind":"ref","fqn":"com.example.kernel.Person","nullable":false}}},{"name":"action","type":{"kind":"function","nullable":false,"parameters":[{"kind":"ref","fqn":"com.example.kernel.Person","nullable":false}],"return":{"kind":"unit","nullable":false}}}],"returnType":{"kind":"unit","nullable":false}}
]
""".trimIndent()

    @Test
    fun `parses classes from unified manifest`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleManifest)
        assertEquals(2, manifest.classes.size)
        assertEquals("com.example.kernel.Person", manifest.classes[0].qualifiedName)
        assertEquals("com.example.kernel.Person3", manifest.classes[1].qualifiedName)
    }

    @Test
    fun `parses constructor params with types`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleManifest)
        val person = manifest.classes[0]
        assertEquals(2, person.constructorParams.size)
        assertEquals("name" to "kotlin.String", person.constructorParams[0])
        assertEquals("age" to "kotlin.Int", person.constructorParams[1])
    }

    @Test
    fun `parses mutable properties`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleManifest)
        val person = manifest.classes[0]
        assertEquals("name" to false, person.properties[0])
        assertEquals("status" to true, person.properties[1])
    }

    @Test
    fun `parses functions with composable flag`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleManifest)
        assertEquals(2, manifest.functions.size)
        val text = manifest.functions.first { it.name == "Text" }
        assertTrue(text.composable)
        val forEach = manifest.functions.first { it.name == "forEach" }
        assertTrue(!forEach.composable)
    }

    @Test
    fun `parses lambda parameters of a function`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleManifest)
        val forEach = manifest.functions.first { it.name == "forEach" }
        assertEquals(2, forEach.parameters.size)
        assertEquals(null, forEach.parameters[0].lambdaArity)
        assertEquals(1, forEach.parameters[1].lambdaArity)
    }

    @Test
    fun `empty input yields empty manifest`() {
        val empty = RdmaPluginConfig.parseManifest("")
        assertTrue(empty.classes.isEmpty())
        assertTrue(empty.functions.isEmpty())
        val emptyArr = RdmaPluginConfig.parseManifest("[]")
        assertTrue(emptyArr.classes.isEmpty())
        assertTrue(emptyArr.functions.isEmpty())
    }
}
