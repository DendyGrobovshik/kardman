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
package com.example.kernel.user.alice

import io.github.dendygrobovshik.kardman.RDMA

/**
 * A top-level `@RDMA` function in the `user:alice` module, used by the alice plugin. It exists
 * as a removal target for the integration test (manual-polyfill / `R` scenario): a top-level
 * function is what the `R` materializer can re-provide.
 */
@RDMA
fun aliceTagline(): String = "alice-v1"
