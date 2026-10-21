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
package com.example.kernel.internal

import androidx.compose.foundation.layout.Column as M3Column
import androidx.compose.foundation.layout.Row as M3Row
import androidx.compose.foundation.layout.Spacer as M3Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dendygrobovshik.kardman.RDMA

@Composable
@RDMA
fun Text(text: String) {
    M3Text(text)
}

@Composable
@RDMA
fun Column(content: @Composable () -> Unit) {
    M3Column {
        content()
    }
}

@Composable
@RDMA
fun Row(content: @Composable () -> Unit) {
    M3Row {
        content()
    }
}

@Composable
@RDMA
fun Spacer(width: Double, height: Double) {
    M3Spacer(Modifier.size(width.dp, height.dp))
}

@Composable
@RDMA
fun Button(text: String, onClick: () -> Unit) {
    M3Button(onClick = onClick) {
        M3Text(text)
    }
}

@Composable
@RDMA
fun ButtonList(labels: List<String>, actions: List<() -> Unit>, selectedIndex: Int, icons: List<String>) {
    PlatformButtonList(labels, actions, selectedIndex, icons)
}

@Composable
internal expect fun PlatformButtonList(labels: List<String>, actions: List<() -> Unit>, selectedIndex: Int, icons: List<String>)

fun runRdmaApp(content: @Composable () -> Unit) {
    // Host-side entry point. In the plugin this call is rewritten by the
    // plugin compiler plugin into RDMA.registerContent(...).
}
