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

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.dp
import io.github.dendygrobovshik.kardman.RDMA

/**
 * A lazy list whose items are a plugin `@Composable (Int) -> Unit` (test probe). It scrolls
 * itself to the end and back [passes] times and prints the items it ever laid out with zero
 * height — an item whose plugin content composed no nodes.
 */
@Composable
@RDMA
fun LazyProbe(tag: String, count: Int, passes: Int, item: @Composable (Int) -> Unit, modifier: Modifier = Modifier()) {
    val state = rememberLazyListState()
    LazyColumn(state = state, modifier = modifier.toComposeUi().height(240.dp)) {
        items(count) { i -> item(i) }
    }
    LaunchedEffect(Unit) {
        val seen = HashSet<Int>()
        val holes = HashSet<Int>()
        suspend fun check() {
            withFrameNanos { }
            for (info in state.layoutInfo.visibleItemsInfo) {
                seen += info.index
                if (info.size == 0) holes += info.index
            }
        }
        check()
        repeat(passes) {
            while (state.canScrollForward) { state.scrollBy(90f); check() }
            while (state.canScrollBackward) { state.scrollBy(-90f); check() }
        }
        println("LAZY_PROBE $tag: seen=${seen.size} holes=${holes.size} ${holes.sorted().take(20)}")
    }
}
