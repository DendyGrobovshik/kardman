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

    private val fileTexts = mutableMapOf<String, String>()
    private val edits = mutableMapOf<String, MutableList<SourceEdit>>()
    private val subclasses = mutableMapOf<String, RdmaSubclass>()

    fun configure(jsonPath: String?, outputDir: String?) {
        outputDir?.let { this.outputDir = it }
        val path = jsonPath ?: return
        val file = File(path)
        if (file.exists()) {
            types = RdmaPluginConfig.parseClassesJson(file.readText())
        }
    }

    fun typeByQualifiedName(fqn: String): RdmaPluginType? = types.find { it.qualifiedName == fqn }

    fun isRdmaQualifiedName(fqn: String): Boolean = types.any { it.qualifiedName == fqn }

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
        for ((path, fileEdits) in edits) {
            if (fileEdits.isEmpty()) continue
            val sourceFile = File(path)
            val text = textOf(path)
            val sorted = fileEdits
                .filter { it.start in 0..text.length && it.end in it.start..text.length }
                .sortedByDescending { it.start }
            val sb = StringBuilder(text)
            for (edit in sorted) {
                sb.replace(edit.start, edit.end, edit.replacement)
            }
            val outName = sourceFile.nameWithoutExtension + "_rdma.kt"
            val outDir = File(outputDir)
            outDir.mkdirs()
            File(outDir, outName).writeText(sb.toString())
        }
    }

    fun reset() {
        fileTexts.clear()
        edits.clear()
    }
}
