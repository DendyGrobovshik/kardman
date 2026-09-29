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

/**
 * The delta-bundle optimization (§8.3): cuts a polyfill bundle into content-addressed pieces so
 * the client downloads only what is missing. Applicable only to modules **without** shared
 * mutable state (§6.5); a module with shared state stays monolithic (and correct).
 */
object RdmaDeltaBundle {

    data class Piece(val key: String, val content: String)

    /**
     * Splits the materialized polyfill [files] into content-addressed pieces. With shared state the
     * bundle is kept monolithic (one piece); otherwise each file is its own piece.
     */
    fun split(files: List<PolyfillSourceFile>, sharedState: Set<String>): List<Piece> {
        if (files.isEmpty()) return emptyList()
        return if (sharedState.isNotEmpty()) {
            val content = files.joinToString("\n\n") { it.content }
            listOf(Piece(RdmaContentHasher.sha256(content), content))
        } else {
            files.map { Piece(RdmaContentHasher.sha256(it.content), it.content) }
        }
    }
}
