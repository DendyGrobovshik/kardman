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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlin.jvm.functions.Function2
import kotlin.jvm.functions.Function3

object RdmaComposeHost {
    /**
     * Renders the plugin component [name] (`"<pluginId>/<component>"`) with [args], typed by
     * the component's declared parameters. Any number of call sites may render the same
     * component, each with its own state. A call site keeps the version it first rendered
     * until it leaves the composition; one that is waiting for a component not registered
     * yet composes as soon as it is.
     */
    @Composable
    fun Component(name: String, vararg args: Any?) {
        val slot = remember(name) { ComponentSlot() }
        if (slot.ref == null) {
            componentsVersion.intValue // recompose when a component is registered
            val id = nativeComponentBlock(name)
            if (id != 0L) slot.ref = RdmaBlockRef(id)
        }
        val ref = slot.ref ?: return
        nativeInvokeComponent(ref.id, arrayOf(*args), currentComposer)
        ref.touch()
    }

    private val componentsVersion = mutableIntStateOf(0)

    /** Called by the runtime (Hermes thread) when a plugin registers a component. */
    @JvmStatic
    fun onComponentsChanged() {
        componentsVersion.intValue++
    }

    private class ComponentSlot : RememberObserver {
        var ref: RdmaBlockRef? = null
        override fun onRemembered() {}
        override fun onForgotten() { ref?.release() }
        override fun onAbandoned() { ref?.release() }
    }

    private external fun nativeComponentBlock(name: String): Long
    private external fun nativeInvokeComponent(blockId: Long, args: Array<Any?>, composer: Composer)
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
/**
 * A plugin JS object as a Compose key (`changed`, effect keys): its identity id, the same
 * for the same object, never reused (docs/internals/compose_protocol.md#object-keys).
 */
data class JsIdentityKey(val id: Long)

class JsValueHolder(val id: Long) : RememberObserver {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun release() {
        if (released.compareAndSet(false, true)) RdmaComposeHost.nativeReleaseJsValue(id)
    }
    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}
