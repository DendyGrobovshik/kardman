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

import io.github.dendygrobovshik.kardman.types.PolyfillIndex
import io.github.dendygrobovshik.kardman.types.PolyfillRef
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `versions/polyfills.json`: which content-addressed polyfill serves which host (§8.1). */
object RdmaPolyfillIndex {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun file(versionsDir: File) = File(versionsDir, "polyfills.json")

    fun read(versionsDir: File): PolyfillIndex =
        file(versionsDir).takeIf { it.isFile }?.let { json.decodeFromString(PolyfillIndex.serializer(), it.readText()) } ?: PolyfillIndex()

    /** Written whole or not at all. */
    fun write(versionsDir: File, index: PolyfillIndex) {
        val target = file(versionsDir).apply { parentFile.mkdirs() }
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(json.encodeToString(PolyfillIndex.serializer(), index))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** The file a build writes for generation [g] (`_.hbc` when there are no generations). */
    fun bundleName(g: String): String = "${g.ifEmpty { "_" }}.hbc"

    fun putF(index: PolyfillIndex, module: String, hosts: List<Int>, g: String, ref: PolyfillRef): PolyfillIndex {
        val byHost = index.f[module].orEmpty().toMutableMap()
        for (h in hosts) byHost[h.toString()] = byHost[h.toString()].orEmpty() + (g to ref)
        return index.copy(f = index.f + (module to byHost))
    }

    fun addR(index: PolyfillIndex, module: String, g: String, ref: PolyfillRef): PolyfillIndex {
        val byGen = index.r[module].orEmpty().toMutableMap()
        byGen[g] = byGen[g].orEmpty().filter { it.hash != ref.hash } + ref
        return index.copy(r = index.r + (module to byGen))
    }
}
