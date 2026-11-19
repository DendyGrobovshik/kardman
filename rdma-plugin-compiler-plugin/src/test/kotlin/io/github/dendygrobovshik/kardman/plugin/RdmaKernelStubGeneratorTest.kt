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
package io.github.dendygrobovshik.kardman.plugin

import io.github.dendygrobovshik.kardman.types.ConstructorInfo
import io.github.dendygrobovshik.kardman.types.MethodInfo
import io.github.dendygrobovshik.kardman.types.ParameterInfo
import io.github.dendygrobovshik.kardman.types.PropertyInfo
import io.github.dendygrobovshik.kardman.types.RdmaClassInfo
import io.github.dendygrobovshik.kardman.types.RdmaEnumInfo
import io.github.dendygrobovshik.kardman.types.RdmaFunctionInfo
import io.github.dendygrobovshik.kardman.types.RdmaManifest
import io.github.dendygrobovshik.kardman.types.RdmaParameterInfo
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef
import io.github.dendygrobovshik.kardman.types.StaticInfo
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The stubs are the plugin's typed view of the kernel; their bodies must be exactly
 * call form v1 (docs/design/runtime_rework.md §5.1) — that is what keeps a new plugin
 * running on an old host.
 */
class RdmaKernelStubGeneratorTest {

    private val string = RdmaTypeRef(RdmaType.Primitive("kotlin.String"))
    private val int = RdmaTypeRef(RdmaType.Primitive("kotlin.Int"))
    private val unit = RdmaTypeRef(RdmaType.UnitType)
    private fun fn(vararg params: RdmaTypeRef) = RdmaTypeRef(RdmaType.FunctionType(params.toList(), unit))
    private fun list(e: RdmaTypeRef) = RdmaTypeRef(RdmaType.ListType(e))
    private fun ref(fqn: String, nullable: Boolean = false) = RdmaTypeRef(RdmaType.Ref(fqn), nullable)

    private fun generate(manifest: RdmaManifest, registerComponent: String = "com.k.registerComponent"): Map<String, String> =
        RdmaKernelStubGenerator(listOf(manifest), "alice:counter", "com.p", registerComponent).generate()

    private fun single(manifest: RdmaManifest): String = generate(manifest).getValue("RdmaKernelStubs_com_k.kt")

    @Test
    fun `one file per kernel package with the RDMA external object`() {
        val files = generate(
            RdmaManifest(
                functions = listOf(
                    RdmaFunctionInfo("a", "com.k.a", "", false, emptyList(), unit),
                    RdmaFunctionInfo("b", "com.k.sub.b", "", false, emptyList(), unit),
                ),
            ),
        )
        assertEquals(setOf("RdmaKernelStubs_com_k.kt", "RdmaKernelStubs_com_k_sub.kt"), files.keys)
        val src = files.getValue("RdmaKernelStubs_com_k_sub.kt")
        assertContains(src, "package com.k.sub")
        assertContains(src, "@JsName(\"RDMA\")\ninternal external object RdmaAbi {")
        assertContains(src, "    fun b(): dynamic")
    }

    @Test
    fun `plain function registers lambdas and installs the collection classifiers`() {
        val src = single(
            RdmaManifest(
                functions = listOf(
                    RdmaFunctionInfo(
                        "httpGet", "com.k.httpGet", "", false,
                        listOf(
                            RdmaParameterInfo("url", string),
                            RdmaParameterInfo("onOk", fn(ref("com.k.Response"))),
                            RdmaParameterInfo("retries", int, hasDefault = true),
                        ),
                        list(int),
                    ),
                ),
            ),
        )
        assertContains(
            src,
            "inline fun httpGet(url: kotlin.String, noinline onOk: ((com.k.Response) -> kotlin.Unit), " +
                "retries: kotlin.Int = js(\"undefined\")): kotlin.collections.List<kotlin.Int> {",
        )
        assertContains(src, "    com.p.rdmaListKindInstalled\n")
        assertContains(
            src,
            "return RdmaAbi.httpGet(url, RdmaAbi.registerBlock(onOk, \"alice:counter\"), retries)" +
                ".unsafeCast<kotlin.collections.List<kotlin.Int>>()",
        )
    }

    @Test
    fun `widget is a non-inline composable forwarding to compose name`() {
        val src = single(
            RdmaManifest(
                functions = listOf(
                    RdmaFunctionInfo(
                        "Panel", "com.k.Panel", "", true,
                        listOf(
                            RdmaParameterInfo("title", string),
                            RdmaParameterInfo("actions", list(fn()), hasDefault = true),
                            RdmaParameterInfo("content", fn(), composable = true),
                        ),
                        unit,
                    ),
                ),
            ),
        )
        assertContains(src, "@androidx.compose.runtime.Composable\nfun Panel(title: kotlin.String, ")
        assertContains(src, "content: @androidx.compose.runtime.Composable () -> kotlin.Unit)")
        assertFalse(src.contains("inline fun Panel"))
        assertContains(
            src,
            "RdmaAbi.composePanel(title, (if (actions === js(\"undefined\")) js(\"undefined\") else " +
                "actions.map { RdmaAbi.registerBlock(it, \"alice:counter\") }), " +
                "RdmaAbi.registerBlock(content, \"alice:counter\"))",
        )
    }

