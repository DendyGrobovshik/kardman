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
package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.RDMA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

// Cancellation of a kernel `suspend` call (docs/internals/suspend.md): the plugin cancels
// the coroutine waiting for `waitLong`, and the kernel coroutine is cancelled as well.

@Volatile
private var longWaitCancelled = false

@RDMA
suspend fun waitLong(ms: Int): String {
    try {
        delay(ms.toLong())
        return "finished"
    } catch (e: CancellationException) {
        longWaitCancelled = true
        throw e
    }
}

@RDMA
fun wasLongWaitCancelled(): Boolean = longWaitCancelled
