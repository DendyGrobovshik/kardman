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
import com.example.kernel.internal.ContentBench
import com.example.kernel.internal.Row
import com.example.kernel.internal.Text

/** A heavy plugin composition: 100 rows of two texts, recomposed 20 times (all rows change). */
@Composable
fun BenchProbe() {
    ContentBench("rows", 20, { round ->
        for (i in 0 until 100) {
            Row {
                Text("row $i")
                Text("round $round")
            }
        }
    })
}
