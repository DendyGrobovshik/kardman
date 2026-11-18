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
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kernel-side holder of a plugin JS block (docs/design/runtime_rework.md §4).
 *
 * The block stays alive while at least one holder does. A holder retains the block
 * on construction and releases it exactly once: deterministically when Compose
 * forgets it (`RememberObserver`), or when it becomes unreachable (a phantom
 * reference; `java.lang.ref.Cleaner` needs API 33, the runtime supports 24).
 * The release reaches the JS thread through the same FIFO queue as the block's
 * invocations, so an invocation posted earlier always runs first.
 */
class RdmaBlockRef(val id: Long) : RememberObserver {
    private val token = Token(id)

    @Volatile
    private var touched = false

    init {
        RdmaComposeHost.nativeRetainBlock(id)
        Finalizer.track(this, token)
    }

    /** Parameter type descriptors of a callback block (comma-separated). */
    fun withParamDescs(descs: String): RdmaBlockRef {
        if (descs.isNotEmpty()) RdmaComposeHost.nativeSetBlockParamDescs(id, descs)
        return this
    }

    /**
     * Keeps this holder reachable up to the call site: an invocation reads [id], posts
     * the call and then touches the holder, so the holder cannot be finalized (and the
     * release posted) before the invocation is queued.
     */
    fun touch() {
        touched = !touched
    }

    fun release() = token.release()

    override fun onRemembered() {}
    override fun onForgotten() = release()
    override fun onAbandoned() = release()

    private class Token(val id: Long) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) RdmaComposeHost.nativeReleaseBlock(id)
        }
    }

    private class Ref(holder: RdmaBlockRef, val token: Token, queue: ReferenceQueue<RdmaBlockRef>) :
        PhantomReference<RdmaBlockRef>(holder, queue)

    private object Finalizer {
        private val queue = ReferenceQueue<RdmaBlockRef>()
        private val live: MutableSet<Ref> = Collections.newSetFromMap(ConcurrentHashMap())

        init {
            Thread({
                while (true) {
                    val ref = queue.remove() as Ref
                    live.remove(ref)
                    ref.token.release()
                }
            }, "rdma-block-release").apply { isDaemon = true }.start()
        }

        fun track(holder: RdmaBlockRef, token: Token) {
            live.add(Ref(holder, token, queue))
        }
    }
}

/** Several block holders released together (a widget's list of callbacks). */
class RdmaBlockRefs(val refs: List<RdmaBlockRef>) : RememberObserver {
    override fun onRemembered() {}
    override fun onForgotten() = refs.forEach { it.release() }
    override fun onAbandoned() = refs.forEach { it.release() }
}

// ---------------------------------------------------------------------------
// Composition-owned block lambdas for the generated widget entries. The holder is
// remembered by the block id: while the plugin passes the same (memoized) lambda the
// id is stable, the widget receives the very same Kotlin lambda and Compose can skip
// it; when the id changes the previous block is released at once.
// ---------------------------------------------------------------------------

@Composable
fun rdmaRememberBlock(id: Long, paramDescs: String = ""): RdmaBlockRef =
    remember(id) { RdmaBlockRef(id).withParamDescs(paramDescs) }

@Composable
fun rdmaRememberContent0(id: Long): @Composable () -> Unit {
    val ref = rdmaRememberBlock(id)
    return remember(ref) {
        @Composable { RdmaComposeHost.nativeInvokeScopeBlock(ref.id, currentComposer, 0); ref.touch() }
    }
}

@Composable
fun rdmaRememberContent1(id: Long): @Composable (Int) -> Unit {
    val ref = rdmaRememberBlock(id)
    return remember(ref) {
        @Composable { p0: Int -> RdmaComposeHost.nativeInvokeScopeBlock1(ref.id, currentComposer, 0, p0); ref.touch() }
    }
}

@Composable
fun rdmaRememberCallback0(id: Long): () -> Unit {
    val ref = rdmaRememberBlock(id)
    return remember(ref) { { RdmaComposeHost.nativeInvokeCallback(ref.id, emptyArray()); ref.touch() } }
}

@Composable
fun <P1> rdmaRememberCallback1(id: Long, paramDescs: String): (P1) -> Unit {
    val ref = rdmaRememberBlock(id, paramDescs)
    return remember(ref) { { p1: P1 -> RdmaComposeHost.nativeInvokeCallback(ref.id, arrayOf<Any?>(p1)); ref.touch() } }
}

@Composable
fun <P1, P2> rdmaRememberCallback2(id: Long, paramDescs: String): (P1, P2) -> Unit {
    val ref = rdmaRememberBlock(id, paramDescs)
    return remember(ref) {
        { p1: P1, p2: P2 -> RdmaComposeHost.nativeInvokeCallback(ref.id, arrayOf<Any?>(p1, p2)); ref.touch() }
    }
}

@Composable
fun <P1, P2, P3> rdmaRememberCallback3(id: Long, paramDescs: String): (P1, P2, P3) -> Unit {
    val ref = rdmaRememberBlock(id, paramDescs)
    return remember(ref) {
        { p1: P1, p2: P2, p3: P3 -> RdmaComposeHost.nativeInvokeCallback(ref.id, arrayOf<Any?>(p1, p2, p3)); ref.touch() }
    }
}

@Composable
fun rdmaRememberCallbackList(ids: List<Long>): List<() -> Unit> {
    val refs = remember(ids) { RdmaBlockRefs(ids.map { RdmaBlockRef(it) }) }
    return remember(refs) {
        refs.refs.map { ref -> { RdmaComposeHost.nativeInvokeCallback(ref.id, emptyArray()); ref.touch() } }
    }
}
