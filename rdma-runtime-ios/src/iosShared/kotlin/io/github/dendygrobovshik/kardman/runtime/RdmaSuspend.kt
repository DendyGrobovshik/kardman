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

    fun completeResult(completionId: Long, value: Any?) {
        RdmaComposeHost.nativeInvokeContinuation(completionId, value)
    }

    fun completeError(completionId: Long, throwable: Throwable) {
        RdmaComposeHost.nativeInvokeContinuationError(completionId, throwable)
    }
}
