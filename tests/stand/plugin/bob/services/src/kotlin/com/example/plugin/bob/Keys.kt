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
package com.example.plugin.bob

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Modifier
import com.example.kernel.internal.Row
import com.example.kernel.internal.Text
import com.example.kernel.internal.modifierCompositions
import kotlinx.coroutines.delay

// Object keys in the Compose slot table (docs/internals/compose_protocol.md#object-keys):
// when the parent recomposes, the same plugin object, an equal kernel object and the same
// content lambda count as unchanged — as in plain Compose.

class Item(val name: String)

// The same object on every call, but not provably so to the compiler (a value from
// `remember {}` would be static and never reach `changed`).
private val sharedItem = Item("item")
private fun currentItem(): Item = sharedItem

private var bodyRuns = 0
private var itemRowRuns = 0
private var rememberItemRuns = 0
private var rememberKernelRuns = 0
private var effectStarts = 0

@Composable
fun ItemRow(item: Item) {
    itemRowRuns++
    Text(item.name)
}

@Composable
fun KeysDemo() {
    var tick by remember { mutableStateOf(0) }
    val item = currentItem()
    bodyRuns++
    // Same plugin object as a key.
    remember(item) { rememberItemRuns++ }
    // A new kernel object on every run, equal by the kernel's equals.
    remember(Modifier().padding(1.0)) { rememberKernelRuns++ }
    DisposableEffect(item) {
        effectStarts++
        onDispose { }
    }
    Text("tick=$tick")
    // A plugin composable with an object parameter (the same instance).
    ItemRow(item)
    // A kernel container with a content lambda (capturing, so it is not a static singleton).
    Row(modifier = Modifier().testTag("keys-row")) { Text("in row: ${item.name}") }
    LaunchedEffect(Unit) {
        // Relative to this point: the stand host may have recreated the component meanwhile.
        delay(300)
        val b0 = bodyRuns
        val i0 = itemRowRuns
        val r0 = rememberItemRuns
        val k0 = rememberKernelRuns
        val e0 = effectStarts
        val row0 = modifierCompositions("keys-row")
        repeat(3) {
            tick++
            delay(200)
        }
        println(
            "KEYS: body=+${bodyRuns - b0} itemRow=+${itemRowRuns - i0} rememberItem=+${rememberItemRuns - r0} " +
                "rememberKernel=+${rememberKernelRuns - k0} effect=+${effectStarts - e0} row=+${modifierCompositions("keys-row") - row0}",
        )
    }
}
