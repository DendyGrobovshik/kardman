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
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.PluginRelease
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-plugin release registry: `versions/plugins/<pluginId>.json` holds the append-only list of
 * [PluginRelease]s for that plugin. `plugin-release` appends; the store loads these files.
 */
object RdmaPluginReleases {
    private val json = Json { prettyPrint = true }
    private val listSerializer = ListSerializer(PluginRelease.serializer())

    fun file(versionsDir: File, pluginId: String): File =
        File(versionsDir, "plugins/$pluginId.json")

    fun read(versionsDir: File, pluginId: String): List<PluginRelease> {
        val file = file(versionsDir, pluginId)
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, file.readText()) }.getOrDefault(emptyList())
    }

    fun write(versionsDir: File, pluginId: String, releases: List<PluginRelease>): File {
        val file = file(versionsDir, pluginId)
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(listSerializer, releases))
        return file
    }

    fun append(versionsDir: File, release: PluginRelease): File {
        val releases = read(versionsDir, release.contract.pluginId) + release
        return write(versionsDir, release.contract.pluginId, releases)
    }

    fun nextVersion(existing: List<PluginRelease>): Int =
        (existing.maxOfOrNull { it.contract.version } ?: 0) + 1

    /** Loads every plugin's releases from the `versions/plugins/` directory. */
    fun loadAll(versionsDir: File): Map<String, List<PluginRelease>> {
        val pluginsDir = File(versionsDir, "plugins")
        if (!pluginsDir.isDirectory) return emptyMap()
        val result = mutableMapOf<String, List<PluginRelease>>()
        pluginsDir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
            val releases = runCatching { json.decodeFromString(listSerializer, file.readText()) }.getOrDefault(emptyList())
            result[file.nameWithoutExtension] = releases
        }
        return result
    }
}
