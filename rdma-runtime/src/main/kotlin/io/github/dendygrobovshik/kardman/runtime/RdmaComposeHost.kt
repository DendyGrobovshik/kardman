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
import androidx.compose.runtime.Composer
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.currentComposer
import kotlin.jvm.functions.Function2
import kotlin.jvm.functions.Function3

object RdmaComposeHost {
    // Call this from the host composition (e.g. androidApp `setContent`).
    @Composable
    fun Content() {
        nativeInvokeContent(currentComposer)
    }

    private external fun nativeInvokeContent(composer: Composer)
    external fun nativeInvokeScopeBlock(blockId: Long, composer: Composer, changed: Int)
    external fun nativeInvokeScopeBlock1(blockId: Long, composer: Composer, changed: Int, p1: Int)
    external fun nativeInvokeCallback(blockId: Long, args: Array<Any?>)
    external fun nativeInvokeLambda(id: Long, args: Array<Any?>): Any?

    // DisposableEffect bridge. `nativeInvokeEffectBody` runs the single-use effect
    // body on the Hermes thread, stores its returned result object, and returns the
    // result id; `nativeInvokeDispose` calls `.dispose()` on that result;
    // `nativeInvokeFreeBlock` releases a block that was registered but never invoked
    // (abandoned composition).
    external fun nativeInvokeEffectBody(blockId: Long): Long
    external fun nativeInvokeDispose(resultId: Long)
    external fun nativeInvokeFreeBlock(blockId: Long)

    // Suspend completion. `nativeInvokeContinuation` marshals [value] by the type
    // descriptor registered for [completionId] (see RdmaSuspend); the error variant
    // serializes [throwable] and resumes the plugin continuation with the exception.
    external fun nativeInvokeContinuation(completionId: Long, value: Any?)
    external fun nativeInvokeContinuationError(completionId: Long, throwable: Throwable)

    // Block holders (docs/design/runtime_rework.md §4). Callable from any thread.
    external fun nativeRetainBlock(blockId: Long)
    external fun nativeReleaseBlock(blockId: Long)
    external fun nativeSetBlockParamDescs(blockId: Long, descs: String)
    external fun nativeBlockCount(): Int

    // Releases a remembered plugin JS value (see [JsValueHolder]).
    external fun nativeReleaseJsValue(valueId: Long)
}

// Recompose-scope block: held by the recompose scope it was installed on; the JS block
// is released when the scope drops it (finalization of this holder).
class ComposerScopeBlock(blockId: Long) : Function2<Composer, Int, Unit> {
    private val ref = RdmaBlockRef(blockId)
    override fun invoke(composer: Composer, changed: Int) {
        RdmaComposeHost.nativeInvokeScopeBlock(ref.id, composer, changed)
        ref.touch()
    }
}

class ComposerScopeBlock1(blockId: Long) : Function3<Int, Composer, Int, Unit> {
    private val ref = RdmaBlockRef(blockId)
    override fun invoke(p1: Int, composer: Composer, changed: Int) {
        RdmaComposeHost.nativeInvokeScopeBlock1(ref.id, composer, changed, p1)
        ref.touch()
    }
}

/**
 * A plugin JS value stored in a kernel `remember` slot. Compose reports when the slot
 * forgets it, and the JS value is released then (docs/design/runtime_rework.md §4).
 */
class JsValueHolder(val id: Long) : RememberObserver {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun release() {
        if (released.compareAndSet(false, true)) RdmaComposeHost.nativeReleaseJsValue(id)
    }
    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}
