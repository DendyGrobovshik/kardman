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
package io.github.dendygrobovshik.kardman.types

import java.io.File

/**
 * Minimal hand-rolled reader for the project's fixed `rdma.toml` schema (see [RdmaConfig]). It
 * supports exactly what the schema needs — `[section]` headers, `key = "string"`, bare values
 * (`key = 42`), string arrays (`key = ["a", "b"]`), `#` comments and blank lines — and nothing
 * else. A full TOML library is unnecessary for a structure this small and fixed.
 */
object RdmaToml {

    /** Reads [file]; returns the default config when the file is absent or unreadable. */
    fun load(file: File): RdmaConfig =
        try {
            if (file.isFile) parse(file.readText()) else RdmaConfig()
        } catch (e: Throwable) {
            System.err.println("RdmaToml.load: failed to read ${file.absolutePath}: ${e::class.simpleName}: ${e.message}")
            RdmaConfig()
        }

    fun parse(text: String): RdmaConfig {
        var section = ""
        val strings = mutableMapOf<String, String>()
        val arrays = mutableMapOf<String, List<String>>()

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            val fullKey = if (section.isEmpty()) key else "$section.$key"
            when (val parsed = parseValue(value)) {
                is Value.Str -> strings[fullKey] = parsed.s
                is Value.Arr -> arrays[fullKey] = parsed.items
            }
        }

        fun str(k: String) = strings[k].orEmpty()
        fun intOrNull(k: String) = strings[k]?.toIntOrNull()
        fun arr(k: String) = arrays[k].orEmpty()

        return RdmaConfig(
            kernel = KernelConfig(
                rootPackage = str("kernel.rootPackage"),
                project = str("kernel.project"),
                internals = arr("kernel.internals"),
                oldestActiveKernel = intOrNull("kernel.oldestActiveKernel"),
            ),
            plugin = PluginConfig(
                rootPackage = str("plugin.rootPackage"),
                project = str("plugin.project"),
            ),
            toolchain = ToolchainConfig(
                oldestActiveGeneration = str("toolchain.oldestActiveGeneration"),
            ),
            hermes = HermesConfig(
                path = str("hermes.path"),
            ),
        )
    }

    /**
     * Updates `toolchain.oldestActiveGeneration` in [file] in place, preserving comments and
     * other sections. Used by `generation-release`/`retire-generation` to advance the FIFO floor.
     * Creates the file (with a `[toolchain]` section) if it does not exist.
     */
    fun writeOldestActiveGeneration(file: File, g: String): File {
        val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
        var inToolchain = false
        var toolchainIndex = -1
        var keyIndex = -1
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith('[')) {
                inToolchain = line == "[toolchain]"
                if (inToolchain) toolchainIndex = i
                continue
            }
            if (inToolchain && line.startsWith("oldestActiveGeneration")) {
                keyIndex = i
                break
            }
        }

        val newLine = "oldestActiveGeneration = \"$g\""
        when {
            keyIndex >= 0 -> lines[keyIndex] = newLine
            toolchainIndex >= 0 -> lines.add(toolchainIndex + 1, newLine)
            else -> {
                lines.add("[toolchain]")
                lines.add(newLine)
            }
        }

        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        return file
    }

    private sealed interface Value {
        data class Str(val s: String) : Value
        data class Arr(val items: List<String>) : Value
    }

    private fun parseValue(v: String): Value {
        val s = v.trim()
        if (s.startsWith('[')) {
            val inner = s.removePrefix("[").removeSuffix("]")
            val items = inner.split(',')
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .filter { it.isNotEmpty() }
            return Value.Arr(items)
        }
        if (s.startsWith('"') && s.endsWith('"') && s.length >= 2) {
            return Value.Str(s.substring(1, s.length - 1))
        }
        return Value.Str(s)
    }
}
