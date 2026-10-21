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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private fun iconVector(name: String): ImageVector = when (name) {
    "minus" -> Icons.Filled.Remove
    "plus" -> Icons.Filled.Add
    "pencil" -> Icons.Filled.Edit
    "eye" -> Icons.Filled.Visibility
    else -> Icons.Filled.Add
}

@Composable
internal actual fun PlatformButtonList(labels: List<String>, actions: List<() -> Unit>, selectedIndex: Int, icons: List<String>) {
    if (selectedIndex >= 0) {
        TabRow(selectedTabIndex = selectedIndex) {
            labels.forEachIndexed { i, label ->
                Tab(
                    selected = selectedIndex == i,
                    onClick = { actions[i]() },
                    text = { Text(label) },
                    icon = icons.getOrNull(i)?.let { name -> { Icon(iconVector(name), contentDescription = null) } },
                )
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            labels.forEachIndexed { i, label ->
                Button(
                    onClick = { actions[i]() },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                ) {
                    icons.getOrNull(i)?.let { name ->
                        Icon(iconVector(name), contentDescription = null)
                    }
                    Text(label)
                }
            }
        }
    }
}
