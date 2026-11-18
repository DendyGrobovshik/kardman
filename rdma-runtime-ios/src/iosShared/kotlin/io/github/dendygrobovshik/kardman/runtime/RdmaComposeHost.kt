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
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
)

package io.github.dendygrobovshik.kardman.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeCallback
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeContent
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeContinuation
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeContinuationError
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeDispose
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeEffectBody
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeFreeBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeBlockCount
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeReleaseBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeReleaseJsValue
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeRetainBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeSetBlockParamDescs
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeLambda
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeScopeBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeScopeBlock1
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_registerFunction
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_takeNativeError
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_vtableDispatch
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.value
import kotlinx.cinterop.ptr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.alloc
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.toKString

/**
 * iOS (Kotlin/Native) host for the compose bridge. Mirrors the Android
 * `RdmaComposeHost` but crosses the `Composer` as a StableRef handle; the C ABI
 * entry points are the `rdma_nativeInvoke*` functions imported via cinterop.
 */
object RdmaComposeHost {
    // Call this from the host composition (e.g. iosApp `setContent`).
    @Composable
    fun Content() {
        val prev = currentComposerHolder
        currentComposerHolder = currentComposer
        try {
            rdma_nativeInvokeContent()
            throwIfNativeError()
        } finally {
            currentComposerHolder = prev
        }
    }

    fun nativeInvokeScopeBlock(blockId: Long, composer: Composer, changed: Int) {
        val prev = currentComposerHolder
        currentComposerHolder = composer
        try {
            rdma_nativeInvokeScopeBlock(blockId, changed)
            throwIfNativeError()
        } finally {
            currentComposerHolder = prev
        }
    }

    fun nativeInvokeScopeBlock1(blockId: Long, composer: Composer, changed: Int, p1: Int) {
        val prev = currentComposerHolder
        currentComposerHolder = composer
        try {
            rdma_nativeInvokeScopeBlock1(blockId, changed, p1)
            throwIfNativeError()
        } finally {
            currentComposerHolder = prev
        }
    }

    fun nativeInvokeCallback(blockId: Long, args: Array<Any?>) {
        // The C++ side disposes the args handle after the async post.
        rdma_nativeInvokeCallback(blockId, StableRef.create(args).asCPointer())
    }

    fun nativeInvokeLambda(id: Long, args: Array<Any?>): Any? {
        // The C++ side disposes the args handle after the async post.
        rdma_nativeInvokeLambda(id, StableRef.create(args).asCPointer())
        return null
    }

    fun nativeInvokeEffectBody(blockId: Long): Long = rdma_nativeInvokeEffectBody(blockId)
    fun nativeInvokeDispose(resultId: Long) = rdma_nativeInvokeDispose(resultId)
    fun nativeInvokeFreeBlock(blockId: Long) = rdma_nativeInvokeFreeBlock(blockId)

    // Block holders (docs/design/runtime_rework.md §4). Callable from any thread.
    fun nativeRetainBlock(blockId: Long) = rdma_nativeRetainBlock(blockId)
    fun nativeReleaseBlock(blockId: Long) = rdma_nativeReleaseBlock(blockId)
    fun nativeSetBlockParamDescs(blockId: Long, descs: String) = rdma_nativeSetBlockParamDescs(blockId, descs)
    fun nativeBlockCount(): Int = rdma_nativeBlockCount()
    fun nativeReleaseJsValue(valueId: Long) = rdma_nativeReleaseJsValue(valueId)

    fun nativeInvokeContinuation(completionId: Long, value: Any?) {
        rdma_nativeInvokeContinuation(completionId, value?.let { StableRef.create(it).asCPointer() })
    }

    fun nativeInvokeContinuationError(completionId: Long, throwable: Throwable) {
        rdma_nativeInvokeContinuationError(completionId, serializeThrowable(throwable))
    }

