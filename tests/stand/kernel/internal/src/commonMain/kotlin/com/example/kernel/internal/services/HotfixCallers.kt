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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// Background-thread callers of the hotfix fixture (docs/design/runtime_rework.md §6.4,
// D5 = B), used by plugin-lifecycle.sh. Kept apart from Hotfix.kt so that file's polyfill
// needs no coroutine imports.

/**
 * Calls `hotfixTarget` on a `Dispatchers.Default` thread — neither the Hermes nor the UI
 * thread — and hands the result (or the error) to the plugin.
 */
@OptIn(DelicateCoroutinesApi::class)
@RDMA
fun callHotfixTargetInBackground(tag: String, onResult: (String) -> Unit) {
    GlobalScope.launch(Dispatchers.Default) {
        val result = try {
            hotfixTarget(tag)
        } catch (e: Throwable) {
            "error:" + (e.message ?: e::class.simpleName)
        }
        onResult(result)
    }
}

/**
 * Keeps the calling (plugin) thread busy for [ms] milliseconds. Test-only: a synchronous
 * `@RDMA` function must never block like this (user guide, "Threads").
 */
@RDMA
fun holdPluginThread(ms: Int) {
    val end = TimeSource.Monotonic.markNow() + ms.milliseconds
    while (end.hasNotPassedNow()) {
        // busy wait: no platform sleep in common code
    }
}
