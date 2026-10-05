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
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeDispose
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeEffectBody
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeFreeBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeLambda
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeScopeBlock
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_registerFunction
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_vtableDispatch
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef

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
        } finally {
            currentComposerHolder = prev
        }
    }

    fun nativeInvokeScopeBlock(blockId: Long, composer: Composer, changed: Int) {
        val prev = currentComposerHolder
        currentComposerHolder = composer
        try {
            rdma_nativeInvokeScopeBlock(blockId, changed)
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

    /** Vtable dispatch (native actual): calls the JS override, returns the boxed String. */
    fun rdmaVtableDispatch(vtablePtr: Long, vtableId: Int): Any? {
        val handle: COpaquePointer = rdma_vtableDispatch(vtablePtr, vtableId) ?: return null
        val ref = handle.asStableRef<Any>()
        val value = ref.get()
        ref.dispose()
        return value
    }
}

class ComposerScopeBlock(private val blockId: Long) : (Composer, Int) -> Unit {
    override fun invoke(composer: Composer, changed: Int) {
        RdmaComposeHost.nativeInvokeScopeBlock(blockId, composer, changed)
    }
}

class JsValueHolder(val id: Long)

// The currently-composing Composer, held as a raw object reference for the C ABI.
// The widget wrappers and the composer proxy methods read this instead of
// receiving the composer as an argument.
private var currentComposerHolder: Composer? = null

/** Returns the currently-composing Composer (or null outside a composition). */
fun rdmaGetCurrentComposer(): Composer? = currentComposerHolder

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
