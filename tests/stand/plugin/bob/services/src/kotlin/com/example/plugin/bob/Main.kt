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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.kernel.internal.Text
import com.example.kernel.internal.registerComponent

fun main() {
    // A component with parameters: the host renders "bob:services/card" twice with
    // different arguments, each with its own state (docs/internals/compose_protocol.md).
    registerComponent("card") { title: String, count: Int -> Card(title, count) }
    registerComponent("modifiers") { ModifiersDemo() }
    registerComponent("keys") { KeysDemo() }
    runServicesDemo()
}

@Composable
fun Card(title: String, count: Int) {
    val created by remember { mutableStateOf("$title#$count") }
    Text("$title: $count")
    SideEffect { println("CARD: $title $count state=$created") }
}
