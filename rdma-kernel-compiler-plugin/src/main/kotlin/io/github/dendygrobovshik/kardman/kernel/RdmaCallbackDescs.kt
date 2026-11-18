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

import io.github.dendygrobovshik.kardman.types.RdmaType
import io.github.dendygrobovshik.kardman.types.RdmaTypeDescFormat
import io.github.dendygrobovshik.kardman.types.RdmaTypeRef

/**
 * Type descriptors of a plugin callback's parameters (docs/design/runtime_rework.md
 * §4.5): registered with the callback block, so the runtime marshals kernel →
 * plugin arguments by declared type (a `List`/`Map` argument becomes a collection
 * proxy) instead of by runtime shape. Comma-separated — descriptors never contain
 * commas.
 */
object RdmaCallbackDescs {
    /**
     * Recognizes `@RDMA` enums (this module's and its kernel dependencies'), whose leaf
     * carries the `!` mark: an entry crosses as its name string, not as an object.
     * Set by the kernel compiler plugin at the start of each module's generation.
     */
    @Volatile
    var isEnum: (String) -> Boolean = { false }

    fun of(type: RdmaTypeRef): String = when (val t = type.type) {
        is RdmaType.Primitive -> RdmaTypeDescFormat.leaf(t.fqn, type.nullable)
        is RdmaType.Ref -> RdmaTypeDescFormat.leaf(t.fqn, type.nullable, isEnum = isEnum(t.fqn))
        is RdmaType.ListType -> RdmaTypeDescFormat.list(of(t.element))
        is RdmaType.MapType -> RdmaTypeDescFormat.map(of(t.key), of(t.value))
        is RdmaType.UnitType -> "kotlin.Unit"
        is RdmaType.FunctionType -> "kotlin.Any"
    }

    fun joined(params: List<RdmaTypeRef>): String = params.joinToString(",") { of(it) }
}
