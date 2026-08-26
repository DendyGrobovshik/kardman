package io.github.dendygrobovshik.kardman.kernel

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
import java.io.ByteArrayOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RdmaTypeValidatorTest {

    @Test
    fun `primitives and RDMA refs are valid`() {
        val type = RdmaTypeRef(
            RdmaType.FunctionType(
                listOf(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))),
                RdmaTypeRef(RdmaType.UnitType),
            )
        )
        assertTrue(RdmaTypeValidator.validate(type, setOf("com.example.kernel.Person")).isEmpty())
    }

    @Test
    fun `non-RDMA type is rejected`() {
        val type = RdmaTypeRef(RdmaType.Ref("com.example.kernel.NotRDMA"))
        val errors = RdmaTypeValidator.validate(type, setOf("com.example.kernel.Person"))
        assertEquals(1, errors.size)
        assertContains(errors[0], "NotRDMA")
    }

    @Test
    fun `non-RDMA type inside function parameter is rejected`() {
        val fn = RdmaFunctionInfo(
            name = "foo",
            qualifiedName = "com.example.kernel.foo",
            facadeClass = "com.example.kernel.Kt",
            composable = false,
            parameters = listOf(RdmaParameterInfo("x", RdmaTypeRef(RdmaType.Ref("com.example.Unknown")))),
            returnType = RdmaTypeRef(RdmaType.UnitType),
        )
        val errors = RdmaTypeValidator.validateFunction(fn, setOf("com.example.kernel.Person"))
        assertTrue(errors.isNotEmpty())
        assertContains(errors[0], "com.example.Unknown")
    }

    @Test
    fun `valid function produces no errors`() {
        val fn = RdmaFunctionInfo(
            name = "forEach",
            qualifiedName = "com.example.kernel.forEach",
            facadeClass = "com.example.kernel.WidgetsKt",
            composable = false,
            parameters = listOf(
                RdmaParameterInfo("items", RdmaTypeRef(RdmaType.ListType(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))))),
                RdmaParameterInfo("action", RdmaTypeRef(RdmaType.FunctionType(
                    listOf(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))),
                    RdmaTypeRef(RdmaType.UnitType),
                ))),
            ),
            returnType = RdmaTypeRef(RdmaType.UnitType),
        )
        assertTrue(RdmaTypeValidator.validateFunction(fn, setOf("com.example.kernel.Person")).isEmpty())
    }
}

class RdmaFunctionCodegenTest {

    private fun person() = RdmaClassInfo(
        packageName = "com.example.kernel", className = "Person",
        qualifiedName = "com.example.kernel.Person",
        constructors = listOf(ConstructorInfo(listOf(ParameterInfo("name", "kotlin.String")))),
        methods = emptyList(),
        properties = listOf(PropertyInfo("name", "kotlin.String", false))
    )

