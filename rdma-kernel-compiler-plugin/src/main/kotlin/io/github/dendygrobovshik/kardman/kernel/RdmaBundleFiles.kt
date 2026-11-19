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

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Publishing a bundle into the store's `versions/bundles/` (compatibility.md §8.1): the bundle
 * under its content key and, next to it, `<key>.gz` — compressed once, at release time. The store
 * (or any static host) serves the `.gz` as is with `Content-Encoding: gzip`, which the platform
 * HTTP clients decompress transparently; the client caches the decompressed bytecode, which
 * Hermes maps directly. Content keys are hashes of the uncompressed bytes.
 *
 * Level 9: on the stand's shared runtime (7.2 MB of bytecode) it gives 34.1% of the size against
 * 34.3% at the default level 6, at four times the compression time — paid once per release —
 * while decompression takes the same time at every level.
 */
object RdmaBundleFiles {
    const val GZIP_SUFFIX = ".gz"

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        object : GZIPOutputStream(out) {
            init { def.setLevel(Deflater.BEST_COMPRESSION) }
        }.use { it.write(bytes) }
        return out.toByteArray()
    }

    /** Writes `bundles/<key>` and `bundles/<key>.gz`; each file appears whole or not at all. */
    fun publish(versionsDir: File, key: String, bytes: ByteArray): File {
        val dir = File(versionsDir, "bundles").apply { mkdirs() }
        writeAtomically(File(dir, key + GZIP_SUFFIX), gzip(bytes))
        return File(dir, key).also { writeAtomically(it, bytes) }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
