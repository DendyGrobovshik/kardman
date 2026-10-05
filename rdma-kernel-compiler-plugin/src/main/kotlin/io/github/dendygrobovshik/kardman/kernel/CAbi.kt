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
 * Shared C-ABI vocabulary for the iOS backend: symbol naming and the C type each
 * bridgeable Kotlin type maps to on both sides of the boundary.
 *
 * The C ABI is the direct-call equivalent of JNI:
 *   - `jobject`  -> `void*`          (a StableRef handle)
 *   - primitives -> their C scalar   (int32_t / int64_t / double / float / bool)
 *   - `String`   -> `const char*`    (UTF-8, null-terminated; callers copy the
 *                                     returned pointer before the next allocation)
 *   - `List<T>`  -> `void*`          (StableRef handle to a Kotlin List)
 *   - lambda     -> `int64_t`        (a block id, wrapped into a Kotlin lambda)
 */
object CAbi {
    /** The C symbol prefix for this module (flat C namespace needs the module id). */
    fun prefix(moduleId: String): String = if (moduleId.isEmpty()) "rdma_" else "rdma_${moduleId}_"

    fun classCtor(moduleId: String, className: String): String =
        prefix(moduleId) + className + "_new"

    fun classMember(moduleId: String, className: String, member: String): String =
        prefix(moduleId) + className + "_" + member

    fun topLevelFn(moduleId: String, name: String): String = prefix(moduleId) + name

    fun typeIdOf(moduleId: String): String = prefix(moduleId) + "typeIdOf"

    /** C type descriptors for a single bridgeable type. */
    data class CType(
        val kotlinType: String,
        val cppType: String,
        val isHandle: Boolean = false,
    )

    private val primitives = mapOf(
        "kotlin.Int" to CType("Int", "int32_t"),
        "kotlin.Long" to CType("Long", "int64_t"),
        "kotlin.Float" to CType("Float", "float"),
        "kotlin.Double" to CType("Double", "double"),
        "kotlin.Boolean" to CType("Boolean", "bool"),
        "kotlin.String" to CType("String", "const char*"),
        "kotlin.Unit" to CType("Unit", "void"),
    )

    /** Maps a class-member type name (`kotlin.Int`, `com.foo.Bar`, …) to its C type. */
    fun forMemberType(typeName: String): CType {
        primitives[typeName]?.let { return it }
        if (typeName.startsWith("kotlin.Function")) {
            // A lambda crosses as a block id; the generated @CName fn wraps it into
            // the concrete Kotlin function type.
            return CType("Long", "int64_t")
        }
        return CType("COpaquePointer", "void*", isHandle = true) // @RDMA ref or List
    }

    fun isPrimitive(typeName: String): Boolean = typeName in primitives
    fun isVoid(typeName: String): Boolean = typeName == "kotlin.Unit"
}
