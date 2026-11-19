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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.ColumnWithDefault
import com.example.kernel.internal.Text
import com.example.kernel.internal.TextWithDefault
import com.example.kernel.internal.registerComponent
import com.example.kernel.user.alice.PersonProbe

@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: $count")
        Button("Increment", onClick = { count++ })
        TextWithDefault()
        ColumnWithDefault()
    }
    StateTypesProbe()
    // Overrides called by a kernel widget on the UI thread (vtable, runtime_rework §6.4).
    val probe = remember { Cyborg("probe", 1) }
    PersonProbe(probe)
}

fun main() {
    runPersonDemo()
    registerComponent("main") { Counter() }
}
