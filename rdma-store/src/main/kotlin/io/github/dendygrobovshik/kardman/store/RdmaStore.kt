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

import io.github.dendygrobovshik.kardman.kernel.RdmaBundleFiles
import io.github.dendygrobovshik.kardman.kernel.RdmaChangelog
import io.github.dendygrobovshik.kardman.kernel.RdmaPolyfillIndex
import io.github.dendygrobovshik.kardman.types.PolyfillIndex
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
    private val gzipped = mutableMapOf<String, ByteArray>()
    private var totalLive = 0
    private var index = PolyfillIndex()
    // Live plugin versions seen by re-sync ("<pluginId>@<version>"), persisted in
    // `versions/telemetry.json`: the usage the `R` retention policy reads (§7.3).
    private val seen = mutableMapOf<String, LivePlugin>()

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
        index = RdmaPolyfillIndex.read(dir)
        File(dir, TELEMETRY_FILE).takeIf { it.isFile }?.let { f -> runCatching { seen.putAll(decodeTelemetry(f.readText())) } }
    }

    /** The polyfill index (normally read from `versions/polyfills.json` by [reload]). */
    fun setPolyfillIndex(index: PolyfillIndex) {
        this.index = index
    }

    /**
     * The polyfill bundles [target] needs on host `(H, G)`, or null when the store does not have
     * them (compatibility.md §8.2): for each kernel module the plugin uses that changed after
     * `H` up to the kernel the plugin was built against, an `F` built at least that recently.
     */
    private fun fPolyfillsFor(target: PluginRelease, hostVersion: Int, hostGeneration: String): List<String>? {
        val builtAgainst = target.contract.builtAgainstKernel
        val keys = mutableListOf<String>()
        for (module in target.contract.moduleDeps) {
            val lastChange = RdmaChangelog.lastChange(changelog, module, builtAgainst)
            if (lastChange <= hostVersion) continue
            val ref = index.f[module]?.get(hostVersion.toString())?.get(hostGeneration) ?: return null
            if (ref.version < lastChange) return null
            keys += ref.hash
        }
        return keys
    }

    fun registerRelease(release: PluginRelease) {
        releases.getOrPut(release.contract.pluginId) { mutableListOf() }.add(release)
    }

    /** Loads plugin releases from the `versions/plugins/` directory. */
    fun loadReleases(versionsDir: File) {
        RdmaPluginReleases.loadAll(versionsDir).values.forEach { list -> list.forEach { registerRelease(it) } }
    }

    /**
     * Stores a downloadable bundle (polyfill or plugin bundle) under a content key (§8.1), with
     * its release-time gzip copy when there is one ([RdmaBundleFiles]).
     */
    fun putBundle(key: String, bytes: ByteArray, gzipped: ByteArray? = null) {
        bundles[key] = bytes
        if (gzipped != null) this.gzipped[key] = gzipped else this.gzipped.remove(key)
    }

    fun bundle(key: String): ByteArray? = bundles[key]

    /** The bundle's gzip copy made at release time, or null when it was published without one. */
    fun gzippedBundle(key: String): ByteArray? = gzipped[key]

    /**
     * Loads bundles from a `bundles/` directory: each file is stored under its file name as the
     * content key (§8.1). Keys are `F:<module>:<H>` / `R:<module>` / plugin-bundle sha256 hashes.
     */
    fun loadBundles(dir: File) {
        if (!dir.isDirectory) return
        dir.listFiles { f -> f.isFile && !f.name.endsWith(RdmaBundleFiles.GZIP_SUFFIX) && !f.name.startsWith(".") }?.forEach { f ->
            val gz = File(dir, f.name + RdmaBundleFiles.GZIP_SUFFIX).takeIf { it.isFile }?.readBytes()
            putBundle(f.name, f.readBytes(), gz)
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
        // The newest compatible version whose polyfills this host can get; a version that needs
        // a polyfill the store does not have (not built for this host or generation) is skipped.
        val target = pluginReleases
            .filter { it.contract.minHost <= request.hostVersion }
            .filter { request.hostGeneration.isBlank() || it.contract.builtAgainstToolchain == request.hostGeneration }
            .sortedByDescending { it.contract.version }
            .firstOrNull { fPolyfillsFor(it, request.hostVersion, request.hostGeneration) != null }

        val current = pluginReleases.firstOrNull { it.contract.version == request.version }
        fun rRetained(fqn: String): Boolean {
            val removedAt = RdmaChangelog.removedAt(changelog)[fqn] ?: return true
            return RdmaStoreEngine.isRRetained(removedAt, now, usageFraction(fqn))
        }

        val state = current
            ?.let { RdmaStoreEngine.pluginState(changelog, it.contract.usedSymbols, ::rRetained) }
            ?: PluginState.CURRENT
        current?.let { recordSeen(request.pluginId, request.version, it.contract.usedSymbols, now) }
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

        val polyfills = fPolyfillsFor(target, request.hostVersion, request.hostGeneration).orEmpty() +
            rPolyfills(target, request.hostGeneration, state)

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

    /** `R` polyfills of the modules an at-risk plugin uses (every release that removed symbols). */
    private fun rPolyfills(target: PluginRelease, hostGeneration: String, state: PluginState): List<String> {
        if (state != PluginState.AT_RISK) return emptyList()
        return target.contract.moduleDeps.flatMap { module -> index.r[module]?.get(hostGeneration).orEmpty().map { it.hash } }
    }

    private fun recordSeen(pluginId: String, version: Int, usedSymbols: List<String>, now: Instant) {
        seen["$pluginId@$version"] = LivePlugin(now.toString(), usedSymbols.distinct())
        val dir = versionsDir ?: return
        runCatching { File(dir, TELEMETRY_FILE).writeText(encodeTelemetry(seen)) }
    }

    /**
     * The share of live plugin versions using [fqn]: those seen by re-sync within the retention
     * window, plus any recorded through [recordLive].
     */
    private fun usageFraction(fqn: String, now: Instant = Instant.now()): Double {
        val cutoff = now.minus(java.time.Duration.ofDays(RdmaStoreEngine.RETENTION_DAYS))
        val live = seen.values.filter { runCatching { Instant.parse(it.lastSeen) }.getOrNull()?.isAfter(cutoff) == true }
        val total = totalLive + live.size
        if (total == 0) return 0.0
        return ((usageBySymbol[fqn] ?: 0) + live.count { fqn in it.usedSymbols }).toDouble() / total
    }

    private companion object {
        const val TELEMETRY_FILE = "telemetry.json"

        fun encodeTelemetry(seen: Map<String, LivePlugin>): String =
            kotlinx.serialization.json.JsonObject(
                seen.mapValues { (_, p) ->
                    kotlinx.serialization.json.JsonObject(
                        mapOf(
                            "lastSeen" to kotlinx.serialization.json.JsonPrimitive(p.lastSeen),
                            "usedSymbols" to kotlinx.serialization.json.JsonArray(p.usedSymbols.map { kotlinx.serialization.json.JsonPrimitive(it) }),
                        ),
                    )
                },
            ).toString()

        fun decodeTelemetry(text: String): Map<String, LivePlugin> {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
            return root.mapValues { (_, v) ->
                val o = v as kotlinx.serialization.json.JsonObject
                LivePlugin(
                    (o["lastSeen"] as kotlinx.serialization.json.JsonPrimitive).content,
                    (o["usedSymbols"] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content },
                )
            }
        }
    }
}

/** A plugin version seen by re-sync: when last, and the kernel symbols it uses. */
data class LivePlugin(val lastSeen: String, val usedSymbols: List<String>)
