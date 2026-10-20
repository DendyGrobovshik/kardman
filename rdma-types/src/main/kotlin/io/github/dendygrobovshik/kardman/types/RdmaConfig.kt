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

/**
 * The project's `rdma.toml` (toolchain_compatibility.md §4): the operator-edited support policy
 * plus the framework config that used to live in the `#RDMA` section of `gradle.properties`.
 *
 * Support policy is expressed as explicit **floors**, not sliding windows: everything at or above
 * the floor is supported, everything below it is out of support.
 */
data class RdmaConfig(
    val kernel: KernelConfig = KernelConfig(),
    val plugin: PluginConfig = PluginConfig(),
    val toolchain: ToolchainConfig = ToolchainConfig(),
    val hermes: HermesConfig = HermesConfig(),
)

/**
 * Kernel-axis config. [oldestActiveKernel] is the oldest kernel version `H` still supported
 * (`null` = no floor, everything supported).
 */
data class KernelConfig(
    val rootPackage: String = "",
    val project: String = "",
    val internals: List<String> = emptyList(),
    val oldestActiveKernel: Int? = null,
)

/** Plugin-axis config (the plugin container project + its root package). */
data class PluginConfig(
    val rootPackage: String = "",
    val project: String = "",
)

/**
 * Toolchain-axis config. [oldestActiveGeneration] is the oldest toolchain generation still live
 * (a floor in `toolchain.json`'s first-appearance order; empty = no floor). Generation retirement
 * is FIFO: `retire-generation` advances this floor past the retired generation.
 */
data class ToolchainConfig(
    val oldestActiveGeneration: String = "",
)

/** Hermes compiler config: [path] is the on-disk `hermesc` binary (empty = resolve from maven). */
data class HermesConfig(
    val path: String = "",
)
