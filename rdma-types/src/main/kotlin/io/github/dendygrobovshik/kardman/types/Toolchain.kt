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
package io.github.dendygrobovshik.kardman.types

import kotlinx.serialization.Serializable

/**
 * The pins that identify a toolchain generation (§1 of toolchain_compatibility.md). The
 * generation id `G` is a content hash of these; they are recorded alongside `g` in
 * `toolchain.json` for reproducibility and diagnostics.
 */
@Serializable
data class ToolchainPins(
    val kotlin: String = "",
    val composeRuntime: String = "",
    val hermes: String = "",
)

/**
 * One toolchain generation. [g] is the generation id (the content hash of [pins]);
 * [status] is `active` or `retired`. Append-only in `toolchain.json`.
 */
@Serializable
data class ToolchainGeneration(
    val g: String,
    val alias: String = "",
    val pins: ToolchainPins = ToolchainPins(),
    val time: String = "",
    val status: String = "active",
)

/**
 * The append-only journal of toolchain generations (`versions/toolchain.json`), the source of
 * truth for the binary-compatibility axis. Order is by first appearance.
 */
@Serializable
data class Toolchain(
    val generations: List<ToolchainGeneration> = emptyList(),
)
