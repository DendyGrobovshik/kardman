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
package io.github.dendygrobovshik.kardman.runtime

/**
 * The exception the host throws when a plugin exception crosses the runtime
 * boundary. The original class cannot be recreated on the host side, so its name
 * is preserved as [originalExceptionType]; the plugin's stack is carried verbatim
 * in [remoteStack]; each further boundary crossing nests another [RdmaException]
 * as [cause], producing an end-to-end stack. See docs/design/exception_propagation.md.
 */
class RdmaException(
    val originalExceptionType: String,
    message: String,
    val remoteStack: String,
    cause: RdmaException?,
) : RuntimeException(message, cause) {
    override fun toString(): String = buildString {
        append("io.github.dendygrobovshik.kardman.runtime.RdmaException: ").append(message)
        if (remoteStack.isNotEmpty()) {
            append("\n--- remote stack (").append(originalExceptionType).append(") ---")
            append('\n').append(remoteStack)
        }
    }
}

/** Thrown by the bundle loader when a bundle fails to compile or load (see §7). */
class BundleLoadException(message: String, cause: RdmaException?) : RuntimeException(message, cause)