    @Test
    fun `suspend function resumes through registerContinuation`() {
        val src = single(
            RdmaManifest(
                functions = listOf(
                    RdmaFunctionInfo("computeSum", "com.k.computeSum", "", false, listOf(RdmaParameterInfo("a", int)), int, suspend = true),
                ),
            ),
        )
        assertContains(src, "suspend inline fun computeSum(a: kotlin.Int): kotlin.Int = suspendCancellableCoroutine { rdmaCont ->")
        assertContains(src, "{ rdmaValue: dynamic -> rdmaCont.resume(rdmaValue.unsafeCast<kotlin.Int>()) },")
        assertContains(src, "RdmaAbi._computeSumAsync(rdmaId, a)")
    }

    @Test
    fun `class has private constructor, accessor members and a factory`() {
        val src = single(
            RdmaManifest(
                classes = listOf(
                    RdmaClassInfo(
                        packageName = "com.k",
                        className = "Person",
                        qualifiedName = "com.k.Person",
                        constructors = listOf(ConstructorInfo(listOf(ParameterInfo("name", "kotlin.String"), ParameterInfo("age", "kotlin.Int", hasDefault = true)))),
                        methods = listOf(
                            MethodInfo("greet", "kotlin.String", emptyList()),
                            MethodInfo("fight", "kotlin.Unit", listOf(ParameterInfo("other", "com.k.Person"))),
                            MethodInfo("fetch", "kotlin.Int", listOf(ParameterInfo("id", "kotlin.Int")), isSuspend = true),
                            MethodInfo("friends", "kotlin.collections.List", emptyList(), isList = true, listElementType = "com.k.Person"),
                            MethodInfo("toString", "kotlin.String", emptyList()),
                        ),
                        properties = listOf(PropertyInfo("status", "kotlin.String", isMutable = true, nullable = true)),
                        statics = listOf(StaticInfo("Default", "com.k.Person")),
                    ),
                ),
            ),
        )
        assertContains(src, "class Person private constructor(rdmaNoInstances: kotlin.Nothing) {")
        assertContains(src, "    var status: kotlin.String?\n        inline get() = asDynamic().getStatus().unsafeCast<kotlin.String?>()")
        assertContains(src, "asDynamic().setStatus(value)")
        assertContains(src, "inline fun greet(): kotlin.String = asDynamic().greet().unsafeCast<kotlin.String>()")
        assertContains(src, "inline fun fight(other: com.k.Person) {\n        asDynamic().fight(other)")
        assertContains(src, "inline fun friends(): kotlin.collections.List<com.k.Person> = asDynamic().friends()")
        assertContains(src, "RdmaAbi._Person_fetchAsync(rdmaId, this, id)")
        assertFalse(src.contains("fun toString"))
        assertContains(src, "        val Default: com.k.Person\n            inline get() = RdmaAbi.personDefault().unsafeCast<com.k.Person>()")
        assertContains(
            src,
            "inline fun Person(name: kotlin.String, age: kotlin.Int = js(\"undefined\")): Person =\n" +
                "    RdmaAbi.createPerson(name, age).unsafeCast<Person>()",
        )
    }

    @Test
    fun `a subclass stub extends its superclass stub and does not repeat inherited members`() {
        val area = MethodInfo("area", "kotlin.Double", emptyList(), isOpen = true)
        val name = PropertyInfo("name", "kotlin.String", isMutable = false)
        val src = single(
            RdmaManifest(
                classes = listOf(
                    RdmaClassInfo("com.k", "Shape", "com.k.Shape", emptyList(), listOf(area), listOf(name), isOpen = true),
                    RdmaClassInfo(
                        "com.k", "Square", "com.k.Square",
                        listOf(ConstructorInfo(listOf(ParameterInfo("side", "kotlin.Double")))),
                        listOf(area.copy(isOpen = false)),
                        listOf(name, PropertyInfo("side", "kotlin.Double", isMutable = false)),
                        superClass = "com.k.Shape",
                    ),
                ),
            ),
        )
        assertContains(src, "open class Shape protected constructor(rdmaNoInstances: kotlin.Nothing) {")
        assertContains(src, "class Square private constructor(rdmaNoInstances: kotlin.Nothing) : com.k.Shape(rdmaNoInstances) {")
        // `area` and `name` are declared once, on Shape.
        assertEquals(1, Regex("inline fun area\\(").findAll(src).count())
        assertEquals(1, Regex("val name: ").findAll(src).count())
        assertContains(src, "val side: kotlin.Double")
    }

