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
import kotlinx.coroutines.delay

/**
 * Long boundary exercises: an echo for the plugin-side literal round trip, a
 * List<Long> channel for element crossings, a suspend result, and a `Long` field
 * on an `@RDMA` class. `Long` keeps all 64 bits in both directions as the plugin's
 * `Long(low, high)` object (docs/design/type_descriptors.md#long-policy).
 */
@RDMA
fun echoLong(value: Long): Long = value

@RDMA
fun echoLongList(list: List<Long>): List<Long> = list

@RDMA
suspend fun echoLongSuspend(value: Long): Long {
    delay(50)
    return value
}

@RDMA
class LongBox(var value: Long = 0L)
