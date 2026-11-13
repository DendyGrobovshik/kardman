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
package org.example.ui

/**
 * Example plugin sources shown in the "edit" tab. Each is a complete plugin file: a
 * `package` header, a `@Composable` content function and a `main()` that registers it.
 * They showcase different Kardman capabilities — Compose state, `@RDMA` widgets,
 * modifiers, and `@RDMA` services (platform info + HTTP).
 */
data class Example(val name: String, val code: String)

val Examples = listOf(
    Example("Counter", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.ButtonList
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun GeneratedContent() {
    var count by remember { mutableStateOf(0) }
    Column {
        Text("Count: " + count)
        ButtonList(
            labels = listOf("decrement", "increment"),
            actions = listOf(
                { count-- },
                { count++ },
            ),
            selectedIndex = -1,
            icons = listOf("minus", "plus"),
        )
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("Greeting", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun GeneratedContent() {
    Column {
        Text("Hello from a dynamic plugin!")
        Text("Loaded at runtime, no app restart.")
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("Platform info", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import com.example.kernel.internal.services.platformInfo

@Composable
fun GeneratedContent() {
    val info = remember { platformInfo() }
    Column {
        Text("OS: " + info.os)
        Text("Device: " + info.deviceModel)
        Text("Version: " + info.osVersion)
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("HTTP service", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import com.example.kernel.internal.services.httpGet

@Composable
fun GeneratedContent() {
    val body = remember { mutableStateOf("Press the button") }
    Column {
        Text(body.value)
        Button("Fetch", onClick = {
            body.value = "loading..."
            httpGet(
                "https://example.com",
                onSuccess = { resp -> body.value = "HTTP " + resp.status },
                onError = { err -> body.value = "error: " + err },
            )
        })
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("Layout", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Row
import com.example.kernel.internal.Spacer
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp

@Composable
fun GeneratedContent() {
    Column {
        Text("Nested @RDMA widgets")
        Row {
            Text("Left")
            Spacer(8.0, 0.0)
            Text("Right")
        }
        Spacer(0.0, 8.0)
        Text("Bottom")
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("Modifiers", """package com.example.plugin.generated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.Row
import com.example.kernel.internal.Spacer
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import io.github.dendygrobovshik.kardman.compose.Color
import io.github.dendygrobovshik.kardman.compose.Dp
import io.github.dendygrobovshik.kardman.compose.HorizontalAlignment
import io.github.dendygrobovshik.kardman.compose.Modifier
import io.github.dendygrobovshik.kardman.compose.Shape

@Composable
fun GeneratedContent() {
    var picked by remember { mutableStateOf("none") }
    Column(modifier = Modifier().paddingAll(Dp(16.0)).fillMaxWidth()) {
        Text(
            "Modifiers",
            modifier = Modifier().padding(Dp(0.0), Dp(0.0), Dp(0.0), Dp(8.0)).testTag("title"),
        )
        Text(
            "Picked: " + picked,
            modifier = Modifier().alpha(0.7).alignHorizontally(HorizontalAlignment.CenterHorizontally),
        )
        Spacer(0.0, 12.0)
        Row(modifier = Modifier().fillMaxWidth()) {
            Button(
                "Left",
                modifier = Modifier().weight(1.0).paddingAll(Dp(4.0)),
                onClick = { picked = "left" },
            )
            Spacer(8.0, 0.0)
            Button(
                "Right",
                modifier = Modifier().weight(1.0).paddingAll(Dp(4.0)),
                onClick = { picked = "right" },
            )
        }
        Spacer(0.0, 12.0)
        Row(modifier = Modifier().fillMaxWidth()) {
            Text(
                "Red",
                modifier = Modifier()
                    .weight(1.0)
                    .background(Color.Red, Shape.Rectangle)
                    .paddingAll(Dp(8.0)),
            )
            Spacer(8.0, 0.0)
            Text(
                "Blue",
                modifier = Modifier()
                    .weight(1.0)
                    .background(Color.Blue, Shape.Circle)
                    .paddingSymmetric(Dp(12.0), Dp(8.0))
                    .offset(Dp(0.0), Dp(4.0)),
            )
        }
    }
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
    Example("Heavy", """package com.example.plugin.generated

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
import kotlin.time.TimeSource

private const val HEAVY_ROWS = 32
private const val HEAVY_COLS = 4

@Composable
fun GeneratedContent() {
    var tick by remember { mutableStateOf(0) }
    var recordedTick by remember { mutableStateOf(-1) }
    var shown by remember { mutableStateOf("measuring") }
    Column(modifier = Modifier().fillMaxWidth().paddingAll(Dp(8.0))) {
        Text(shown, modifier = Modifier().testTag("heavy-time").paddingAll(Dp(4.0)))
        Button(
            "Recompose",
            modifier = Modifier().fillMaxWidth().paddingAll(Dp(4.0)),
            onClick = { tick = tick + 1 },
        )
        val started = TimeSource.Monotonic.markNow()
        repeat(HEAVY_ROWS) { row ->
            Row(modifier = Modifier().fillMaxWidth().paddingSymmetric(Dp(2.0), Dp(1.0))) {
                repeat(HEAVY_COLS) { col ->
                    val band = (row + col + tick) % 2
                    Text(
                        "" + row + "." + col,
                        modifier = Modifier()
                            .weight(1.0)
                            .paddingAll(Dp(4.0))
                            .background(if (band == 0) Color.Red else Color.Blue, if (col % 2 == 0) Shape.Rectangle else Shape.Circle)
                            .paddingSymmetric(Dp(6.0), Dp(2.0))
                            .alpha(if (tick % 2 == 0) 1.0 else 0.7)
                            .offset(Dp(0.0), Dp((row % 3).toDouble()))
                            .alignVertically(VerticalAlignment.CenterVertically)
                            .testTag("c" + row + "x" + col),
                    )
                }
            }
        }
        val elapsedUs = started.elapsedNow().inWholeMicroseconds
        SideEffect {
            if (recordedTick != tick) {
                recordedTick = tick
                shown = "grid " + formatMs(elapsedUs) + " ms"
            }
        }
    }
}

private fun formatMs(elapsedUs: Long): String {
    val whole = elapsedUs / 1000
    val frac = (elapsedUs % 1000) / 100
    return "" + whole + "." + frac
}

fun main() {
    runRdmaApp { GeneratedContent() }
}
"""),
)
