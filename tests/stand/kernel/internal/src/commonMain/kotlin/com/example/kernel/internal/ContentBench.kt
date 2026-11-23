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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import io.github.dendygrobovshik.kardman.RDMA
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/**
 * Composition benchmark (test probe): composes the plugin [content] with round 0, then
 * [rounds] more times with a new round number, and prints how long the composition of the
 * content took — the first one and the median of the rest.
 */
@Composable
@RDMA
fun ContentBench(tag: String, rounds: Int, content: @Composable (Int) -> Unit, modifier: Modifier = Modifier()) {
    var round by remember { mutableIntStateOf(0) }
    val micros = remember { LongArray(rounds + 1) }
    M3Column(modifier = modifier.toComposeUi()) {
        val mark = TimeSource.Monotonic.markNow()
        content(round)
        micros[round] = mark.elapsedNow().inWholeMicroseconds
    }
    LaunchedEffect(Unit) {
        repeat(rounds) {
            withFrameNanos { }
            delay(30)
            round++
        }
        withFrameNanos { }
        delay(200)
        val rest = micros.drop(1).sorted()
        println("BENCH $tag: first=${micros[0] / 1000}ms median=${rest[rest.size / 2] / 1000}ms min=${rest.first() / 1000}ms max=${rest.last() / 1000}ms")
    }
}
