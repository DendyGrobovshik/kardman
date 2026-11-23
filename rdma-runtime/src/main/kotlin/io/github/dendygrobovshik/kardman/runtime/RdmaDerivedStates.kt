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

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf

/**
 * Plugin `derivedStateOf` (docs/internals/compose_protocol.md#derived-states): a real derived
 * state whose calculation is the plugin block [blockId]. Compose records the states the
 * calculation reads and recomposes the readers only when the result changes. The block lives
 * as long as the state: the holder is released when the state becomes unreachable.
 */
object RdmaDerivedStates {
    @JvmStatic
    fun create(blockId: Long): State<Any?> {
        val ref = RdmaBlockRef(blockId)
        return derivedStateOf {
            val value = RdmaComposeHost.nativeInvokeDerived(ref.id)
            ref.touch()
            value
        }
    }
}
