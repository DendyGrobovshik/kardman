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
package io.github.dendygrobovshik.kardman.store

import io.github.dendygrobovshik.kardman.kernel.RdmaChangelog
import io.github.dendygrobovshik.kardman.kernel.RdmaPluginReleases
import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.PluginState
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import java.io.File
import java.time.Instant

/**
 * The in-memory store: the plugin registry, the changelog (source of truth) and the re-sync
 * reconciliation (§8.2). It is the single place that computes the plugin state and severity on
 * every re-sync.
 */
class RdmaStore(
    private var changelog: Changelog = Changelog(),
) {
    private val releases = mutableMapOf<String, MutableList<PluginRelease>>()
    private val usageBySymbol = mutableMapOf<String, Int>()
    private val bundles = mutableMapOf<String, ByteArray>()
    private var totalLive = 0

    /** Directory holding `changelog.json`, `plugins/` and `bundles/`; used by [reload]. */
    var versionsDir: File? = null

    /**
     * Re-reads the changelog, plugin releases and bundles from disk. The store is static (§8.1):
     * CI populates [versionsDir], and re-sync serves from it. Reloading lets a long-running server
     * pick up newly published releases without restarting.
     */
    fun reload() {
        val dir = versionsDir ?: return
        loadReleases(dir)
        val changelogFile = File(dir, "changelog.json")
        if (changelogFile.isFile) {
            changelog = RdmaChangelog.read(changelogFile)
        }
        loadBundles(File(dir, "bundles"))
    }

    fun registerRelease(release: PluginRelease) {
        releases.getOrPut(release.contract.pluginId) { mutableListOf() }.add(release)
    }

    /** Loads plugin releases from the `versions/plugins/` directory. */
    fun loadReleases(versionsDir: File) {
        RdmaPluginReleases.loadAll(versionsDir).values.forEach { list -> list.forEach { registerRelease(it) } }
    }

    /** Stores a downloadable bundle (polyfill or plugin bundle) under a content key (§8.1). */
    fun putBundle(key: String, bytes: ByteArray) {
        bundles[key] = bytes
    }

    fun bundle(key: String): ByteArray? = bundles[key]

    /**
     * Loads bundles from a `bundles/` directory: each file is stored under its file name as the
     * content key (§8.1). Keys are `F:<module>:<H>` / `R:<module>` / plugin-bundle sha256 hashes.
     */
    fun loadBundles(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile }?.forEach { f ->
            bundles[f.name] = f.readBytes()
        }
    }

    fun setChangelog(changelog: Changelog) {
        this.changelog = changelog
    }

    fun changelog(): Changelog = changelog

    /** Telemetry of live plugins for the `R` retention policy (§7.3). */
    fun recordLive(usedSymbols: List<String>) {
        totalLive++
        usedSymbols.distinct().forEach { usageBySymbol[it] = (usageBySymbol[it] ?: 0) + 1 }
    }

    fun resync(request: ResyncRequest, now: Instant = Instant.now()): ResyncResponse {
        val pluginReleases = releases[request.pluginId].orEmpty()
        val target = RdmaStoreEngine.resolveNewest(pluginReleases, request.hostVersion)

        val current = pluginReleases.firstOrNull { it.contract.version == request.version }
        fun rRetained(fqn: String): Boolean {
            val removedAt = RdmaChangelog.removedAt(changelog)[fqn] ?: return true
            return RdmaStoreEngine.isRRetained(removedAt, now, usageFraction(fqn))
        }

        val state = current
            ?.let { RdmaStoreEngine.pluginState(changelog, it.contract.usedSymbols, ::rRetained) }
            ?: PluginState.CURRENT
        val severity = RdmaStoreEngine.severityFor(state)

        if (target == null) {
            return ResyncResponse(
                pluginId = request.pluginId,
                state = state,
                severity = severity,
                status = ResyncStatus.UNAVAILABLE,
                currentVersion = request.version,
                targetVersion = request.version,
            )
        }

        val polyfills = polyfillKeys(target, request.hostVersion, state)

        return when {
            state == PluginState.BROKEN -> ResyncResponse(
                pluginId = request.pluginId,
                state = state,
                severity = severity,
                status = ResyncStatus.REFUSED,
                currentVersion = request.version,
                targetVersion = target.contract.version,
            )
            target.contract.version > request.version -> ResyncResponse(
                pluginId = request.pluginId,
                state = state,
                severity = severity,
                status = ResyncStatus.UPDATE,
                currentVersion = request.version,
                targetVersion = target.contract.version,
                bundleHash = target.bundleHash,
                polyfills = polyfills,
            )
            else -> ResyncResponse(
                pluginId = request.pluginId,
                state = state,
                severity = severity,
                status = ResyncStatus.CURRENT,
                currentVersion = request.version,
                targetVersion = target.contract.version,
                bundleHash = target.bundleHash,
                polyfills = polyfills,
            )
        }
    }

    private fun polyfillKeys(target: PluginRelease, hostVersion: Int, state: PluginState): List<String> {
        val keys = mutableListOf<String>()
        for (module in target.contract.moduleDeps) keys += "F:$module:$hostVersion"
        if (state == PluginState.AT_RISK) {
            for (module in target.contract.moduleDeps) keys += "R:$module"
        }
        return keys
    }

    private fun usageFraction(fqn: String): Double =
        if (totalLive == 0) 0.0 else (usageBySymbol[fqn] ?: 0).toDouble() / totalLive
}
