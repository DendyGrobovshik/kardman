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

import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_nativeInvokeSlot
import io.github.dendygrobovshik.kardman.ios.cinterop.rdma_takeNativeError
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlin.concurrent.AtomicReference

/**
 * Hotfix dispatch slots (docs/design/runtime_rework.md §6), C ABI side. Every slotted
 * kernel callable has a generated `RdmaSlotCell` in its module package that registers a
 * setter for its `blockId` here by FQN; `RDMA.installSlot` fills it. Either may come
 * first: an install for a not-yet-registered callable is applied on registration.
 */
object RdmaSlots {
    private class State(
        val setters: Map<String, (Long) -> Unit>,
        val installed: Map<String, Long>,
    )

    // Copy-on-write: changes happen once per callable (registration) or per hotfix.
    private val state = AtomicReference(State(emptyMap(), emptyMap()))

    fun register(fqn: String, setter: (Long) -> Unit) {
        while (true) {
            val current = state.value
            val next = State(current.setters + (fqn to setter), current.installed)
            if (state.compareAndSet(current, next)) {
                current.installed[fqn]?.let(setter)
                return
            }
        }
    }

    /** Fills the slot of [fqn]; returns the previously installed block id (0 if none). */
    fun install(fqn: String, blockId: Long): Long {
        while (true) {
            val current = state.value
            val next = State(current.setters, current.installed + (fqn to blockId))
            if (state.compareAndSet(current, next)) {
                current.setters[fqn]?.invoke(blockId)
                return current.installed[fqn] ?: 0L
            }
        }
    }

    /** Runs the hotfix [blockId] with the callable's arguments, or returns [fallthrough]. */
    fun invokeNative(blockId: Long, args: Array<Any?>, paramDescs: String, resultDesc: String, fallthrough: Any): Any? =
        memScoped {
            val status = alloc<IntVar>()
            val handle = rdma_nativeInvokeSlot(
                blockId,
                StableRef.create(args).asCPointer(),
                paramDescs,
                resultDesc,
                status.ptr,
            )
            when (status.value) {
                SLOT_FALLTHROUGH -> fallthrough
                SLOT_ERROR -> throw RdmaException("Error", rdma_takeNativeError()?.toKString() ?: "hotfix failed", "", null)
                else -> handle?.let {
                    val ref = it.asStableRef<Any>()
                    val value = ref.get()
                    ref.dispose()
                    value
                }
            }
        }

    private const val SLOT_FALLTHROUGH = 1
    private const val SLOT_ERROR = 2
}

private val NOT_RUN = Any()

/**
 * Calls plugin block [blockId] and waits for its result (a lambda with a result): on the
 * Hermes thread directly, through the rendezvous from the UI thread, through the low queue
 * with a timeout from any other thread. Null when the runtime is not running yet.
 */
fun rdmaCallBlock(blockId: Long, args: Array<Any?>, paramDescs: String, resultDesc: String): Any? {
    val r = RdmaSlots.invokeNative(blockId, args, paramDescs, resultDesc, NOT_RUN)
    return if (r === NOT_RUN) null else r
}

/** `RDMA.installSlot` → Kotlin (registered in the C ABI function registry). */
fun rdma_installSlot(fqn: CPointer<ByteVar>?, blockId: Long): Long =
    if (fqn == null) 0L else RdmaSlots.install(fqn.toKString(), blockId)
