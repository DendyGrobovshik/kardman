package io.github.dendygrobovshik.kardman.plugin

import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaManifest
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaPluginConfigTest {

    private fun sampleJson(): String {
        val manifest = RdmaManifest(
            classes = listOf(
                RdmaClassInfo(
                    packageName = "com.example.kernel", className = "Person",
                    qualifiedName = "com.example.kernel.Person",
                    constructors = listOf(ConstructorInfo(listOf(
                        ParameterInfo("name", "kotlin.String"),
                        ParameterInfo("age", "kotlin.Int"),
                    ))),
                    methods = emptyList(),
                    properties = listOf(
                        PropertyInfo("name", "kotlin.String", false),
                        PropertyInfo("status", "kotlin.String", true),
                    ),
                ),
                RdmaClassInfo(
                    packageName = "com.example.kernel", className = "Person3",
                    qualifiedName = "com.example.kernel.Person3",
                    constructors = listOf(ConstructorInfo(listOf(
                        ParameterInfo("name3", "kotlin.String"),
                        ParameterInfo("age3", "kotlin.Int"),
                    ))),
                    methods = emptyList(),
                    properties = listOf(PropertyInfo("name3", "kotlin.String", false)),
                ),
            ),
            functions = listOf(
                RdmaFunctionInfo(
                    name = "Text", qualifiedName = "com.example.kernel.Text",
                    facadeClass = "com.example.kernel.WidgetsKt", composable = true,
                    parameters = emptyList(), returnType = RdmaTypeRef(RdmaType.UnitType),
                ),
                RdmaFunctionInfo(
                    name = "forEach", qualifiedName = "com.example.kernel.forEach",
                    facadeClass = "com.example.kernel.WidgetsKt", composable = false,
                    parameters = listOf(
                        RdmaParameterInfo("items", RdmaTypeRef(RdmaType.ListType(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))))),
                        RdmaParameterInfo("action", RdmaTypeRef(RdmaType.FunctionType(
                            listOf(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))),
                            RdmaTypeRef(RdmaType.UnitType),
                        ))),
                    ),
                    returnType = RdmaTypeRef(RdmaType.UnitType),
                ),
            ),
        )
        return Json.encodeToString(RdmaManifest.serializer(), manifest)
    }

    @Test
    fun `parses classes from unified manifest`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleJson())
        assertEquals(2, manifest.classes.size)
        assertEquals("com.example.kernel.Person", manifest.classes[0].qualifiedName)
        assertEquals("com.example.kernel.Person3", manifest.classes[1].qualifiedName)
    }

    @Test
    fun `parses constructor params with types`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleJson())
        val person = manifest.classes[0]
        assertEquals(2, person.constructorParams.size)
        assertEquals("name" to "kotlin.String", person.constructorParams[0])
        assertEquals("age" to "kotlin.Int", person.constructorParams[1])
    }

    @Test
    fun `parses mutable properties`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleJson())
        val person = manifest.classes[0]
        assertEquals("name" to false, person.properties[0])
        assertEquals("status" to true, person.properties[1])
    }

    @Test
    fun `parses functions with composable flag`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleJson())
        assertEquals(2, manifest.functions.size)
        val text = manifest.functions.first { it.name == "Text" }
        assertTrue(text.composable)
        val forEach = manifest.functions.first { it.name == "forEach" }
        assertTrue(!forEach.composable)
    }

    @Test
    fun `parses lambda parameters of a function`() {
        val manifest = RdmaPluginConfig.parseManifest(sampleJson())
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
    }

    @Test
    fun `parses companion statics from manifest`() {
        val manifest = RdmaManifest(
            classes = listOf(
                RdmaClassInfo(
                    packageName = "com.example.kernel", className = "Alignment",
                    qualifiedName = "com.example.kernel.Alignment",
                    constructors = listOf(ConstructorInfo(listOf(ParameterInfo("ordinal", "kotlin.Int")))),
                    methods = emptyList(),
                    properties = listOf(PropertyInfo("ordinal", "kotlin.Int", false)),
                    statics = listOf(
                        io.github.dendygrobovshik.kardman.types.StaticInfo("Center", "com.example.kernel.Alignment"),
                        io.github.dendygrobovshik.kardman.types.StaticInfo("TopStart", "com.example.kernel.Alignment"),
                    ),
                ),
            ),
        )
        val parsed = RdmaPluginConfig.parseManifest(Json.encodeToString(RdmaManifest.serializer(), manifest))
        val alignment = parsed.classes.first()
        assertEquals(listOf("Center", "TopStart"), alignment.statics)
    }
}
