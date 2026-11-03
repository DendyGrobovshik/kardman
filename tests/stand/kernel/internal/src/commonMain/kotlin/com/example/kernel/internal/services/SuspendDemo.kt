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
 * Suspend bridge exercises: a top-level suspend function, a suspend method, a
 * List-returning suspend function, and an error case. The bodies use `delay` to
 * force a real suspension so the completion travels back to the plugin
 * asynchronously.
 */
@RDMA
suspend fun computeSum(a: Int, b: Int): Int {
    delay(50)
    return a + b
}

@RDMA
suspend fun echoIntListSuspend(list: List<Int>): List<Int> {
    delay(50)
    return list
}

@RDMA
suspend fun echoIntMapSuspend(map: Map<String, Int>): Map<String, Int> {
    delay(50)
    return map
}

@RDMA
suspend fun touchSuspend() {
    delay(50)
}

@RDMA
suspend fun maybeNullSuspend(flag: Boolean): String? {
    delay(50)
    return if (flag) "value-$flag" else null
}

@RDMA
suspend fun describeInfoSuspend(info: PlatformInfo): String {
    delay(50)
    return "os=${info.os} model=${info.deviceModel}"
}

@RDMA
suspend fun failSuspend(): Int {
    delay(50)
    throw IllegalStateException("suspend boom")
}

@RDMA
suspend fun failNestedSuspend(): Int {
    delay(50)
    throw IllegalStateException("outer boom", IllegalArgumentException("inner boom"))
}

@RDMA
suspend fun fetchPlatformInfoSuspend(): PlatformInfo {
    delay(50)
    return PlatformInfo("suspend-os", "suspend-model", "suspend-version")
}

@RDMA
class SuspendService {
    suspend fun fetch(id: Int): String {
        delay(50)
        return "value-$id"
    }
}
