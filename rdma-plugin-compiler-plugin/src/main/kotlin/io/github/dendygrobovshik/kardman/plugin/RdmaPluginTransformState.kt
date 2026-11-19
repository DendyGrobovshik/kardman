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

import io.github.dendygrobovshik.kardman.types.RdmaManifest
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import kotlinx.serialization.json.Json
import java.io.File

data class SourceEdit(val start: Int, val end: Int, val replacement: String)

data class RdmaSubclass(
    val simpleName: String,
    val qualifiedName: String,
    val parentSimpleName: String,
    val overrides: List<Pair<String, String>>,
    val parentQualifiedName: String = parentSimpleName,
)

/** Generated files of earlier toolchains that the stubs replace; removed on flush. */
private val legacyGeneratedFiles = setOf(
    "RdmaException.kt",
    "RdmaFunctionBridge.kt",
    "RdmaWidgetBridge.kt",
    "RdmaStaticBridge.kt",
    "RdmaEnumBridge.kt",
)

const val STUB_FILE_PREFIX = "RdmaKernelStubs_"

object RdmaPluginTransformState {
    var outputDir: String = ""
    var pluginPackage: String = "com.example.plugin"
    var registerComponentFqn: String = "com.example.kernel.registerComponent"
    var kernelRootPackage: String = ""
    var pluginId: String = ""
    var pluginVersion: Int = 1
    var builtAgainstKernel: Int = 0
    var builtAgainstToolchain: String = ""
    var moduleDeps: List<String> = emptyList()
        private set
    var floorMap: Map<String, Int> = emptyMap()

    /** The plugin's declared `minHost` (compatibility.md §5.3): moved only by the developer. */
    var declaredMinHost: Int = 0
        private set
    var types: List<RdmaPluginType> = emptyList()
        private set
    var functions: List<RdmaPluginFunction> = emptyList()
        private set
    var enums: List<RdmaPluginEnum> = emptyList()
        private set
    /** The kernel manifests as read, for the stub generator (full member types). */
    var rawManifests: List<RdmaManifest> = emptyList()
        private set

    private val fileTexts = mutableMapOf<String, String>()
    private val edits = mutableMapOf<String, MutableList<SourceEdit>>()
    private val subclasses = mutableMapOf<String, RdmaSubclass>()
    private val usedSymbols = sortedSetOf<String>()

    private val composeBridgeable = setOf(
        "androidx.compose.runtime.mutableStateOf",
        "androidx.compose.runtime.mutableIntStateOf",
        "androidx.compose.runtime.mutableStateListOf",
        "androidx.compose.runtime.mutableStateMapOf",
        "androidx.compose.runtime.SideEffect",
        "androidx.compose.runtime.DisposableEffect",
        "androidx.compose.runtime.LaunchedEffect",
        "androidx.compose.runtime.rememberCoroutineScope",
    )

    /** State factories: each call opens an erased value channel and therefore must
     *  carry the channel's type descriptor (docs/design/type_descriptors.md). */
    val stateFactoryFqns = setOf(
        "androidx.compose.runtime.mutableStateOf",
        "androidx.compose.runtime.mutableIntStateOf",
        "androidx.compose.runtime.mutableStateListOf",
        "androidx.compose.runtime.mutableStateMapOf",
    )

    fun configure(
        manifestPaths: List<String>,
        pluginPackage: String?,
        registerComponentFqn: String?,
        kernelRootPackage: String?,
        outputDir: String?,
        pluginId: String?,
        pluginVersion: Int?,
        builtAgainstKernel: Int?,
        builtAgainstToolchain: String?,
        moduleDeps: List<String>,
        floorMap: Map<String, Int>,
        declaredMinHost: Int = 0,
    ) {
        this.declaredMinHost = declaredMinHost
        outputDir?.let { this.outputDir = it }
        pluginPackage?.let { this.pluginPackage = it }
        registerComponentFqn?.let { this.registerComponentFqn = it }
        kernelRootPackage?.let { this.kernelRootPackage = it }
        pluginId?.let { this.pluginId = it }
        pluginVersion?.let { this.pluginVersion = it }
        builtAgainstKernel?.let { this.builtAgainstKernel = it }
        builtAgainstToolchain?.let { this.builtAgainstToolchain = it }
        this.moduleDeps = moduleDeps
        this.floorMap = floorMap
        usedSymbols.clear()
        val allTypes = mutableListOf<RdmaPluginType>()
        val allFunctions = mutableListOf<RdmaPluginFunction>()
        val allEnums = mutableListOf<RdmaPluginEnum>()
        val raw = mutableListOf<RdmaManifest>()
        for (path in manifestPaths) {
            val file = File(path)
            if (file.exists()) {
                val text = file.readText()
                val manifest = RdmaPluginConfig.parseManifest(text)
                allTypes += manifest.classes
                allFunctions += manifest.functions
                allEnums += manifest.enums
                RdmaPluginConfig.parseRawManifest(text)?.let { raw += it }
            }
        }
        types = allTypes
        functions = allFunctions
        enums = allEnums
        rawManifests = raw
    }

