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

import io.github.dendygrobovshik.kardman.RDMA

// Lambda parameters of class members (docs/design/runtime_rework.md §11): a plugin callback
// passed to a constructor and to a method crosses as a block id and is invoked by the kernel.

@RDMA
class Ticker(onTick: (Int) -> Unit) {
    private val callback = onTick

    fun tick(times: Int) {
        for (i in 1..times) callback(i)
    }

    fun each(items: List<String>, action: (String) -> Unit) {
        items.forEach(action)
    }
}
