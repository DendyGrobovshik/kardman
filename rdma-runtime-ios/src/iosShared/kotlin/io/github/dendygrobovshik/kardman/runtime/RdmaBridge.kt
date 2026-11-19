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
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_eval_file
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_eval_files
import kotlin.concurrent.AtomicReference
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_is_ready
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_content_version
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_start
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_set_uncaught_handler
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_takeEvalError
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.toKString

/**
 * iOS runtime bootstrap. Mirrors the Android `RdmaBridge` but drives the Hermes
 * thread through the C-ABI entry points (`rdma_start` / `rdma_is_ready` /
 * `rdma_eval_bytes`) instead of JNI.
 */
object RdmaBridge {
    fun nativeInit() {
        rdma_start(null)
    }

    fun nativeIsReady(): Boolean {
        if (rdma_is_ready() == 0) return false
        rdma_takeEvalError()?.let { ptr ->
            throw BundleLoadException(ptr.toKString(), null)
        }
        return true
    }

    fun nativeContentVersion(): Int = rdma_content_version()

    fun nativeEvalBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            rdma_eval_bytes(pinned.addressOf(0), bytes.size)
        }
    }

    fun nativeEvalFile(path: String) {
        rdma_eval_file(path)
    }

    private val staged = AtomicReference<List<String>>(emptyList())

    /**
     * Stages a downloaded bundle file (a plugin or a polyfill) for the next update point
     * (compatibility.md §8.5). Nothing changes until [applyUpdates]: plugins on screen keep
     * running the code they started with. Stage polyfills before the plugins that need them.
     * Any thread.
     */
    fun stageUpdate(path: String) {
        while (true) {
            val cur = staged.value
            if (path in cur) return
            if (staged.compareAndSet(cur, cur + path)) return
        }
    }

    /** True when bundles are waiting for [applyUpdates]. */
    fun hasStagedUpdates(): Boolean = staged.value.isNotEmpty()

    /**
     * An update point (compatibility.md §8.5): evaluates every staged bundle, in staging order,
     * as one step on the plugin thread, so nothing runs against a half-applied update. Every
     * place showing a component of an updated plugin is recreated with the new version (its
     * `remember` state starts over), and updated kernel functions switch for every caller. Call
     * it where the user does not watch plugin content: between screens, on returning from the
     * background. A cold start is the other update point: evaluate the newest bundles before
     * the first frame. Returns false when nothing was staged. Any thread.
     */
    fun applyUpdates(): Boolean {
        val paths = staged.getAndSet(emptyList())
        if (paths.isEmpty()) return false
        rdma_eval_files(paths.joinToString("\n"))
        return true
    }

    /**
     * Installs the host-side global uncaught-exception handler (design §6).
     * Must be called before [nativeInit] so the handler is visible to the Hermes thread.
     */
    fun setUncaughtExceptionHandler(handler: (String, RdmaException) -> Unit) {
        rdmaGlobalUncaughtHandler = handler
        rdma_set_uncaught_handler()
    }
}
