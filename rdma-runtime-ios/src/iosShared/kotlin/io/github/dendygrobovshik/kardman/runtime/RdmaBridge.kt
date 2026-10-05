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

import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_eval_bytes
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_is_ready
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_start
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * iOS runtime bootstrap. Mirrors the Android `RdmaBridge` but drives the Hermes
 * thread through the C-ABI entry points (`rdma_start` / `rdma_is_ready` /
 * `rdma_eval_bytes`) instead of JNI.
 */
object RdmaBridge {
    fun nativeInit() {
        rdma_start(null)
    }

    fun nativeIsReady(): Boolean = rdma_is_ready() != 0

    fun nativeEvalBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            rdma_eval_bytes(pinned.addressOf(0), bytes.size)
        }
    }
}
