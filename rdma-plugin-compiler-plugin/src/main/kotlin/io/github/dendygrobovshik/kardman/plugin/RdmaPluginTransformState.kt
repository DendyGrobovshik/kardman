package io.github.dendygrobovshik.kardman.plugin

import java.io.File

data class SourceEdit(val start: Int, val end: Int, val replacement: String)

data class RdmaSubclass(
    val simpleName: String,
    val qualifiedName: String,
    val parentSimpleName: String,
    val overrides: List<Pair<String, String>>,
)

object RdmaPluginTransformState {
    var outputDir: String = ""
    var types: List<RdmaPluginType> = emptyList()
        private set
    var functions: List<RdmaPluginFunction> = emptyList()
        private set

    private val fileTexts = mutableMapOf<String, String>()
    private val edits = mutableMapOf<String, MutableList<SourceEdit>>()
    private val subclasses = mutableMapOf<String, RdmaSubclass>()

    private val extraBridgeable = setOf(
        "com.example.kernel.runRdmaApp",
        "androidx.compose.runtime.mutableStateOf",
    )

    fun configure(manifestPath: String?, outputDir: String?) {
        outputDir?.let { this.outputDir = it }
        manifestPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val manifest = RdmaPluginConfig.parseManifest(file.readText())
                types = manifest.classes
                functions = manifest.functions
            }
        }
    }

    fun typeByQualifiedName(fqn: String): RdmaPluginType? = types.find { it.qualifiedName == fqn }

    fun isClassQualifiedName(fqn: String): Boolean = types.any { it.qualifiedName == fqn }

    fun isFunctionQualifiedName(fqn: String): Boolean =
        functions.any { it.qualifiedName == fqn } || fqn in extraBridgeable

    fun isBridgeableQualifiedName(fqn: String): Boolean =
        isClassQualifiedName(fqn) || isFunctionQualifiedName(fqn)

    fun bridgeNameFor(fqn: String): String = when (fqn) {
        "com.example.kernel.runRdmaApp" -> "rdmaRunApp"
        "androidx.compose.runtime.mutableStateOf" -> "rdmaMutableStateOf"
        else -> "rdma" + fqn.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    fun composeFnName(simpleName: String): String = "compose" + simpleName

    fun staticBridgeNameFor(className: String, staticName: String): String =
        "rdma" + className + staticName.replaceFirstChar { it.uppercase() }

    fun staticJsName(className: String, staticName: String): String =
        className.replaceFirstChar { it.lowercase() } + staticName.replaceFirstChar { it.uppercase() }

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

    fun flush() {
        if (outputDir.isBlank()) return
        val outDir = File(outputDir)
        // Remove stale rewritten sources so deleting a plugin file does not leave
        // a dangling `*_rdma.kt` that still references removed kernel symbols.
        outDir.listFiles { f -> f.isFile && f.name.endsWith("_rdma.kt") }?.forEach { it.delete() }
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
        writeFunctionBridge(outDir)
        writeWidgetBridge(outDir)
        writeStaticBridge(outDir)
    }

    private fun writeFunctionBridge(outDir: File) {
        val bridgeFile = File(outDir, "RdmaFunctionBridge.kt")
        val plain = functions.filter { !it.composable }
        if (plain.isEmpty()) {
            bridgeFile.delete()
            return
        }
        outDir.mkdirs()
        bridgeFile.writeText(buildFunctionBridge(plain))
    }

    private fun writeWidgetBridge(outDir: File) {
        val bridgeFile = File(outDir, "RdmaWidgetBridge.kt")
        val widgets = functions.filter { it.composable }
        if (widgets.isEmpty()) {
            bridgeFile.delete()
            return
        }
        outDir.mkdirs()
        bridgeFile.writeText(buildWidgetBridge(widgets))
    }

    private fun writeStaticBridge(outDir: File) {
        val bridgeFile = File(outDir, "RdmaStaticBridge.kt")
        val statics = types.flatMap { type -> type.statics.map { type to it } }
        if (statics.isEmpty()) {
            bridgeFile.delete()
            return
        }
        outDir.mkdirs()
        bridgeFile.writeText(buildStaticBridge(statics))
    }

    internal fun buildStaticBridge(statics: List<Pair<RdmaPluginType, String>>): String {
        val sb = StringBuilder()
        sb.appendLine("package com.example.plugin")
        sb.appendLine()
        for ((type, name) in statics) {
            sb.appendLine("fun ${staticBridgeNameFor(type.simpleName, name)}(): dynamic = js(\"RDMA\").${staticJsName(type.simpleName, name)}()")
            sb.appendLine()
        }
        return sb.toString()
    }

    internal fun buildFunctionBridge(plainFunctions: List<RdmaPluginFunction>): String {
        val sb = StringBuilder()
        sb.appendLine("package com.example.plugin")
        sb.appendLine()
        for (fn in plainFunctions) {
            if (fn.composable) continue
            val bridgeName = bridgeNameFor(fn.qualifiedName)
            val params = fn.parameters.mapIndexed { i, p ->
                if (p.kind == RdmaParamKind.VALUE) {
                    "p$i: dynamic"
                } else {
                    val inner = (0 until (p.lambdaArity ?: 0)).joinToString(", ") { "dynamic" }
                    "p$i: ($inner) -> dynamic"
                }
            }.joinToString(", ")
            val args = fn.parameters.mapIndexed { i, p ->
                if (p.kind == RdmaParamKind.VALUE) "p$i" else "js(\"RDMA\").registerBlock(p$i)"
            }.joinToString(", ")
            sb.appendLine("fun $bridgeName($params): dynamic = js(\"RDMA\").${fn.name}($args)")
            sb.appendLine()
        }
        return sb.toString()
    }

    internal fun buildWidgetBridge(widgets: List<RdmaPluginFunction>): String {
        val sb = StringBuilder()
        sb.appendLine("package com.example.plugin")
        sb.appendLine()
        sb.appendLine("import androidx.compose.runtime.Composable")
        sb.appendLine()
        for (fn in widgets) {
            if (!fn.composable) continue
            val bridgeName = bridgeNameFor(fn.qualifiedName)
            val params = fn.parameters.joinToString(", ") { p -> "${p.name}: ${p.kotlinType}" }
            val args = fn.parameters.joinToString(", ") { p ->
                if (p.kind == RdmaParamKind.VALUE) p.name else "js(\"RDMA\").registerBlock(${p.name})"
            }
            val composeName = composeFnName(fn.name)
            sb.appendLine("@Composable")
            sb.appendLine("fun $bridgeName($params) {")
            sb.appendLine("    js(\"RDMA\").$composeName($args)")
            sb.appendLine("}")
            sb.appendLine()
        }
        return sb.toString()
    }

    fun reset() {
        fileTexts.clear()
        edits.clear()
    }
}
