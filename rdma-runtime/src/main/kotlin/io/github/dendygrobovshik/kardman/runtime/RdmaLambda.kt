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

// Kernel wrappers of a plugin lambda passed to an `@RDMA` function. Each one is a
// holder of the JS block (docs/design/runtime_rework.md §4): the block lives exactly
// as long as the wrapper; invocations post to the JS thread and `touch` the holder
// after posting, so it cannot be finalized before the call is queued.
class RdmaFunction0<R>(id: Long) : Function0<R> {
    private val ref = RdmaBlockRef(id)
    override fun invoke(): R = (RdmaComposeHost.nativeInvokeLambda(ref.id, emptyArray()) as R).also { ref.touch() }
}

class RdmaFunction1<P1, R>(id: Long) : Function1<P1, R> {
    private val ref = RdmaBlockRef(id)
    override fun invoke(p1: P1): R = (RdmaComposeHost.nativeInvokeLambda(ref.id, arrayOf(p1 as Any?)) as R).also { ref.touch() }
}

class RdmaFunction2<P1, P2, R>(id: Long) : Function2<P1, P2, R> {
    private val ref = RdmaBlockRef(id)
    override fun invoke(p1: P1, p2: P2): R =
        (RdmaComposeHost.nativeInvokeLambda(ref.id, arrayOf(p1 as Any?, p2 as Any?)) as R).also { ref.touch() }
}

class RdmaFunction3<P1, P2, P3, R>(id: Long) : Function3<P1, P2, P3, R> {
    private val ref = RdmaBlockRef(id)
    override fun invoke(p1: P1, p2: P2, p3: P3): R =
        (RdmaComposeHost.nativeInvokeLambda(ref.id, arrayOf(p1 as Any?, p2 as Any?, p3 as Any?)) as R).also { ref.touch() }
}
