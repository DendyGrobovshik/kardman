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
package io.github.dendygrobovshik.kardman.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import io.github.dendygrobovshik.kardman.RDMA

/**
 * Host-side callbacks for [Modifier.clickable]. The guest registers one with [ClickAction];
 * applying the modifier invokes [click].
 */
object ClickActions {
    private val actions = mutableMapOf<Int, () -> Unit>()

    fun register(id: Int, action: () -> Unit) {
        actions[id] = action
    }

    fun unregister(id: Int) {
        actions.remove(id)
    }

    fun click(id: Int) {
        actions[id]?.invoke()
    }

    internal fun clear() {
        actions.clear()
    }
}

/** Keeps [onClick] installed for [actionId] while this composition is active. */
@Composable
@RDMA
fun ClickAction(actionId: Int, onClick: () -> Unit) {
    DisposableEffect(actionId, onClick) {
        ClickActions.register(actionId, onClick)
        onDispose { ClickActions.unregister(actionId) }
    }
}
