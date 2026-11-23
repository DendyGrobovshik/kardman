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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeDerived
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_takeNativeError
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/**
 * Plugin `derivedStateOf` (docs/internals/compose_protocol.md#derived-states): a real derived
 * state whose calculation is the plugin block [blockId]. Compose records the states the
 * calculation reads and recomposes the readers only when the result changes. The block lives
 * as long as the state: the holder is released when the state is collected.
 */
object RdmaDerivedStates {
    fun create(blockId: Long): State<Any?> {
        val ref = RdmaBlockRef(blockId)
        return derivedStateOf {
            val value = calculate(ref.id)
            ref.touch()
            value
        }
    }

    private fun calculate(blockId: Long): Any? = memScoped {
        val status = alloc<IntVar>()
        val handle = rdma_nativeInvokeDerived(blockId, status.ptr)
        when (status.value) {
            ERROR -> throw RdmaException("Error", rdma_takeNativeError()?.toKString() ?: "derived state calculation failed", "", null)
            else -> handle?.let {
                val stable = it.asStableRef<Any>()
                val value = stable.get()
                stable.dispose()
                value
            }
        }
    }

    private const val ERROR = 2
}
