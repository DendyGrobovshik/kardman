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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Text
import kotlinx.coroutines.delay

/**
 * `derivedStateOf`: a state ticking 30 times feeds `tick / 10`; its reader must run once
 * plus once per change of the result (4 times), not once per tick. Also read outside
 * composition, and a calculation that throws.
 */
@Composable
fun DerivedProbe() {
    var tick by remember { mutableStateOf(0) }
    val tens = remember { derivedStateOf { tick / 10 } }
    val label = remember { derivedStateOf<String> { "tick ${tick % 2}" } }
    val broken = remember { derivedStateOf<Int> { if (tick >= 0) error("calc boom") else 0 } }
    val runs = remember { IntArray(1) }
    DerivedReader(tens, runs)
    LaunchedEffect(Unit) {
        repeat(30) {
            tick++
            delay(40)
        }
        delay(300)
        val error = runCatching { broken.value }.exceptionOrNull()?.message
        println("DERIVED: runs=${runs[0]} tens=${tens.value} label=${label.value} error=$error")
    }
}

@Composable
private fun DerivedReader(tens: State<Int>, runs: IntArray) {
    runs[0]++
    Text("tens=${tens.value}")
}
