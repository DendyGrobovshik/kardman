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

import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.IdentityHashMap

/**
 * Debugging aids for kernel developers (docs/guide/threads_and_performance.md#recomposition-tracer).
 *
 * The recomposition tracer counts how often each plugin composable runs and why: the first
 * run of its scope, a restart by its own invalidation (and which state or lambda invalidated
 * it), or a new run by its caller (and which parameters changed). It is off by default and
 * costs nothing while off: nothing is registered and no composition path checks for it.
 * While on it slows composition down, so compare runs and causes, not timings, against an
 * untraced build.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
object RdmaDiagnostics {
    private const val TAG = "RdmaTrace"
    private const val ENTER = 0
    private const val EXIT = 1
    private const val INVALIDATED = 2
    private const val READ = 3
    private const val DISPOSED = 4

    /** Whether the recomposition tracer is on. */
    @Volatile
    @JvmStatic
    var tracing: Boolean = false
        private set

    // Set while a plugin composable lambda invalidates the scopes that ran it (field report
    // #50): Compose reports that invalidation without a value.
    @Volatile
    internal var lambdaInvalidation = false

    private val lock = Any()
    private var watcher: Job? = null
    private val recomposers = IdentityHashMap<RecomposerInfo, CompositionObserverHandle>()
    private val compositions = IdentityHashMap<ObservableComposition, CompositionObserverHandle>()

    /**
     * Turns the recomposition tracer on (clearing what was collected) or off. Call it on the
     * main thread; it takes effect for every composition, including the ones already running.
     */
    @JvmStatic
    fun traceRecompositions(enabled: Boolean) {
        synchronized(lock) {
            if (enabled == tracing) return
            tracing = enabled
            nativeSetTracing(enabled)
            if (enabled) {
                watcher = CoroutineScope(Dispatchers.Unconfined).launch {
                    Recomposer.runningRecomposers.collect { running -> observeRecomposers(running) }
                }
            } else {
                watcher?.cancel()
                watcher = null
                recomposers.values.forEach { it.dispose() }
                recomposers.clear()
                compositions.values.forEach { it.dispose() }
                compositions.clear()
            }
        }
    }

    /** What the tracer collected since it was turned on: one entry per plugin composable. */
    @JvmStatic
    fun recompositionReport(): String = nativeReport()

    /** Writes [recompositionReport] to the log (tag `RdmaTrace`), one line per entry. */
    @JvmStatic
    fun logRecompositionReport() {
        recompositionReport().lineSequence().filter { it.isNotEmpty() }.forEach { Log.i(TAG, it) }
    }

    private fun observeRecomposers(running: Set<RecomposerInfo>) {
        synchronized(lock) {
            if (!tracing) return
            for (info in running) {
                if (info !in recomposers) recomposers[info] = info.observe(registrations)
            }
        }
    }

    private val registrations = object : CompositionRegistrationObserver {
        override fun onCompositionRegistered(composition: ObservableComposition) = synchronized(lock) {
            if (tracing && composition !in compositions) {
                compositions[composition] = composition.setObserver(scopes)
            }
        }

        override fun onCompositionUnregistered(composition: ObservableComposition) = synchronized(lock) {
            compositions.remove(composition)?.dispose()
            Unit
        }
    }

    private val scopes = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) = nativeScopeEvent(ENTER, idOf(scope), 0, null)
        override fun onScopeExit(scope: RecomposeScope) = nativeScopeEvent(EXIT, idOf(scope), 0, null)
        override fun onScopeDisposed(scope: RecomposeScope) = nativeScopeEvent(DISPOSED, idOf(scope), 0, null)
        override fun onReadInScope(scope: RecomposeScope, value: Any) =
            nativeScopeEvent(READ, idOf(scope), idOf(value), null)

        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) =
            nativeScopeEvent(INVALIDATED, idOf(scope), value?.let(::idOf) ?: 0, describe(value))
    }

    // Identity ids (never 0). Tracing data only: a reused identity hash may rarely merge two
    // objects, which is acceptable for counting.
    private fun idOf(o: Any): Long = (System.identityHashCode(o).toLong() and 0xffffffffL) or (1L shl 32)

    private fun describe(value: Any?): String {
        if (value == null) return if (lambdaInvalidation) "lambda update" else "invalidate()"
        val id = Integer.toHexString(System.identityHashCode(value))
        if (value is State<*>) {
            val current = runCatching { Snapshot.withoutReadObservation { value.value } }.getOrNull()
            return "state@$id<${current?.javaClass?.simpleName ?: "null"}>"
        }
        return "${value.javaClass.simpleName}@$id"
    }

    private external fun nativeSetTracing(enabled: Boolean)
    private external fun nativeScopeEvent(kind: Int, scope: Long, value: Long, desc: String?)
    private external fun nativeReport(): String
}
