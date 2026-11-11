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
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import io.github.dendygrobovshik.kardman.RDMA

/**
 * Trailing default-parameter exercises: a trailing default that references an earlier parameter
 * (cross-parameter resolution), multiple trailing defaults, a nullable return, a suspend function,
 * a defaulted constructor/method with a `this` reference, and composable value + lambda defaults.
 */

// Cross-parameter default: `b` references the already-resolved `a`.
@RDMA
fun addWithDefaults(a: Int, b: Int = a * 2): Int = a + b

// Multiple trailing defaults.
@RDMA
fun greet(name: String = "world", punctuation: String = "!"): String = "hello $name$punctuation"

// Nullable return with a defaulted trailing parameter.
@RDMA
fun describeDefault(flag: Boolean, label: String = "default-label"): String? =
    if (flag) "flag:$label" else null

// Defaulted suspend function.
@RDMA
suspend fun computeSumDefault(a: Int, b: Int = 10): Int = a + b

// Defaulted constructor + a method whose default references `this`.
@RDMA
class Counter(val step: Int = 1) {
    fun bump(value: Int, times: Int = this.step): Int = value + times * this.step
    fun tag(prefix: String = "n"): String = "$prefix:$step"
}

// Composable value defaults.
@Composable
@RDMA
fun TextWithDefault(text: String = "default", title: String = "title") {
    println("WIDGET_TEXT_DEFAULT: $title: $text")
    M3Text("$title: $text")
}

// Composable empty-lambda default.
@Composable
@RDMA
fun ColumnWithDefault(content: @Composable () -> Unit = {}) {
    println("WIDGET_COLUMN_DEFAULT")
    M3Column {
        content()
    }
}
