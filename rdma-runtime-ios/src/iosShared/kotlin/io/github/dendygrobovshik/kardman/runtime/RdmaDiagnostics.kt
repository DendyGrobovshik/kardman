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
@file:OptIn(ExperimentalNativeApi::class, ExperimentalComposeRuntimeApi::class)

package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.RecomposerInfo
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.runtime.tooling.setObserver
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_trace_report
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_trace_scope_event
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_trace_set_enabled
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode

/**
 * Debugging aids for kernel developers (docs/guide/threads_and_performance.md#recomposition-tracer);
 * the Android counterpart documents the recomposition tracer. Call [traceRecompositions] on
 * the main thread: the compositions register and report there too.
 */
@OptIn(ExperimentalForeignApi::class)
object RdmaDiagnostics {
    private const val ENTER = 0
    private const val EXIT = 1
    private const val INVALIDATED = 2
    private const val READ = 3
    private const val DISPOSED = 4

    /** Whether the recomposition tracer is on. */
    @Volatile
    var tracing: Boolean = false
        private set

    // Set while a plugin composable lambda invalidates the scopes that ran it (field report
    // #50): Compose reports that invalidation without a value.
    @Volatile
    internal var lambdaInvalidation = false

    private var watcher: Job? = null
    private val recomposers = mutableListOf<Pair<RecomposerInfo, CompositionObserverHandle>>()
    private val compositions = mutableListOf<Pair<ObservableComposition, CompositionObserverHandle>>()

    /**
     * Turns the recomposition tracer on (clearing what was collected) or off; it takes effect
     * for every composition, including the ones already running.
     */
    fun traceRecompositions(enabled: Boolean) {
        if (enabled == tracing) return
        tracing = enabled
        rdma_trace_set_enabled(if (enabled) 1 else 0)
        if (enabled) {
            watcher = CoroutineScope(Dispatchers.Unconfined).launch {
                Recomposer.runningRecomposers.collect { running -> observeRecomposers(running) }
            }
        } else {
            watcher?.cancel()
            watcher = null
            recomposers.forEach { it.second.dispose() }
            recomposers.clear()
            compositions.forEach { it.second.dispose() }
            compositions.clear()
        }
    }

    /** What the tracer collected since it was turned on: one entry per plugin composable. */
    fun recompositionReport(): String = rdma_trace_report()?.toKString() ?: ""

    /** Prints [recompositionReport], one line per entry, each prefixed with `RdmaTrace: `. */
    fun logRecompositionReport() {
        recompositionReport().lineSequence().filter { it.isNotEmpty() }.forEach { println("RdmaTrace: $it") }
    }

    private fun observeRecomposers(running: Set<RecomposerInfo>) {
        if (!tracing) return
        for (info in running) {
            if (recomposers.any { it.first === info }) continue
            info.observe(registrations)?.let { recomposers += info to it }
        }
    }

    private val registrations = object : CompositionRegistrationObserver {
        override fun onCompositionRegistered(composition: ObservableComposition) {
            if (tracing && compositions.none { it.first === composition }) {
                composition.setObserver(scopes)?.let { compositions += composition to it }
            }
        }

        override fun onCompositionUnregistered(composition: ObservableComposition) {
            val i = compositions.indexOfFirst { it.first === composition }
            if (i >= 0) compositions.removeAt(i).second.dispose()
        }
    }

    private val scopes = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) = rdma_trace_scope_event(ENTER, idOf(scope), 0, null)
        override fun onScopeExit(scope: RecomposeScope) = rdma_trace_scope_event(EXIT, idOf(scope), 0, null)
        override fun onScopeDisposed(scope: RecomposeScope) = rdma_trace_scope_event(DISPOSED, idOf(scope), 0, null)
        override fun onReadInScope(scope: RecomposeScope, value: Any) =
            rdma_trace_scope_event(READ, idOf(scope), idOf(value), null)

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) =
            rdma_trace_scope_event(INVALIDATED, idOf(scope), value?.let(::idOf) ?: 0, describe(value))
    }

    // Identity ids (never 0); a reused identity hash may rarely merge two objects.
    private fun idOf(o: Any): Long = (o.identityHashCode().toLong() and 0xffffffffL) or (1L shl 32)

    private fun describe(value: Any?): String {
        if (value == null) return if (lambdaInvalidation) "lambda update" else "invalidate()"
        val id = value.identityHashCode().toUInt().toString(16)
        if (value is State<*>) {
            val current = runCatching { Snapshot.withoutReadObservation { value.value } }.getOrNull()
            return "state@$id<${current?.let { it::class.simpleName } ?: "null"}>"
        }
        return "${value::class.simpleName}@$id"
    }
}
