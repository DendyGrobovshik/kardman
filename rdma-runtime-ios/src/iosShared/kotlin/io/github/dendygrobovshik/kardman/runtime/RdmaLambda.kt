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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.dendygrobovshik.kardman.runtime

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get

// Kernel wrappers of a plugin lambda passed to an `@RDMA` function: each one is a
// holder of the JS block (docs/design/runtime_rework.md §4).
/** A kernel lambda that calls a plugin block: the runtime hands the plugin its own function back. */
interface RdmaBlockFunction {
    val blockId: Long
}

// A lambda without a result posts the call (fire-and-forget); one with a result waits for
// the value on the Hermes thread, routed like a hotfix slot (docs/internals/blocks_and_lambdas.md).
internal fun rdmaInvokeBlock(ref: RdmaBlockRef, args: Array<Any?>, paramDescs: String, resultDesc: String): Any? {
    val result = if (resultDesc.isEmpty() || resultDesc == "kotlin.Unit") {
        RdmaComposeHost.nativeInvokeLambda(ref.id, args)
    } else {
        rdmaCallBlock(ref.id, args, paramDescs, resultDesc)
    }
    ref.touch()
    return result
}

class RdmaFunction0<R>(id: Long, private val paramDescs: String = "", private val resultDesc: String = "") : () -> R, RdmaBlockFunction {
    private val ref = RdmaBlockRef(id).withParamDescs(paramDescs)
    override val blockId: Long get() = ref.id
    // Two wrappers of one plugin block are equal (see the Android runtime).
    override fun equals(other: Any?): Boolean = other is RdmaFunction0<*> && other.blockId == blockId
    override fun hashCode(): Int = blockId.hashCode()
    override fun invoke(): R = rdmaInvokeBlock(ref, emptyArray(), paramDescs, resultDesc) as R
}

class RdmaFunction1<P1, R>(id: Long, private val paramDescs: String = "", private val resultDesc: String = "") : (P1) -> R, RdmaBlockFunction {
    private val ref = RdmaBlockRef(id).withParamDescs(paramDescs)
    override val blockId: Long get() = ref.id
    // Two wrappers of one plugin block are equal (see the Android runtime).
    override fun equals(other: Any?): Boolean = other is RdmaFunction1<*, *> && other.blockId == blockId
    override fun hashCode(): Int = blockId.hashCode()
    override fun invoke(p1: P1): R = rdmaInvokeBlock(ref, arrayOf(p1 as Any?), paramDescs, resultDesc) as R
}

class RdmaFunction2<P1, P2, R>(id: Long, private val paramDescs: String = "", private val resultDesc: String = "") : (P1, P2) -> R, RdmaBlockFunction {
    private val ref = RdmaBlockRef(id).withParamDescs(paramDescs)
    override val blockId: Long get() = ref.id
    // Two wrappers of one plugin block are equal (see the Android runtime).
    override fun equals(other: Any?): Boolean = other is RdmaFunction2<*, *, *> && other.blockId == blockId
    override fun hashCode(): Int = blockId.hashCode()
    override fun invoke(p1: P1, p2: P2): R = rdmaInvokeBlock(ref, arrayOf(p1 as Any?, p2 as Any?), paramDescs, resultDesc) as R
}

class RdmaFunction3<P1, P2, P3, R>(id: Long, private val paramDescs: String = "", private val resultDesc: String = "") : (P1, P2, P3) -> R, RdmaBlockFunction {
    private val ref = RdmaBlockRef(id).withParamDescs(paramDescs)
    override val blockId: Long get() = ref.id
    // Two wrappers of one plugin block are equal (see the Android runtime).
    override fun equals(other: Any?): Boolean = other is RdmaFunction3<*, *, *, *> && other.blockId == blockId
    override fun hashCode(): Int = blockId.hashCode()
    override fun invoke(p1: P1, p2: P2, p3: P3): R =
        rdmaInvokeBlock(ref, arrayOf(p1 as Any?, p2 as Any?, p3 as Any?), paramDescs, resultDesc) as R
}

// --- kernel lambdas called from JS (registered in the C ABI function registry) ---

/** The plugin block behind a kernel lambda, or 0 for a native lambda. */
fun rdma_functionBlockId(handle: COpaquePointer?): Long =
    (handle?.asStableRef<Any>()?.get() as? RdmaBlockFunction)?.blockId ?: 0L

/**
 * Calls the kernel lambda [handle] with [argc] argument handles (consumed); returns a new
 * handle of the result. An exception is recorded for the C++ caller (`checkKotlinError`).
 */
fun rdma_invokeFunction(handle: COpaquePointer?, argc: Int, argv: CPointer<COpaquePointerVar>?): COpaquePointer? =
    rdmaCaptureError(null) {
        val fn = handle!!.asStableRef<Any>().get()
        val args = Array<Any?>(argc) { i ->
            argv!![i]?.let { h ->
                val ref = h.asStableRef<Any>()
                val value = ref.get()
                ref.dispose()
                value
            }
        }
        @Suppress("UNCHECKED_CAST")
        val result: Any? = when (argc) {
            0 -> (fn as () -> Any?)()
            1 -> (fn as (Any?) -> Any?)(args[0])
            2 -> (fn as (Any?, Any?) -> Any?)(args[0], args[1])
            3 -> (fn as (Any?, Any?, Any?) -> Any?)(args[0], args[1], args[2])
            else -> error("a kernel lambda with $argc parameters cannot cross (at most 3)")
        }
        result?.let { StableRef.create(it).asCPointer() }
    }
