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
package com.example.kernel.user.itest

import io.github.dendygrobovshik.kardman.RDMA

/**
 * New user kernel module added by the integration test (§ "add a user module to the kernel").
 * The `@RDMA` placeholder keeps the module's native bridge generated at the initial (step-1)
 * build; the test script appends `itestGreeting` at runtime.
 */
@RDMA
fun itestPlaceholder(): String = "placeholder"

/**
 * Throws a host exception (used by the exception-propagation e2e to verify
 * Native -> JS delivery: the plugin catches a JS `Error` with `__rdmaCause`).
 */
@RDMA
fun itestThrow(message: String): String {
    throw IllegalStateException(message)
}

/**
 * Fire-and-forget lambda invocation (design §5.1: callback/lambda has no host
 * frame). A throwing block is delivered through the uncaught-exception path.
 */
@RDMA
fun itestFireAndForget(block: () -> Unit) {
    block()
}