    private fun forEachFn() = RdmaFunctionInfo(
        name = "forEach",
        qualifiedName = "com.example.kernel.forEach",
        facadeClass = "com.example.kernel.WidgetsKt",
        composable = false,
        parameters = listOf(
            RdmaParameterInfo("items", RdmaTypeRef(RdmaType.ListType(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))))),
            RdmaParameterInfo("action", RdmaTypeRef(RdmaType.FunctionType(
                listOf(RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person"))),
                RdmaTypeRef(RdmaType.UnitType),
            ))),
        ),
        returnType = RdmaTypeRef(RdmaType.UnitType),
    )

    private fun generate(classInfos: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>): Map<String, String> {
        val files = mutableMapOf<String, ByteArrayOutputStream>()
        CppGenerator { fileName, _ ->
            ByteArrayOutputStream().also { files[fileName] = it }
        }.generate(classInfos, functions)
        return files.mapValues { it.value.use { it.toString("UTF-8") } }
    }

    @Test
    fun `generates JNI cache entries for functions`() {
        val generated = generate(listOf(person()), listOf(forEachFn()))
        val cacheH = generated["RdmaJniCache.h"] ?: error("missing cache header")
        assertContains(cacheH, "fn_forEach_clazz")
        assertContains(cacheH, "fn_forEach_method")

        val cacheCpp = generated["RdmaJniCache.cpp"] ?: error("missing cache cpp")
        assertContains(cacheCpp, "GetStaticMethodID")
        assertContains(cacheCpp, "com/example/kernel/WidgetsKt")
    }

    @Test
    fun `generates host function for a top-level function`() {
        val generated = generate(listOf(person()), listOf(forEachFn()))
        val bridge = generated["RdmaBridge.cpp"] ?: error("missing bridge")
        assertContains(bridge, "rdma_fn_forEach")
        assertContains(bridge, """PropNameID::forAscii(rt, "forEach")""")
        assertContains(bridge, "CallStaticVoidMethod")
    }

    @Test
    fun `lambda parameter wraps into RdmaFunction of matching arity`() {
        val generated = generate(listOf(person()), listOf(forEachFn()))
        val bridge = generated["RdmaBridge.cpp"] ?: error("missing bridge")
        assertContains(bridge, "io/github/dendygrobovshik/kardman/runtime/RdmaFunction1")
    }

    @Test
    fun `function returning RDMA ref wraps result`() {
        val fn = RdmaFunctionInfo(
            name = "first",
            qualifiedName = "com.example.kernel.first",
            facadeClass = "com.example.kernel.WidgetsKt",
            composable = false,
            parameters = emptyList(),
            returnType = RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person")),
        )
        val generated = generate(listOf(person()), listOf(fn))
        val bridge = generated["RdmaBridge.cpp"] ?: error("missing bridge")
        assertContains(bridge, "createPersonWrapper")
        assertContains(bridge, "NewGlobalRef")
    }

    @Test
    fun `composable functions are not codegen'd`() {
        val fn = RdmaFunctionInfo(
            name = "Text",
            qualifiedName = "com.example.kernel.Text",
            facadeClass = "com.example.kernel.WidgetsKt",
            composable = true,
            parameters = emptyList(),
            returnType = RdmaTypeRef(RdmaType.UnitType),
        )
        val generated = generate(listOf(person()), listOf(fn))
        val bridge = generated["RdmaBridge.cpp"] ?: error("missing bridge")
        assertTrue(!bridge.contains("rdma_fn_Text"))
    }
}

class RdmaManifestSerializationTest {

    @Test
    fun `manifest round-trips through kotlinx`() {
        val manifest = RdmaManifest(
            classes = listOf(RdmaClassInfo(
                packageName = "com.example.kernel", className = "Person",
                qualifiedName = "com.example.kernel.Person",
                constructors = listOf(ConstructorInfo(listOf(ParameterInfo("name", "kotlin.String")))),
                methods = emptyList(), properties = emptyList(),
            )),
            functions = listOf(
                RdmaFunctionInfo(
                    name = "Text",
                    qualifiedName = "com.example.kernel.Text",
                    facadeClass = "com.example.kernel.WidgetsKt",
                    composable = true,
                    parameters = emptyList(),
                    returnType = RdmaTypeRef(RdmaType.UnitType),
                ),
                RdmaFunctionInfo(
                    name = "forEach",
                    qualifiedName = "com.example.kernel.forEach",
                    facadeClass = "com.example.kernel.WidgetsKt",
                    composable = false,
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

        val json = Json.encodeToString(RdmaManifest.serializer(), manifest)
        val decoded = Json.decodeFromString(RdmaManifest.serializer(), json)

        assertEquals(manifest, decoded)
        assertContains(json, "Person")
        assertContains(json, "forEach")
        assertContains(json, "\"kind\":\"function\"")
    }

    @Test
    fun `rdma type tree round-trips`() {
        val type = RdmaTypeRef(
            RdmaType.FunctionType(
                listOf(RdmaTypeRef(RdmaType.ListType(RdmaTypeRef(RdmaType.Primitive("kotlin.String"))))),
                RdmaTypeRef(RdmaType.Ref("com.example.kernel.Person")),
            )
        )
        val json = Json.encodeToString(RdmaTypeRef.serializer(), type)
        val decoded = Json.decodeFromString(RdmaTypeRef.serializer(), json)
        assertEquals(type, decoded)
    }
}
