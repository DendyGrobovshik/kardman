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
package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import com.example.kernel.user.itest.itestThrow

// S2: Native -> JS (host throws, plugin catches a JS Error).
fun runExceptionScenarios() {
    try {
        itestThrow("S2")
    } catch (e: Throwable) {
        println("S2_CAUGHT:" + e.message)
    }
}

@Composable
fun ExceptionsContent() {
    Column {
        Text("EXCEPTIONS")
    }
}

fun main() {
    runExceptionScenarios()
    runRdmaApp { ExceptionsContent() }
}
