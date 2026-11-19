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
import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef

/**
 * Generates the plugin's typed view of the kernel (docs/design/runtime_rework.md §5.0):
 * Kotlin declarations in the kernel packages, with the kernel's names and types, whose
 * members are `inline` and whose bodies are exactly the call form v1 (`RDMA.createX(…)`,
 * `obj.getX()`, `obj.m(…)`, `RDMA.f(…)`, enum entries as name strings, lambdas as
 * `registerBlock` ids). Plugin source compiles against them unchanged and fully typed;
 * after inlining, the emitted JS is the call form the pre-rework rewrite produced, so a
 * new plugin keeps running on old hosts.
 *
 * - an `@RDMA` class → a class whose only constructor is private and uncallable
 *   (`Nothing` parameter: instances are native proxies, and no factory signature can
 *   clash with it) plus a same-named top-level `inline fun` acting as its constructor;
 * - an `@RDMA` enum → a class with a private constructor whose runtime value IS the
 *   entry-name string. Not a `value class`: those are boxed in generic positions
 *   (`List<HttpMethod>`, `MutableState<HttpMethod>`, lambda parameters) while the
 *   kernel hands plain strings everywhere. An entry unknown to this plugin stays
 *   representable;
 * - a widget → a non-inline `@Composable` forwarding to `RDMA.compose<Name>`;
 * - `suspend` functions and methods → `suspend inline` over `registerContinuation`;
 * - `registerComponent` (0–3 component parameters) → forwarders to the runtime bridge's
 *   `rdmaRegisterComponent`; the JVM pass inserts the parameter descriptors.
 */
