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
 * They showcase different Kardman capabilities — Compose state, `@RDMA` widgets and
 * `@RDMA` services (platform info + HTTP).
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
)
