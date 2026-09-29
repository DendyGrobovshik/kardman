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
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import kotlinx.serialization.json.Json

/**
 * Assembles a plugin's version contract (§2.2, §2.3). `minHost` is derived from the symbols the
 * plugin uses and the kernel `floor` map: `max(floor(S))` over the used symbols (§5.3).
 */
object RdmaPluginContractBuilder {
    private val json = Json { prettyPrint = true }

    fun build(
        pluginId: String,
        version: Int,
        builtAgainst: Int,
        moduleDeps: List<String>,
        usedSymbols: List<String>,
        floor: Map<String, Int>,
    ): RdmaPluginContract =
        RdmaPluginContract(
            pluginId = pluginId,
            version = version,
            builtAgainst = builtAgainst,
            minHost = RdmaFloor.minHost(floor, usedSymbols.toSet()),
            moduleDeps = moduleDeps,
            usedSymbols = usedSymbols.distinct().sorted(),
        )

    fun toJson(contract: RdmaPluginContract): String =
        json.encodeToString(RdmaPluginContract.serializer(), contract)

    fun fromJson(text: String): RdmaPluginContract =
        json.decodeFromString(RdmaPluginContract.serializer(), text)
}