    /**
     * Length-prefixed serialization of the full cause chain, mirrored by
     * `parseSerializedThrowable` in `RdmaComposeCAbi.cpp`. Each level is
     * `<typeLen>:<type><msgLen>:<msg><stackLen>:<stack><hasCause>` where
     * `hasCause` is '1' (a nested level follows) or '0'.
     */
    private fun serializeThrowable(throwable: Throwable): String {
        val sb = StringBuilder()
        fun write(level: Throwable) {
            val type = level::class.simpleName ?: "Throwable"
            val message = level.message ?: ""
            val stack = level.stackTraceToString()
            sb.append(type.length).append(':').append(type)
            sb.append(message.length).append(':').append(message)
            sb.append(stack.length).append(':').append(stack)
            val cause = level.cause
            if (cause != null) {
                sb.append('1')
                write(cause)
            } else {
                sb.append('0')
            }
        }
        write(throwable)
        return sb.toString()
    }

    /**
     * Vtable dispatch (native actual): runs the plugin override on the Hermes thread
     * (runtime_rework §6.4); `fallthrough` when the method is not overridden; a JS
     * exception or a timeout is thrown as `RdmaException` (§2.8).
     */
    fun rdmaVtableDispatch(
        vtablePtr: Long,
        vtableId: Int,
        args: Array<Any?>,
        paramDescs: String,
        resultDesc: String,
        fallthrough: Any,
    ): Any? = memScoped {
        val status = alloc<IntVar>()
        val handle = rdma_vtableDispatch(vtablePtr, vtableId, StableRef.create(args).asCPointer(), paramDescs, resultDesc, status.ptr)
        when (status.value) {
            1 -> fallthrough
            2 -> throw RdmaException("Error", rdma_takeNativeError()?.toKString() ?: "override failed", "", null)
            else -> handle?.let {
                val ref = it.asStableRef<Any>()
                val value = ref.get()
                ref.dispose()
                value
            }
        }
    }
}

// Recompose-scope block: held by the recompose scope it was installed on; the JS block
// is released when the scope drops it (finalization of this holder).
class ComposerScopeBlock(blockId: Long) : (Composer, Int) -> Unit {
    private val ref = RdmaBlockRef(blockId)
    override fun invoke(composer: Composer, changed: Int) {
        RdmaComposeHost.nativeInvokeScopeBlock(ref.id, composer, changed)
        ref.touch()
    }
}

class ComposerScopeBlock1(blockId: Long) : (Int, Composer, Int) -> Unit {
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
class JsValueHolder(val id: Long) : androidx.compose.runtime.RememberObserver {
    private val released = kotlin.concurrent.AtomicInt(0)
    private fun release() {
        if (released.compareAndSet(0, 1)) RdmaComposeHost.nativeReleaseJsValue(id)
    }
    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}

// The currently-composing Composer, held as a raw object reference for the C ABI.
// The widget wrappers and the composer proxy methods read this instead of
// receiving the composer as an argument.
private var currentComposerHolder: Composer? = null

/** Returns the currently-composing Composer (or null outside a composition). */
fun rdmaGetCurrentComposer(): Composer? = currentComposerHolder

private fun throwIfNativeError() {
    rdma_takeNativeError()?.let { ptr ->
        throw RdmaException("Error", ptr.toKString(), "", null)
    }
}

/**
 * Registers a C-compatible function pointer under [name] in the C++ registry so
 * the C ABI backend can call it by address (replaces the former `@CName` symbol
 * linkage, which does not export C symbols from a Kotlin/Native framework).
 */
fun rdmaRegisterFunction(name: String, fn: COpaquePointer) {
    rdma_registerFunction(name, fn)
}

// Vtable side-channel for the C ABI backend. The IR-injected `__vtable` field can't be
// referenced from generated Kotlin source (it is created after the frontend resolves
// references), so the generated `*_setVtable` @CName function stores the vtable pointer
// here, keyed by the object, and the IR-injected dispatch reads it back.
private val vtables = mutableMapOf<Any, Long>()

fun rdmaVtableSet(obj: Any, ptr: Long) {
    vtables[obj] = ptr
}

fun rdmaVtableGet(obj: Any): Long = vtables[obj] ?: 0L
