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

/**
 * Textual encoding of a **type descriptor** — the compile-time target type of an
 * erased cross-boundary channel, baked into the generated bridge and carried with
 * the channel at runtime (`docs/design/type_descriptors.md`).
 *
 * Grammar (mirrored by `common/RdmaTypeDesc.h` in both runtimes):
 *
 * ```
 * descriptor := leaf | "list:" descriptor | "map:" descriptor ":" descriptor
 * leaf       := <type fqn> ["?"] ["!"]
 * ```
 *
 * `?` marks a nullable type (null is a value on that channel), `!` marks an
 * `@RDMA` enum (crosses as the entry name string; the kernel materializes it via
 * `valueOf`). In `map:key:value` the `:` between key and value belongs to the map
 * rule; a leaf stops at the next `:` without consuming it.
 *
 * This is a private contract between the generated kernel bridge and the runtime:
 * both are compiled into the app, so the encoding is not part of a plugin bundle
 * and not a stable external format. Keep [encode] and the C parser in sync —
 * the unit tests in `:rdma-tests` pin the textual shape.
 */
object RdmaTypeDescFormat {

    /** Leaf descriptor: a scalar/class FQN with optional nullability and enum marks. */
    fun leaf(fqn: String, nullable: Boolean = false, isEnum: Boolean = false): String = buildString {
        append(fqn)
        if (nullable) append('?')
        if (isEnum) append('!')
    }

    /** `list:<elem>`. */
    fun list(elem: String): String = "list:$elem"

    /** `map:<key>:<value>`. */
    fun map(key: String, value: String): String = "map:$key:$value"
}
