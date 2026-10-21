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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kernel.internal.ButtonList
import com.example.kernel.internal.services.generate
import kotlinx.coroutines.delay

/**
 * The demo shell: a bottom edit/preview switcher, a code editor with example snippets and a
 * "generate" button. Generate sends the code to the demo dev server through the kernel
 * `@RDMA generate` service; once the resulting bundle is loaded it renders in the Preview tab.
 */
@Composable
fun DemoShell() {
    var code by remember { mutableStateOf(Examples.first().code) }
    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showIntro by remember { mutableStateOf(true) }
    var tab by remember { mutableIntStateOf(0) }

    var previewVersion by remember { mutableStateOf(contentVersion()) }
    LaunchedEffect(Unit) {
        while (true) {
            val cv = contentVersion()
            if (cv != previewVersion) previewVersion = cv
            delay(16)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> EditorPane(
                        code = code,
                        generating = generating,
                        status = status,
                        onCodeChange = { code = it; status = "" },
                        onGenerate = {
                            generating = true
                            status = "compiling..."
                            generate(
                                code,
                            onDone = { result ->
                                generating = false
                                status = result
                                tab = 1
                            },
                                onError = { err ->
                                    generating = false
                                    status = "error: $err"
                                },
                            )
                        },
                    )
                    else -> Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        key(previewVersion) { PreviewContent() }
                    }
                }
            }
            ButtonList(
                labels = listOf("Edit", "Preview"),
                actions = listOf({ tab = 0 }, { tab = 1 }),
                selectedIndex = tab,
                icons = listOf("pencil", "eye"),
            )
        }
        if (showIntro) {
            IntroOverlay(onDismiss = { showIntro = false })
        }
    }
}

@Composable
private fun EditorPane(
    code: String,
    generating: Boolean,
    status: String,
    onCodeChange: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Examples.forEach { example ->
                    OutlinedButton(
                        onClick = { onCodeChange(example.code) },
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(example.name)
                    }
                }
            }
            // Fade on the trailing edge so it is obvious the examples scroll.
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(28.dp)
                    .height(48.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onGenerate, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
            Text(if (generating) "Compiling..." else "Generate")
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IntroOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Kardman demo",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Write Kotlin code in the editor and press Generate. The code is compiled " +
                        "into a plugin, delivered over the network and rendered in the Preview " +
                        "tab — without restarting the app.",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap anywhere to close.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
