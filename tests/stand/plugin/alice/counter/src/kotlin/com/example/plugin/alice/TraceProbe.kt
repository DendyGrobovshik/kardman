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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

// Recomposition tracer fixture (docs/guide/threads_and_performance.md#recomposition-tracer).
// Three ticks: TraceProbe and TraceSelf restart by their own state, TraceChild is run again
// by TraceProbe with a changed argument, TraceSkipped gets the same argument and runs once.
@Composable
fun TraceProbe() {
    var tick by remember { mutableStateOf(0) }
    TraceChild(tick)
    TraceSkipped("same")
    TraceSelf()
    LaunchedEffect(Unit) {
        repeat(3) {
            delay(100)
            tick++
        }
        delay(300)
        println("TRACE: ticks=$tick")
    }
}

@Composable
fun TraceChild(n: Int) {
    SideEffect { println("TRACE: child=$n") }
}

@Composable
fun TraceSkipped(s: String) {
    SideEffect { println("TRACE: skipped=$s") }
}

@Composable
fun TraceSelf() {
    var v by remember { mutableStateOf(0) }
    val shown = v
    SideEffect { println("TRACE: self=$shown") }
    LaunchedEffect(Unit) {
        repeat(3) {
            delay(100)
            v++
        }
    }
}
