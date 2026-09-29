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
 * The version contract of a plugin (§2.2, §2.3): `[version, builtAgainst, minHost, module-deps]`
 * + code.
 *
 *  - [version] — monotonic number per plugin, incremented on every plugin release.
 *  - [builtAgainst] — the kernel version the plugin was compiled against.
 *  - [minHost] — the minimum host version the plugin runs on: `max(floor(S))` over the symbols
 *    it uses (§5.3).
 *  - [moduleDeps] — the fixed set of kernel modules the plugin sees (`internal` + its own
 *    `user:*`).
 *  - [usedSymbols] — the `@RDMA` symbols the plugin actually references (for diagnostics and
 *    telemetry; drives `minHost` and the store's state computation).
 */
@Serializable
data class RdmaPluginContract(
    val pluginId: String,
    val version: Int = 1,
    val builtAgainst: Int = 0,
    val minHost: Int = 0,
    val moduleDeps: List<String> = emptyList(),
    val usedSymbols: List<String> = emptyList(),
)