    @Test
    fun `a lambda parameter of a method or constructor crosses as a block id`() {
        val onTick = ParameterInfo("onTick", "kotlin.Function1", functionType = fn(int))
        val src = single(
            RdmaManifest(
                classes = listOf(
                    RdmaClassInfo(
                        "com.k", "Ticker", "com.k.Ticker",
                        listOf(ConstructorInfo(listOf(onTick))),
                        listOf(MethodInfo("each", "kotlin.Unit", listOf(ParameterInfo("action", "kotlin.Function1", functionType = fn(string))))),
                        emptyList(),
                    ),
                ),
            ),
        )
        assertContains(src, "inline fun Ticker(noinline onTick: ((kotlin.Int) -> kotlin.Unit)): Ticker =")
        assertContains(src, "RdmaAbi.createTicker(RdmaAbi.registerBlock(onTick, \"alice:counter\"))")
        assertContains(src, "inline fun each(noinline action: ((kotlin.String) -> kotlin.Unit)) {")
        assertContains(src, "asDynamic().each(RdmaAbi.registerBlock(action, \"alice:counter\"))")
    }

    @Test
    fun `lambda-typed properties and lambda results are typed function values`() {
        val toText = RdmaTypeRef(RdmaType.FunctionType(listOf(int), string))
        val src = single(
            RdmaManifest(
                classes = listOf(
                    RdmaClassInfo(
                        "com.k", "Box", "com.k.Box",
                        emptyList(),
                        listOf(MethodInfo("adder", "kotlin.Function1", emptyList(), returnFunctionType = RdmaTypeRef(RdmaType.FunctionType(listOf(int), int)))),
                        listOf(PropertyInfo("format", "kotlin.Function1", isMutable = true, functionType = toText)),
                    ),
                ),
            ),
        )
        assertContains(src, "var format: ((kotlin.Int) -> kotlin.String)")
        assertContains(src, "inline get() = asDynamic().getFormat().unsafeCast<((kotlin.Int) -> kotlin.String)>()")
        assertContains(src, "asDynamic().setFormat(RdmaAbi.registerBlock(value, \"alice:counter\"))")
        assertContains(src, "inline fun adder(): ((kotlin.Int) -> kotlin.Int) = asDynamic().adder().unsafeCast<((kotlin.Int) -> kotlin.Int)>()")
    }

    @Test
    fun `enum value is the entry name string`() {
        val src = single(
            RdmaManifest(enums = listOf(RdmaEnumInfo("com.k", "HttpMethod", "com.k.HttpMethod", listOf("GET", "POST")))),
        )
        assertContains(src, "class HttpMethod private constructor(rdmaNoInstances: kotlin.Nothing) {")
        assertFalse(src.contains("value class"))
        assertContains(src, "inline val name: kotlin.String get() = this.unsafeCast<kotlin.String>()")
        assertContains(src, "RdmaAbi.httpMethodOrdinal(this)")
        assertContains(src, "inline val GET: HttpMethod get() = \"GET\".unsafeCast<HttpMethod>()")
        assertContains(src, "RdmaAbi.httpMethodValueOf(name).unsafeCast<HttpMethod>()")
    }

    @Test
    fun `enum arguments pass through unconverted`() {
        val src = single(
            RdmaManifest(
                functions = listOf(
                    RdmaFunctionInfo("echoMethod", "com.k.echoMethod", "", false, listOf(RdmaParameterInfo("m", ref("com.k.HttpMethod"))), ref("com.k.HttpMethod")),
                ),
                enums = listOf(RdmaEnumInfo("com.k", "HttpMethod", "com.k.HttpMethod", listOf("GET"))),
            ),
        )
        assertContains(src, "return RdmaAbi.echoMethod(m).unsafeCast<com.k.HttpMethod>()")
    }

    @Test
    fun `registerComponent forwards to the runtime bridge with parameter descriptors`() {
        val src = single(RdmaManifest())
        assertContains(src, "fun registerComponent(name: kotlin.String, content: @androidx.compose.runtime.Composable () -> kotlin.Unit) {\n    com.p.rdmaRegisterComponent(name, \"\", content)")
        assertContains(src, "fun <P1, P2> registerComponent(name: kotlin.String, rdmaParamDescs: kotlin.String, content: @androidx.compose.runtime.Composable (P1, P2) -> kotlin.Unit) {\n    com.p.rdmaRegisterComponent(name, rdmaParamDescs, content)")
    }
}
