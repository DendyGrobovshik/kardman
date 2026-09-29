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
 * Plugin state machine (P1–P4, §7.2), ordered from healthiest to most degraded. Degradation is
 * driven by the kernel (deprecation → removal → R drop); recovery by a plugin update.
 */
@Serializable
enum class PluginState { CURRENT, AGING, AT_RISK, BROKEN }

/** Update severity attached to a re-sync response (§7.4). */
@Serializable
enum class UpdateSeverity { NONE, RECOMMENDED, MANDATORY, CRITICAL }

@Serializable
enum class ResyncStatus { CURRENT, UPDATE, UNAVAILABLE, REFUSED }

/** A single published plugin release (§8.1). */
@Serializable
data class PluginRelease(
    val contract: RdmaPluginContract,
    val bundleHash: String,
)

/** Client → store re-sync request (§8.2): `(id, version, builtAgainst)` + the host's native `H`. */
@Serializable
data class ResyncRequest(
    val pluginId: String,
    val version: Int,
    val builtAgainst: Int,
    val hostVersion: Int,
)

/** Store → client re-sync response (§8.2, §7.4). */
@Serializable
data class ResyncResponse(
    val pluginId: String,
    val state: PluginState,
    val severity: UpdateSeverity,
    val status: ResyncStatus,
    val currentVersion: Int,
    val targetVersion: Int,
    val bundleHash: String? = null,
    val polyfills: List<String> = emptyList(),
)
