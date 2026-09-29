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

import io.github.dendygrobovshik.kardman.types.RdmaDeclaration
import java.security.MessageDigest

/** SHA-256 content hash over a declaration's raw source text (no normalization, "as is"). */
object RdmaContentHasher {
    fun sha256(text: String): String = sha256Bytes(text.toByteArray(Charsets.UTF_8))

    fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Returns the offset of the `{` that opens the class body, scanning from [start] and skipping
 * braces nested inside `(...)`/`[...]`. Used to slice only the class header (signature,
 * modifiers, annotations, supertypes, primary constructor) for the class-structure hash.
 */
fun classHeaderEnd(text: String, start: Int): Int {
    var paren = 0
    var bracket = 0
    var i = start
    while (i < text.length) {
        when (text[i]) {
            '(' -> paren++
            ')' -> paren--
            '[' -> bracket++
            ']' -> bracket--
            '{' -> if (paren == 0 && bracket == 0) return i
        }
        i++
    }
    return text.length
}

/** The set of declarations that differ between the current compile and the committed baseline. */
data class RdmaDirtySet(
    val added: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
    val changed: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty()
}

object RdmaHashDiff {
    fun diff(current: List<RdmaDeclaration>, baseline: Map<String, String>): RdmaDirtySet {
        val currentByFqn = current.associate { it.fqn to it.hash }
        val added = currentByFqn.keys - baseline.keys
        val removed = baseline.keys - currentByFqn.keys
        val changed = currentByFqn.entries
            .filter { (fqn, hash) -> baseline[fqn] != null && baseline[fqn] != hash }
            .map { it.key }
            .toSet()
        return RdmaDirtySet(added, removed, changed)
    }
}
