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
    const val SIGNATURE_SUFFIX = ".sig"

    /**
     * The release signing key: `RDMA_SIGNING_KEY`, a base64 PKCS#8 Ed25519 private key (from
     * `generate-signing-key`), kept in the CI secrets. Without it bundles are published unsigned.
     */
    private val signingKey: java.security.PrivateKey? by lazy {
        System.getenv("RDMA_SIGNING_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { encoded ->
            java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(encoded)))
        }
    }

    /** Ed25519 signature of a bundle key (its content hash), or null when no key is configured. */
    fun sign(key: String): ByteArray? {
        val privateKey = signingKey ?: return null
        return java.security.Signature.getInstance("Ed25519").run {
            initSign(privateKey)
            update(key.lowercase().encodeToByteArray())
            sign()
        }
    }

    /** A new key pair: (base64 PKCS#8 private key for CI, base64 raw 32-byte public key for apps). */
    fun generateKeyPair(): Pair<String, String> {
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoder = java.util.Base64.getEncoder()
        val raw = pair.public.encoded.takeLast(32).toByteArray() // X.509 SubjectPublicKeyInfo ends with the raw key
        return encoder.encodeToString(pair.private.encoded) to encoder.encodeToString(raw)
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        object : GZIPOutputStream(out) {
            init { def.setLevel(Deflater.BEST_COMPRESSION) }
        }.use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * Writes `bundles/<key>`, `bundles/<key>.gz` and, when a signing key is configured,
     * `bundles/<key>.sig`; each file appears whole or not at all, the bundle last.
     */
    fun publish(versionsDir: File, key: String, bytes: ByteArray): File {
        val dir = File(versionsDir, "bundles").apply { mkdirs() }
        sign(key)?.let { writeAtomically(File(dir, key + SIGNATURE_SUFFIX), it) }
        writeAtomically(File(dir, key + GZIP_SUFFIX), gzip(bytes))
        return File(dir, key).also { writeAtomically(it, bytes) }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
