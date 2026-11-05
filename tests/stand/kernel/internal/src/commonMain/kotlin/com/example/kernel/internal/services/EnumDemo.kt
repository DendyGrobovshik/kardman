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
 * Enum boundary exercises. Entries cross the boundary as their name strings: the
 * plugin sees `"GET"` where the kernel sees `HttpMethod.GET`, so plugin-side
 * `when` compiles to plain JS string equality against the RDMA-returned value.
 */
@RDMA
enum class HttpMethod {
    GET,
    POST,
}

@RDMA
enum class Priority {
    LOW,
    HIGH,
}

@RDMA
fun echoMethod(method: HttpMethod): HttpMethod = method

@RDMA
suspend fun echoMethodSuspend(method: HttpMethod): HttpMethod {
    delay(50)
    return method
}

@RDMA
fun defaultMethod(): HttpMethod = HttpMethod.POST

@RDMA
class EnumConfig(var method: HttpMethod) {
    fun methodLabel(): String = "method=${method.name}"
}