    fun recordUsedSymbol(fqn: String) {
        usedSymbols.add(fqn)
    }

    fun typeByQualifiedName(fqn: String): RdmaPluginType? = types.find { it.qualifiedName == fqn }

    fun isClassQualifiedName(fqn: String): Boolean = types.any { it.qualifiedName == fqn }

    fun enumByQualifiedName(fqn: String): RdmaPluginEnum? = enums.find { it.qualifiedName == fqn }

    fun isEnumQualifiedName(fqn: String): Boolean = enums.any { it.qualifiedName == fqn }

    fun isFunctionQualifiedName(fqn: String): Boolean =
        functions.any { it.qualifiedName == fqn } || fqn == registerComponentFqn || fqn in composeBridgeable

    /** Compose entry points the JVM pass still rewrites into typed runtime-bridge calls. */
    fun isComposeEntryPoint(fqn: String): Boolean = fqn in composeBridgeable

    fun isBridgeableQualifiedName(fqn: String): Boolean =
        isClassQualifiedName(fqn) || isFunctionQualifiedName(fqn) || isEnumQualifiedName(fqn)

    fun isKernelPackage(fqn: String): Boolean =
        kernelRootPackage.isNotEmpty() && (fqn == kernelRootPackage || fqn.startsWith("$kernelRootPackage."))

    fun bridgeNameFor(fqn: String): String = when (fqn) {
        "androidx.compose.runtime.mutableStateOf" -> "rdmaMutableStateOf"
        "androidx.compose.runtime.mutableIntStateOf" -> "rdmaMutableIntStateOf"
        "androidx.compose.runtime.mutableStateListOf" -> "rdmaMutableStateListOf"
        "androidx.compose.runtime.mutableStateMapOf" -> "rdmaMutableStateMapOf"
        "androidx.compose.runtime.SideEffect" -> "rdmaSideEffect"
        "androidx.compose.runtime.DisposableEffect" -> "rdmaDisposableEffect"
        "androidx.compose.runtime.LaunchedEffect" -> "rdmaLaunchedEffect"
        "androidx.compose.runtime.rememberCoroutineScope" -> "rdmaRememberCoroutineScope"
        else -> error("not a compose entry point: $fqn")
    }

    private data class ConstructorCall(val path: String, val start: Int, val end: Int, val fqn: String, val argTexts: List<String>)

    private val constructorCalls = mutableListOf<ConstructorCall>()

    /** A plugin-class constructor call; rewritten in [flush] if the class subclasses a kernel class. */
    fun recordConstructorCall(path: String, start: Int, end: Int, fqn: String, argTexts: List<String>) {
        constructorCalls += ConstructorCall(path, start, end, fqn, argTexts)
    }

    /**
     * Constructor calls of plugin subclasses of `open` kernel classes become
     * `RDMA.createWithOverrides(...)`, typed as the parent.
     */
    private fun rewriteSubclassConstructorCalls() {
        for (call in constructorCalls) {
            val subclass = subclasses[call.fqn] ?: continue
            recordUsedSymbol(subclass.parentQualifiedName)
            val overridesJs = subclass.overrides.joinToString(", ") { (n, b) -> "$n: function() { return $b; }" }
            val replacement = buildString {
                append("js(\"\"\"RDMA.createWithOverrides('")
                append(subclass.parentSimpleName)
                append("', [")
                append(call.argTexts.joinToString(", "))
                append("], { ")
                append(overridesJs)
                append(" })\"\"\").unsafeCast<")
                append(subclass.parentQualifiedName)
                append(">()")
            }
            removeEditsWithin(call.path, call.start, call.end)
            addEdit(call.path, call.start, call.end, replacement)
        }
        constructorCalls.clear()
    }

    fun registerSubclass(qualifiedName: String, subclass: RdmaSubclass) {
        subclasses[qualifiedName] = subclass
    }

    fun subclassByQualifiedName(fqn: String): RdmaSubclass? = subclasses[fqn]

    fun textOf(path: String): String {
        fileTexts[path]?.let { return it }
        val text = File(path).readText()
        fileTexts[path] = text
        return text
    }

    fun registerText(path: String, text: String) {
        fileTexts.putIfAbsent(path, text)
    }

    fun addEdit(path: String, start: Int, end: Int, replacement: String) {
        edits.getOrPut(path) { mutableListOf() }.add(SourceEdit(start, end, replacement))
    }

