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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Framework-owned scope used to launch `@RDMA` suspend bodies on iOS. Mirrors the
 * Android `RdmaSuspend`; the body itself chooses its real dispatcher via
 * `withContext(...)`.
 */
object RdmaSuspend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }

    // In-flight calls by completion id, so the plugin can cancel one (its coroutine was
    // cancelled): the kernel call is cancelled too (docs/internals/suspend.md).
    private val jobs = kotlin.concurrent.AtomicReference<Map<Long, Job>>(emptyMap())

    private fun update(change: (Map<Long, Job>) -> Map<Long, Job>) {
        while (true) {
            val current = jobs.value
            if (jobs.compareAndSet(current, change(current))) return
        }
    }

    fun launch(completionId: Long, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        update { it + (completionId to job) }
        job.invokeOnCompletion { update { m -> m - completionId } }
        job.start()
    }

    /** Called by the runtime when the plugin cancels the coroutine waiting for [completionId]. */
    fun cancel(completionId: Long) {
        val job = jobs.value[completionId]
        update { it - completionId }
        job?.cancel()
    }

    fun completeResult(completionId: Long, value: Any?) {
        RdmaComposeHost.nativeInvokeContinuation(completionId, value)
    }

    fun completeError(completionId: Long, throwable: Throwable) {
        RdmaComposeHost.nativeInvokeContinuationError(completionId, throwable)
    }
}

/** `rdma_cancelSuspend` (registered in the C ABI function registry). */
fun rdma_cancelSuspend(completionId: Long) = RdmaSuspend.cancel(completionId)
