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
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import io.github.dendygrobovshik.kardman.RDMA
import io.github.dendygrobovshik.kardman.RuntimeLocal

// Compose-style widgets: `modifier` first among the optional parameters. The framework needs
// defaulted parameters to be trailing (docs/guide/types.md), so the trailing lambdas default
// to `{}`; the trailing-lambda call syntax still binds them.

@Composable
@RDMA
fun Text(text: String, modifier: Modifier = Modifier()) {
    M3Text(text, modifier = modifier.toComposeUi())
}

/** A widget with an `@RDMA` enum parameter: the entry crosses as its name and is rebuilt. */
@Composable
@RDMA
fun PriorityBadge(priority: com.example.kernel.internal.services.Priority, modifier: Modifier = Modifier()) {
    lastPriorityBadge = priority.name
    M3Text("priority=${priority.name}", modifier = modifier.toComposeUi())
}

@RuntimeLocal // a test probe read back by the plugin, never hotfixed
private var lastPriorityBadge = ""

/** The priority [PriorityBadge] last composed with (test probe). */
@RDMA
fun lastPriorityBadge(): String = lastPriorityBadge

@Composable
@RDMA
fun Column(modifier: Modifier = Modifier(), content: @Composable () -> Unit = {}) {
    M3Column(modifier = modifier.toComposeUi()) {
        ProvideLayoutScope(LayoutScope.InColumn(this), content)
    }
}

@Composable
@RDMA
fun Row(modifier: Modifier = Modifier(), content: @Composable () -> Unit = {}) {
    M3Row(modifier = modifier.toComposeUi()) {
        ProvideLayoutScope(LayoutScope.InRow(this), content)
    }
}

@Composable
@RDMA
fun Button(text: String, modifier: Modifier = Modifier(), onClick: () -> Unit = {}) {
    M3Button(onClick = onClick, modifier = modifier.toComposeUi()) {
        M3Text(text)
    }
}

// Plugin entry points: a plugin registers composables under a name, and the host renders
// them with `RdmaComposeHost.Component("<pluginId>/<name>", args…)` — any number of times,
// each place with its own state. These are host-side placeholders for the plugin's JVM
// pass; in the plugin they resolve to the runtime bridge (docs/internals/compose_protocol.md).
fun registerComponent(name: String, content: @Composable () -> Unit) {}
fun <A> registerComponent(name: String, content: @Composable (A) -> Unit) {}
fun <A, B> registerComponent(name: String, content: @Composable (A, B) -> Unit) {}
fun <A, B, C> registerComponent(name: String, content: @Composable (A, B, C) -> Unit) {}
