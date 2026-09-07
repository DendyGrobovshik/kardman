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
import androidx.compose.runtime.currentComposer
import kotlin.jvm.functions.Function2

object RdmaComposeHost {
    // Call this from the host composition (e.g. androidApp `setContent`).
    @Composable
    fun Content() {
        nativeInvokeContent(currentComposer)
    }

    private external fun nativeInvokeContent(composer: Composer)
    external fun nativeInvokeScopeBlock(blockId: Long, composer: Composer, changed: Int)
    external fun nativeInvokeCallback(blockId: Long, args: Array<Any?>)
    external fun nativeInvokeLambda(id: Long, args: Array<Any?>): Any?
}

class ComposerScopeBlock(private val blockId: Long) : Function2<Composer, Int, Unit> {
    override fun invoke(composer: Composer, changed: Int) {
        RdmaComposeHost.nativeInvokeScopeBlock(blockId, composer, changed)
    }
}

class JsValueHolder(val id: Long)
