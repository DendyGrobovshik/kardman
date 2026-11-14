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
package com.example.plugin.bench

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.Row
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import io.github.dendygrobovshik.kardman.compose.Color
import io.github.dendygrobovshik.kardman.compose.Dp
import io.github.dendygrobovshik.kardman.compose.Modifier
import io.github.dendygrobovshik.kardman.compose.Shape
import io.github.dendygrobovshik.kardman.compose.VerticalAlignment

private const val ROWS = 32
private const val COLS = 4

/**
 * Guest grid measured by `:androidApp:guestComposeBench`.
 * Starts as Modifiers. The device test taps NoModifiers for the same grid
 * with no cell modifier chain. The label is `<name> <ms> ms tick <n>`.
 */
@Composable
fun ModifiersBench() {
    var which by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
    var recordedTick by remember { mutableStateOf(-1) }
    var shown by remember { mutableStateOf("measuring") }
    val name = if (which == 0) "Modifiers" else "NoModifiers"
    Column(modifier = Modifier().fillMaxWidth().paddingSymmetric(Dp(8.0), Dp(64.0))) {
        Text(shown, modifier = Modifier().testTag("bench-time").paddingAll(Dp(4.0)))
        Button(
            "Recompose",
            modifier = Modifier().fillMaxWidth().paddingAll(Dp(4.0)),
            onClick = { tick = tick + 1 },
        )
        if (which == 0) {
            Button(
                "NoModifiers",
                modifier = Modifier().fillMaxWidth().paddingAll(Dp(4.0)),
                onClick = {
                    which = 1
                    tick = 0
                },
            )
        }
        val pass = tick
        val started = guestNowMs()
        repeat(ROWS) { row ->
            Row(modifier = Modifier().fillMaxWidth().paddingSymmetric(Dp(2.0), Dp(1.0))) {
                repeat(COLS) { col ->
                    val label = "" + row + "." + col
                    if (which == 0) {
                        val band = (row + col + pass) % 2
                        val dy = when (row % 3) {
                            0 -> 0.0
                            1 -> 1.0
                            else -> 2.0
                        }
                        Text(
                            label,
                            modifier = Modifier()
                                .weight(1.0)
                                .paddingAll(Dp(4.0))
                                .background(if (band == 0) Color.Red else Color.Blue, if (col % 2 == 0) Shape.Rectangle else Shape.Circle)
                                .paddingSymmetric(Dp(6.0), Dp(2.0))
                                .alpha(if (pass % 2 == 0) 1.0 else 0.7)
                                .offset(Dp(0.0), Dp(dy))
                                .alignVertically(VerticalAlignment.CenterVertically)
                                .testTag("c" + row + "x" + col),
                        )
                    } else {
                        Text(label)
                    }
                }
            }
        }
        val elapsed = guestFormatMs(started)
        SideEffect {
            if (recordedTick != pass) {
                recordedTick = pass
                shown = name + " " + elapsed + " ms tick " + pass
            }
        }
    }
}

// Android typecheck sees a stub. The JS compile replaces this line with Date.now
// (`:plugin:bench:patchGuestClock`). TimeSource is not in the shared runtime the app loads.
private fun guestNowMs(): Double = 0.0 // GUEST_CLOCK

private fun guestFormatMs(t0: Double): String = "0.0" // GUEST_FORMAT

fun main() {
    runRdmaApp { ModifiersBench() }
}