class RdmaKernelStubGenerator(
    private val manifests: List<RdmaManifest>,
    private val pluginId: String,
    private val pluginPackage: String,
    private val registerComponentFqn: String,
) {
    private val pid = pluginId.replace("\\", "\\\\").replace("\"", "\\\"")
    private val classesByFqn = manifests.flatMap { it.classes }.associateBy { it.qualifiedName }

    /** Classes some other `@RDMA` class extends: their stubs must be open. */
    private val superClasses = manifests.flatMap { it.classes }.mapNotNull { it.superClass }.toSet()

    /** Members a class inherits from its `@RDMA` superclass chain (declared there, not repeated). */
    private fun inheritedMemberNames(c: RdmaClassInfo): Set<String> {
        val names = mutableSetOf<String>()
        var sup = c.superClass?.let(classesByFqn::get)
        while (sup != null) {
            sup.methods.mapTo(names) { "m:" + it.name + "/" + it.parameters.size }
            sup.properties.mapTo(names) { "p:" + it.name }
            sup = sup.superClass?.let(classesByFqn::get)
        }
        return names
    }

    /** File name → content, one file per kernel package. */
    fun generate(): Map<String, String> {
        val classes = manifests.flatMap { it.classes }.distinctBy { it.qualifiedName }
        val functions = manifests.flatMap { it.functions }.distinctBy { it.qualifiedName }
            .filter { it.qualifiedName != registerComponentFqn }
        val enums = manifests.flatMap { it.enums }.distinctBy { it.qualifiedName }
        val packages = sortedSetOf<String>().apply {
            classes.mapTo(this) { it.packageName }
            functions.mapTo(this) { packageOf(it.qualifiedName) }
            enums.mapTo(this) { it.packageName }
            if (registerComponentFqn.isNotBlank()) add(packageOf(registerComponentFqn))
        }
        return packages.associate { pkg ->
            STUB_FILE_PREFIX + pkg.replace('.', '_') + ".kt" to packageFile(
                pkg,
                classes.filter { it.packageName == pkg },
                functions.filter { packageOf(it.qualifiedName) == pkg },
                enums.filter { it.packageName == pkg },
            )
        }
    }

    // ------------------------------------------------------------------ types

    /** Kotlin source of a boundary type. */
    private fun kt(t: RdmaTypeRef): String {
        val base = when (val ty = t.type) {
            is RdmaType.Primitive -> ty.fqn
            is RdmaType.Ref -> ty.fqn
            is RdmaType.UnitType -> return "kotlin.Unit"
            is RdmaType.ListType -> "kotlin.collections.List<${kt(ty.element)}>"
            is RdmaType.MapType -> "kotlin.collections.Map<${kt(ty.key)}, ${kt(ty.value)}>"
            is RdmaType.FunctionType -> {
                val params = ty.parameters.joinToString(", ") { kt(it) }
                return "(($params) -> ${kt(ty.returnType)})" + if (t.nullable) "?" else ""
            }
        }
        return if (t.nullable) "$base?" else base
    }

    private fun composableKt(t: RdmaTypeRef): String {
        val ty = t.type as RdmaType.FunctionType
        val params = ty.parameters.joinToString(", ") { kt(it) }
        return "@androidx.compose.runtime.Composable ($params) -> kotlin.Unit"
    }

    /** A class-member type described by the flat manifest strings, as a type ref. */
    private fun ref(
        type: String,
        nullable: Boolean,
        isList: Boolean = false,
        elem: String? = null,
        isMap: Boolean = false,
        key: String? = null,
        value: String? = null,
    ): RdmaTypeRef = when {
        isList -> RdmaTypeRef(RdmaType.ListType(fromDesc(elem ?: "kotlin.Any")), nullable)
        isMap -> RdmaTypeRef(RdmaType.MapType(fromDesc(key ?: "kotlin.Any"), fromDesc(value ?: "kotlin.Any")), nullable)
        type == "kotlin.Unit" -> RdmaTypeRef(RdmaType.UnitType)
        else -> fromDesc(type).let { if (nullable) it.copy(nullable = true) else it }
    }

    private fun ref(p: ParameterInfo) =
        p.functionType ?: ref(p.type, p.nullable, p.isList, p.listElementType, p.isMap, p.mapKeyType, p.mapValueType)
    private fun ref(p: PropertyInfo) =
        p.functionType ?: ref(p.type, p.nullable, p.isList, p.listElementType, p.isMap, p.mapKeyType, p.mapValueType)
    private fun returnRef(m: MethodInfo) =
        m.returnFunctionType ?: ref(m.returnType, m.nullableReturn, m.isList, m.listElementType, m.isMap, m.mapKeyType, m.mapValueType)

    /** A type descriptor (`list:…`, `map:k:v`, leaf with `?`/`!` marks) as a type ref. */
    private fun fromDesc(desc: String): RdmaTypeRef {
        if (desc.startsWith("list:")) return RdmaTypeRef(RdmaType.ListType(fromDesc(desc.removePrefix("list:"))))
        if (desc.startsWith("map:")) {
            val (k, v) = splitMapDesc(desc.removePrefix("map:"))
            return RdmaTypeRef(RdmaType.MapType(fromDesc(k), fromDesc(v)))
        }
        val nullable = desc.contains('?')
        val fqn = desc.substringBefore('?').substringBefore('!')
        return if (fqn.startsWith("kotlin.")) RdmaTypeRef(RdmaType.Primitive(fqn), nullable) else RdmaTypeRef(RdmaType.Ref(fqn), nullable)
    }

    /** Splits `<k>:<v>` where either side may itself be a `list:`/`map:` descriptor. */
    private fun splitMapDesc(s: String): Pair<String, String> {
        fun front(x: String): Pair<String, String> = when {
            x.startsWith("list:") -> front(x.removePrefix("list:")).let { "list:${it.first}" to it.second }
            x.startsWith("map:") -> {
                val (k, r1) = front(x.removePrefix("map:"))
                val (v, r2) = front(r1)
                "map:$k:$v" to r2
            }
            else -> x.indexOf(':').let { if (it < 0) x to "" else x.substring(0, it) to x.substring(it + 1) }
        }
        return front(s)
    }

    // ------------------------------------------------------- value conversions

    /**
     * Plugin value → call-form argument. Values (enums included: their runtime value is
     * the name string) pass as they are; only lambdas become block ids.
     */
    private fun toAbi(expr: String, t: RdmaTypeRef, hasDefault: Boolean): String = when (val ty = t.type) {
        is RdmaType.FunctionType -> "RdmaAbi.registerBlock($expr, \"$pid\")"
        is RdmaType.ListType -> if (ty.element.type is RdmaType.FunctionType) {
            val register = ".map { RdmaAbi.registerBlock(it, \"$pid\") }"
            when {
                // An omitted defaulted list stays `undefined`: the kernel fills the default.
                hasDefault -> "(if ($expr === js(\"undefined\")) js(\"undefined\") else $expr$register)"
                t.nullable -> "$expr?$register"
                else -> "$expr$register"
            }
        } else {
            expr
        }
        else -> expr
    }

    /** Call-form result (dynamic) → typed plugin value: a re-typing, no conversion. */
    private fun fromAbi(expr: String, t: RdmaTypeRef): String =
        if (t.type is RdmaType.UnitType) expr else "$expr.unsafeCast<${kt(t)}>()"

    // ----------------------------------------------------------------- params

    private data class P(val name: String, val type: RdmaTypeRef, val hasDefault: Boolean, val composable: Boolean = false)

    private fun decl(p: P, inlineFn: Boolean): String {
        // Lambdas are handed to `registerBlock`, never inlined (nullable ones are implicitly noinline).
        val noinline = if (inlineFn && p.type.type is RdmaType.FunctionType && !p.type.nullable) "noinline " else ""
        val type = if (p.composable) composableKt(p.type) else kt(p.type)
        val default = if (p.hasDefault) " = js(\"undefined\")" else ""
        return "$noinline${p.name}: $type$default"
    }

    private fun decls(params: List<P>, inlineFn: Boolean) = params.joinToString(", ") { decl(it, inlineFn) }

    private fun args(params: List<P>) = params.joinToString(", ") { toAbi(it.name, it.type, it.hasDefault) }

    // ------------------------------------------------------------------- files

    private fun packageFile(pkg: String, classes: List<RdmaClassInfo>, functions: List<RdmaFunctionInfo>, enums: List<RdmaEnumInfo>): String {
        val abi = sortedSetOf(
            "registerBlock(fn: dynamic, pluginId: kotlin.String): dynamic",
            "registerContinuation(onResult: dynamic, onError: dynamic): dynamic",
        )
        val body = StringBuilder()
        for (e in enums) body.append(enumStub(e, abi))
        for (c in classes) body.append(classStub(c, abi))
        for (f in functions) body.append(functionStub(f, abi))
        if (registerComponentFqn.isNotBlank() && packageOf(registerComponentFqn) == pkg) {
            val fn = registerComponentFqn.substringAfterLast('.')
            body.append(
                """
fun $fn(name: kotlin.String, content: @androidx.compose.runtime.Composable () -> kotlin.Unit) {
    $pluginPackage.rdmaRegisterComponent(name, "", content)
}
""",
            )
            // With parameters, the JVM pass inserts their descriptors after the name.
            for (n in 1..3) {
                val tps = (1..n).joinToString(", ") { "P$it" }
                body.append(
                    """
fun <$tps> $fn(name: kotlin.String, rdmaParamDescs: kotlin.String, content: @androidx.compose.runtime.Composable ($tps) -> kotlin.Unit) {
    $pluginPackage.rdmaRegisterComponent(name, rdmaParamDescs, content)
}
""",
                )
            }
        }
        return buildString {
            appendLine("// Generated by the rdma plugin compiler: the plugin's typed view of the kernel")
            appendLine("// (docs/design/runtime_rework.md §5.0). Do not edit.")
            appendLine("@file:Suppress(\"NOTHING_TO_INLINE\", \"unused\", \"UNUSED_PARAMETER\", \"UNCHECKED_CAST\", \"FunctionName\", \"RedundantSuppression\")")
            appendLine()
            appendLine("package $pkg")
            appendLine()
            appendLine("import kotlin.coroutines.resume")
            appendLine("import kotlin.coroutines.resumeWithException")
            appendLine("import kotlinx.coroutines.suspendCancellableCoroutine")
            appendLine()
            appendLine("/** The `RDMA` bridge object as seen by this package's stubs. */")
            appendLine("@PublishedApi")
            appendLine("@JsName(\"RDMA\")")
            appendLine("internal external object RdmaAbi {")
            for (sig in abi) appendLine("    fun $sig")
            appendLine("}")
            append(body)
        }
    }

    private fun abiSig(name: String, arity: Int) =
        "$name(" + (0 until arity).joinToString(", ") { "a$it: dynamic" } + "): dynamic"

    // Local names are prefixed so they never shadow a kernel parameter (`id`, `cont`, …).
    private fun suspendBody(call: String, ret: RdmaTypeRef, indent: String): String {
        val resumeValue = if (ret.type is RdmaType.UnitType) "kotlin.Unit" else fromAbi("rdmaValue", ret)
        // Cancellable: cancelling the plugin coroutine cancels the kernel call (hosts without
        // `cancelContinuation` let it finish; its late result is ignored).
        return """suspendCancellableCoroutine { rdmaCont ->
$indent    val rdmaId = RdmaAbi.registerContinuation(
$indent        { rdmaValue: dynamic -> rdmaCont.resume($resumeValue) },
$indent        { rdmaError: dynamic -> rdmaCont.resumeWithException(rdmaError.unsafeCast<kotlin.Throwable>()) },
$indent    )
$indent    rdmaCont.invokeOnCancellation { RdmaAbi.asDynamic().cancelContinuation?.call(null, rdmaId) }
$indent    $call
$indent}"""
    }

    private fun functionStub(f: RdmaFunctionInfo, abi: MutableSet<String>): String {
        val params = f.parameters.map { P(it.name, it.type, it.hasDefault, it.composable) }
        val ret = f.returnType
        return when {
            f.composable -> {
                val js = "compose" + f.name
                abi += abiSig(js, params.size)
                """
@androidx.compose.runtime.Composable
fun ${f.name}(${decls(params, inlineFn = false)}) {
    RdmaAbi.$js(${args(params)})
}
"""
            }
            f.suspend -> {
                val js = "_${f.name}Async"
                abi += abiSig(js, params.size + 1)
                val call = "RdmaAbi.$js(rdmaId" + (if (params.isEmpty()) "" else ", ${args(params)}") + ")"
                """
suspend inline fun ${f.name}(${decls(params, inlineFn = true)}): ${kt(ret)} = ${suspendBody(call, ret, "")}
"""
            }
            else -> {
                abi += abiSig(f.name, params.size)
                val call = "RdmaAbi.${f.name}(${args(params)})"
                // The collection classifiers must be installed before a value crosses;
                // a plain function may run before `registerComponent` (e.g. from `main`).
                val install = "$pluginPackage.rdmaListKindInstalled"
                if (ret.type is RdmaType.UnitType) {
                    """
inline fun ${f.name}(${decls(params, inlineFn = true)}) {
    $install
    $call
}
"""
                } else {
                    """
inline fun ${f.name}(${decls(params, inlineFn = true)}): ${kt(ret)} {
    $install
    return ${fromAbi(call, ret)}
}
"""
                }
            }
        }
    }

    private fun classStub(c: RdmaClassInfo, abi: MutableSet<String>): String {
        val members = StringBuilder()
        val inherited = inheritedMemberNames(c)
        for (p in c.properties) {
            if ("p:" + p.name in inherited) continue
            val t = ref(p)
            val cap = p.name.replaceFirstChar { it.uppercase() }
            members.append("    ${if (p.isMutable) "var" else "val"} ${p.name}: ${kt(t)}\n")
            members.append("        inline get() = ${fromAbi("asDynamic().get$cap()", t)}\n")
            if (p.isMutable) {
                // A lambda value is handed to `registerBlock`, so it cannot be an inline parameter.
                val param = if (t.type is RdmaType.FunctionType && !t.nullable) "noinline value" else "value"
                members.append("        inline set($param) {\n            asDynamic().set$cap(${toAbi("value", t, false)})\n        }\n")
            }
        }
        for (m in c.methods) {
            // `Any` members dispatch through `Any` (the proxy prototype implements them).
            if (m.name in anyMembers) continue
            if ("m:" + m.name + "/" + m.parameters.size in inherited) continue
            val params = m.parameters.map { P(it.name, ref(it), it.hasDefault) }
            val ret = returnRef(m)
            if (m.isSuspend) {
                val js = "_${c.className}_${m.name}Async"
                abi += abiSig(js, params.size + 2)
                val call = "RdmaAbi.$js(rdmaId, this" + (if (params.isEmpty()) "" else ", ${args(params)}") + ")"
                members.append("    suspend inline fun ${m.name}(${decls(params, true)}): ${kt(ret)} = ${suspendBody(call, ret, "    ")}\n")
            } else {
                val call = "asDynamic().${m.name}(${args(params)})"
                if (ret.type is RdmaType.UnitType) {
                    members.append("    inline fun ${m.name}(${decls(params, true)}) {\n        $call\n    }\n")
                } else {
                    members.append("    inline fun ${m.name}(${decls(params, true)}): ${kt(ret)} = ${fromAbi(call, ret)}\n")
                }
            }
        }
        val statics = StringBuilder()
        for (s in c.statics) {
            val js = c.className.replaceFirstChar { it.lowercase() } + s.name.replaceFirstChar { it.uppercase() }
            abi += abiSig(js, 0)
            val t = ref(s.type, s.nullable)
            statics.append("        val ${s.name}: ${kt(t)}\n            inline get() = ${fromAbi("RdmaAbi.$js()", t)}\n")
        }
        val companion = if (statics.isEmpty()) "" else "\n    companion object {\n$statics    }\n"
        val factory = c.constructors.firstOrNull()?.let { constructorStub(c, it, abi) } ?: ""
        return """
/** Plugin view of `@RDMA` class `${c.qualifiedName}`: instances are native proxies. */
${header(c)} {
$members$companion}
$factory"""
    }

    /**
     * The class header. The only constructor is uncallable (`Nothing`): instances are native
     * proxies. A class another `@RDMA` class extends is `open` with a `protected` constructor;
     * a subclass extends its superclass stub, so a `Circle` passes where a `Shape` is expected.
     */
    private fun header(c: RdmaClassInfo): String {
        val open = c.isOpen || c.qualifiedName in superClasses
        val modifier = if (open) "open " else ""
        val visibility = if (open) "protected" else "private"
        val sup = c.superClass?.takeIf { it in classesByFqn }?.let { " : $it(rdmaNoInstances)" } ?: ""
        return "${modifier}class ${c.className} $visibility constructor(rdmaNoInstances: kotlin.Nothing)$sup"
    }

    private fun constructorStub(c: RdmaClassInfo, ctor: ConstructorInfo, abi: MutableSet<String>): String {
        val js = "create${c.className}"
        val params = ctor.parameters.map { P(it.name, ref(it), it.hasDefault) }
        abi += abiSig(js, params.size)
        return """
inline fun ${c.className}(${decls(params, true)}): ${c.className} =
    RdmaAbi.$js(${args(params)}).unsafeCast<${c.className}>()
"""
    }

    private fun enumStub(e: RdmaEnumInfo, abi: MutableSet<String>): String {
        val lower = e.enumName.replaceFirstChar { it.lowercase() }
        abi += abiSig("${lower}Ordinal", 1)
        abi += abiSig("${lower}ValueOf", 1)
        val entries = e.entries.joinToString("\n") {
            "        inline val $it: ${e.enumName} get() = \"$it\".unsafeCast<${e.enumName}>()"
        }
        return """
/**
 * Plugin view of `@RDMA` enum `${e.qualifiedName}`. The runtime value IS the entry-name
 * string (call form v1): `==`, `when`, hashing and string templates work on the name.
 */
class ${e.enumName} private constructor(rdmaNoInstances: kotlin.Nothing) {
    inline val name: kotlin.String get() = this.unsafeCast<kotlin.String>()
    inline val ordinal: kotlin.Int get() = RdmaAbi.${lower}Ordinal(this).unsafeCast<kotlin.Int>()

    companion object {
$entries

        inline fun valueOf(name: kotlin.String): ${e.enumName} =
            RdmaAbi.${lower}ValueOf(name).unsafeCast<${e.enumName}>()
    }
}
"""
    }

    private fun packageOf(fqn: String) = fqn.substringBeforeLast('.', "")

    private companion object {
        val anyMembers = setOf("toString", "equals", "hashCode")
    }
}
