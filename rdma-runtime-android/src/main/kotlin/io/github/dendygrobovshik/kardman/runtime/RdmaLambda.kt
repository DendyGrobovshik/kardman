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

class RdmaFunction0<R>(private val id: Long) : Function0<R> {
    override fun invoke(): R = RdmaComposeHost.nativeInvokeLambda(id, emptyArray()) as R
}

class RdmaFunction1<P1, R>(private val id: Long) : Function1<P1, R> {
    override fun invoke(p1: P1): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?)) as R
}

class RdmaFunction2<P1, P2, R>(private val id: Long) : Function2<P1, P2, R> {
    override fun invoke(p1: P1, p2: P2): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?, p2 as Any?)) as R
}

class RdmaFunction3<P1, P2, P3, R>(private val id: Long) : Function3<P1, P2, P3, R> {
    override fun invoke(p1: P1, p2: P2, p3: P3): R = RdmaComposeHost.nativeInvokeLambda(id, arrayOf(p1 as Any?, p2 as Any?, p3 as Any?)) as R
}
