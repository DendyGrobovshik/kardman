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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Kernel-side host for the plugin's `SideEffect` primitive. Composed by the generic
 * runtime (via `RDMA.sideEffect` in the C++ bridge); the JS block identified by
 * [blockId] is invoked after every successful composition through the standard
 * callback channel.
 */
@Composable
fun sideEffect(blockId: Long) {
    SideEffect {
        RdmaComposeHost.nativeInvokeCallback(blockId, emptyArray<Any?>())
    }
}

/**
 * Kernel-side `RememberObserver` that drives the plugin's `DisposableEffect`.
 *
 * The observer owns the kernel `remember` slot (so the real Compose runtime calls
 * its lifecycle), while the effect body and its returned dispose callback stay in
 * the plugin. The effect body is single-use: it is invoked once in
 * [onRemembered] and its registered JS function is dropped immediately afterwards;
 * the returned result object is disposed in [onForgotten]. [onAbandoned] (a
 * composition that never committed) only releases the never-invoked block.
 */
class RdmaDisposableEffectObserver(val effectBlockId: Long) : RememberObserver {
    private var resultId = 0L

    override fun onRemembered() {
        resultId = RdmaComposeHost.nativeInvokeEffectBody(effectBlockId)
    }

    override fun onForgotten() {
        if (resultId != 0L) {
            RdmaComposeHost.nativeInvokeDispose(resultId)
            resultId = 0L
        }
    }

    override fun onAbandoned() {
        RdmaComposeHost.nativeInvokeFreeBlock(effectBlockId)
    }
}

/**
 * Kernel-side host for the plugin's `DisposableEffect` primitive, composed by the
 * generic runtime (via `RDMA.disposableEffect` in the C++ bridge).
 *
 * [keys] drive the kernel `remember`, exactly like the host `DisposableEffect`;
 * the returned Boolean tells the bridge whether [effectBlockId] was adopted by a
 * freshly created observer (so the bridge can release a block registered on a
 * recomposition that reused the previous observer).
 */
@Composable
fun disposableEffect(keys: Array<Any?>, effectBlockId: Long): Boolean {
    val observer = remember(*keys) { RdmaDisposableEffectObserver(effectBlockId) }
    return observer.effectBlockId == effectBlockId
}