    /** Drops edits fully inside `[start, end)` — used by whole-span rewrites that cover nested expressions. */
    fun removeEditsWithin(path: String, start: Int, end: Int) {
        // Half-open containment: an edit must START inside the span. A zero-width
        // edit at `end` (e.g. the state factory's `, typeDesc = ...` insert next to
        // a nested constructor rewrite) sits on the boundary, not inside it, and
        // must survive the nested rewrite.
        edits[path]?.removeAll { it.start >= start && it.start < end && it.end <= end }
    }

    /** True if an existing edit fully covers `[start, end)` (a whole-span rewrite above this expression). */
    fun isCovered(path: String, start: Int, end: Int): Boolean =
        edits[path]?.any { it.start <= start && it.end >= end } == true

    /**
     * Writes the generated sources and the contract. Returns the errors to report; a plugin
     * that needs a newer host than it declares is not built (compatibility.md §5.3).
     */
    fun flush(): List<String> {
        if (outputDir.isBlank()) return emptyList()
        rewriteSubclassConstructorCalls()
        val outDir = File(outputDir)
        // Remove stale rewritten sources so deleting a plugin file does not leave
        // a dangling `*_rdma.kt` that still references removed kernel symbols. Also
        // remove the old per-plugin `RdmaException.kt` bridge, which is no longer
        // generated (uncaught exceptions now use a single global host handler).
        outDir.listFiles { f ->
            f.isFile && (f.name.endsWith("_rdma.kt") || f.name in legacyGeneratedFiles || f.name.startsWith(STUB_FILE_PREFIX))
        }?.forEach { it.delete() }
        // Emit every plugin source file: edited ones get the rewrites applied, and
        // untouched files (plain data classes / helpers with no @RDMA usage) are
        // copied verbatim so they remain available to the JS compilation.
        for ((path, text) in fileTexts) {
            val sourceFile = File(path)
            val fileEdits = edits[path].orEmpty()
            val content = if (fileEdits.isEmpty()) {
                text
            } else {
                val sorted = fileEdits
                    .filter { it.start in 0..text.length && it.end in it.start..text.length }
                    .sortedByDescending { it.start }
                val sb = StringBuilder(text)
                for (edit in sorted) {
                    sb.replace(edit.start, edit.end, edit.replacement)
                }
                sb.toString()
            }
            val outName = sourceFile.nameWithoutExtension + "_rdma.kt"
            outDir.mkdirs()
            File(outDir, outName).writeText(content)
        }
        writeKernelStubs(outDir)
        return writeContract(outDir)
    }

    /**
     * The plugin's typed view of the kernel (docs/design/runtime_rework.md §5.0): one
     * file of `inline` stubs per kernel package, so plugin source compiles to JS
     * unchanged while emitting exactly call form v1.
     */
    private fun writeKernelStubs(outDir: File) {
        val files = RdmaKernelStubGenerator(rawManifests, pluginId, pluginPackage, registerComponentFqn).generate()
        if (files.isEmpty()) return
        outDir.mkdirs()
        for ((name, content) in files) File(outDir, name).writeText(content)
    }

    private fun writeContract(outDir: File): List<String> {
        if (pluginId.isBlank()) return emptyList()
        // `minHost` never moves on its own: a symbol that needs a newer host than the plugin
        // declares fails the build instead of silently narrowing the audience.
        val tooNew = usedSymbols.filter { (floorMap[it] ?: 0) > declaredMinHost }.sorted()
        if (tooNew.isNotEmpty()) {
            val required = tooNew.maxOf { floorMap[it] ?: 0 }
            return listOf(
                "plugin $pluginId uses symbols that need host version $required or newer, but declares minHost=$declaredMinHost: " +
                    tooNew.joinToString { "$it (host ${floorMap[it]})" } +
                    ". Set rdmaMinHost=$required in the plugin's gradle.properties to drop older hosts, or stop using these symbols.",
            )
        }
        val minHost = declaredMinHost
        val contract = RdmaPluginContract(
            pluginId = pluginId,
            version = pluginVersion,
            builtAgainstKernel = builtAgainstKernel,
            builtAgainstToolchain = builtAgainstToolchain,
            minHost = minHost,
            moduleDeps = moduleDeps,
            usedSymbols = usedSymbols.toList(),
        )
        val json = Json { prettyPrint = true }
        outDir.mkdirs()
        File(outDir, "plugin.json").writeText(json.encodeToString(RdmaPluginContract.serializer(), contract))
        return emptyList()
    }

    fun reset() {
        fileTexts.clear()
        edits.clear()
        constructorCalls.clear()
    }
}
