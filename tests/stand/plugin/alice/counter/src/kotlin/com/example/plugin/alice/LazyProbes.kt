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
package com.example.plugin.alice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.LazyProbe
import com.example.kernel.internal.Row
import com.example.kernel.internal.Text
import kotlinx.coroutines.delay

/** Lazy list items from a remembered (stable) and an inline plugin lambda. */
@Composable
fun LazyItemsProbe() {
    val stable = remember<@Composable (Int) -> Unit> { { i -> Row { Text("stable-$i") } } }
    LazyProbe("stable", 120, 6, stable)
    LazyProbe("inline", 120, 6, { i -> Row { Text("inline-$i") } })
}

/** States kept in a map, filled during composition and written later. */
@Composable
fun StateMapProbe() {
    var tick by remember { mutableStateOf(0) }
    val flags = remember { HashMap<Int, MutableState<Boolean>>() }
    val replaced = remember { IntArray(1) } // a recomposition got another object from `remember`
    for (i in 0 until 3) {
        val state = remember { mutableStateOf(false) }
        val previous = flags.put(i, state)
        if (previous != null && previous !== state) replaced[0]++
    }
    Text("map tick=$tick ${flags.values.count { it.value }}")
    LaunchedEffect(Unit) {
        tick = 1
        delay(300) // the map now holds the states read back on the recomposition
        val result = runCatching { for (s in flags.values) s.value = true }
        println("STATE_MAP: " + (result.exceptionOrNull()?.message ?: "ok") + " same=${replaced[0] == 0}")
    }
}
