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
package com.example.kernel.internal.services

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.dendygrobovshik.kardman.RDMA

/**
 * A native caller on the UI thread: a kernel widget composes on the UI thread, so its
 * call of the hotfixed `hotfixTarget` goes through the UI → Hermes rendezvous
 * (docs/design/runtime_rework.md §6.4).
 */
@RDMA
@Composable
fun HotfixProbe() {
    Text("UI_HOTFIX " + hotfixTarget("ui"))
}
