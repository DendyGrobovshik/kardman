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

// Hotfix-slot fixture (docs/design/runtime_rework.md §6). plugin-lifecycle.sh step 10
// changes the body of `hotfixTarget` only: the F polyfill then carries just that
// function (the polyfill closure stops at other @RDMA boundaries), so its callers stay
// native and must reach the hotfix through the slot. Kept free of compose imports so the
// polyfill module compiles with the plain JS classpath.

/** The hotfixed callable. */
@RDMA
fun hotfixTarget(tag: String): String = "native-v1:$tag"

/** A native caller reached from JS: calls the target on the Hermes thread. */
@RDMA
fun callHotfixTarget(): String = "via-native(" + hotfixTarget("hermes") + ")"

/** A suspend callable: changing it must be refused as a hotfix (runtime_rework §6.8). */
@RDMA
suspend fun hotfixSuspendTarget(): String = "native-v1"
