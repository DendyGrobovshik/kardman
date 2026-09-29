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

import io.github.dendygrobovshik.kardman.types.Changelog
import io.github.dendygrobovshik.kardman.types.ChangelogChange
import io.github.dendygrobovshik.kardman.types.ChangelogChangeKind
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thin CLI over the pure analysis functions. Purely data-driven (no compiler dependencies), so it
 * can be run on any classpath that includes `rdma-kernel-compiler-plugin` + `rdma-types` +
 * `kotlinx-serialization`.
 *
 * The changelog (`versionsDir/changelog.json`) is the single source of truth (§2.1). The
 * per-module `rdma_hashes.json` (native state), `rdma_floor.json` (floor map) and
 * `rdma_sources.json` (source snapshot for `R` polyfills) are derived snapshots.
 *
 * Usage:
 *   static-release   <analysis.json> <versionsDir> <polyfillOutDir> <internalModules> [telemetryFile]
 *   dynamic-release  <analysis.json> <versionsDir> <polyfillOutDir> <internalModules> [telemetryFile]
 *   hotfix-release   <analysis.json> <versionsDir> <polyfillOutDir> <internalModules> [telemetryFile]
 *   plugin-release   <plugin.json> <versionsDir> <bundlePath>
 *
 * Release order (§9): materialize + gate first, and only on success append the changelog entry
 * (the write is the commit point). `hotfix-release` additionally rejects a `minHost` bump (§9).
 */
object RdmaReleaseCli {
    private val json = Json

    @JvmStatic
    fun main(args: Array<String>) {
        val command = args.getOrNull(0)
        if (command == "plugin-release") {
            pluginRelease(args)
            return
        }

        val analysisPath = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        if (command == null || analysisPath == null || versionsDir == null) {
            System.err.println("usage: (static-release|dynamic-release|hotfix-release) <analysis.json> <versionsDir> <polyfillOutDir> <internalModules> [telemetryFile]")
            kotlin.system.exitProcess(2)
        }
        val analysis = json.decodeFromString(RdmaAnalysis.serializer(), File(analysisPath).readText())
        val polyfillOutDir = args.getOrNull(3)?.let { it.trim().takeIf(String::isNotEmpty)?.let(::File) } ?: File("build/polyfill")
        val internalModules = args.getOrNull(4)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: setOf("internal")
        val telemetryFile = args.getOrNull(5)?.let { it.trim().takeIf(String::isNotEmpty)?.let(::File) }
        val hostVersions = telemetryFile?.let(::readHostVersions).orEmpty()

        val changelogFile = File(versionsDir, "changelog.json")
        val changelog = if (changelogFile.isFile) RdmaChangelog.read(changelogFile) else Changelog()
        val hotfix = command == "hotfix-release"

        when (command) {
            "static-release" -> {
                // Diff against the changelog *head* (latestVersion), not the baked `H`: changes
                // already recorded by prior dynamic-releases must not be re-added. With new
                // changes → append + bump H; with none → just bake (advance H to the latest).
                val prev = RdmaChangelog.manifest(changelog, analysis.moduleId, RdmaChangelog.latestVersion(changelog))
                val changes = RdmaChangelog.changes(analysis, prev)
                if (changes.isEmpty()) {
                    val baked = RdmaChangelog.bake(changelog)
                    if (baked.h != changelog.h) {
                        RdmaChangelog.write(changelogFile, baked)
                        println("No new kernel changes; advanced H to ${baked.h} (baked accumulated changes).")
                    } else {
                        println("No kernel changes; changelog and H untouched (H=${changelog.h}).")
                    }
                    return
                }
                checkRemoval(changelog, analysis, changes, internalModules, polyfillOutDir, versionsDir)
                val updated = RdmaChangelog.append(changelog, analysis.moduleId, changes, bumpH = true)
                gate(updated, analysis, hostVersions, hotfix = false, minHostBefore = RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)))
                RdmaChangelog.write(changelogFile, updated)
                val derived = RdmaBaseline.write(analysis, versionsDir)
                println("Bumped counter to v${updated.h} (H moved). Wrote ${changelogFile.path}")
                println("Derived native state: ${derived.path}")
                writeSnapshots(updated, analysis, versionsDir)
            }

            "dynamic-release", "hotfix-release" -> {
                // 1. Compute + materialize the F polyfill (everything after H).
                val native = RdmaChangelog.nativeState(changelog, analysis.moduleId)
                val dirty = RdmaHashDiff.diff(analysis.declarations, native)
                val result = RdmaSubgraph.compute(analysis, dirty)

                println("added:   ${dirty.added.sorted().joinToString()}")
                println("removed: ${dirty.removed.sorted().joinToString()}")
                println("changed: ${dirty.changed.sorted().joinToString()}")
                println("entries: ${result.entries.sorted().joinToString()}")
                println("nodes:   ${result.nodes.sorted().joinToString()}")

                val files = RdmaPolyfillMaterializer.materialize(analysis, result)
                if (files.isEmpty()) {
                    println("No F polyfill to emit.")
                } else {
                    polyfillOutDir.mkdirs()
                    for (file in files) {
                        val target = File(polyfillOutDir, file.name)
                        target.writeText(file.content)
                        println("Wrote ${target.path}")
                    }
                }

                // 2. Commit point: append the changelog entry (counter bump, H unchanged).
                val prev = RdmaChangelog.manifest(changelog, analysis.moduleId, RdmaChangelog.latestVersion(changelog))
                val changes = RdmaChangelog.changes(analysis, prev)
                val updated = if (changes.isEmpty()) {
                    println("No kernel changes; changelog untouched (H=${changelog.h}).")
                    changelog
                } else {
                    checkRemoval(changelog, analysis, changes, internalModules, polyfillOutDir, versionsDir)
                    val appended = RdmaChangelog.append(changelog, analysis.moduleId, changes, bumpH = false)
                    gate(appended, analysis, hostVersions, hotfix, RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)))
                    RdmaChangelog.write(changelogFile, appended)
                    println("Bumped counter to v${RdmaChangelog.latestVersion(appended)} (H=${appended.h}). Wrote ${changelogFile.path}")
                    appended
                }
                writeSnapshots(updated, analysis, versionsDir)
            }

            else -> {
                System.err.println("unknown command: $command")
                kotlin.system.exitProcess(2)
            }
        }
    }

    /**
     * `plugin-release <plugin.json> <versionsDir> <bundlePath>` — a plugin-only release (§9):
     * bumps the plugin `version` (the kernel counter is **not** touched) and appends a
     * [PluginRelease] to `versions/plugins/<pluginId>.json`.
     */
    private fun pluginRelease(args: Array<String>) {
        val contractPath = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        val bundlePath = args.getOrNull(3)
        if (contractPath == null || versionsDir == null || bundlePath == null) {
            System.err.println("usage: plugin-release <plugin.json> <versionsDir> <bundlePath>")
            kotlin.system.exitProcess(2)
        }
        val contract = json.decodeFromString(RdmaPluginContract.serializer(), File(contractPath).readText())
        val bundleHash = RdmaContentHasher.sha256Bytes(File(bundlePath).readBytes())
        val existing = RdmaPluginReleases.read(versionsDir, contract.pluginId)
        val version = RdmaPluginReleases.nextVersion(existing)
        val release = PluginRelease(contract.copy(version = version), bundleHash)
        val file = RdmaPluginReleases.append(versionsDir, release)
        println("Released plugin '${contract.pluginId}' v$version (bundle $bundleHash). Wrote ${file.path}")
    }

    /** Runs the hotfix gate and prints the coverage warning; aborts the release on a gate failure. */
    private fun gate(updated: Changelog, analysis: RdmaAnalysis, hostVersions: List<Int>, hotfix: Boolean, minHostBefore: Int) {
        val floorAfter = RdmaFloor.compute(updated, analysis)
        val minHostAfter = RdmaReleaseGates.minHostOf(floorAfter)
        if (hotfix) {
            val errors = RdmaReleaseGates.checkHotfix(hotfix = true, minHostBefore, minHostAfter)
            for (e in errors) System.err.println("ERROR: $e")
            if (errors.isNotEmpty()) {
                System.err.println("Release rejected: a hotfix must not bump minHost.")
                kotlin.system.exitProcess(1)
            }
        }
        if (hostVersions.isNotEmpty()) {
            val warning = RdmaReleaseGates.coverageWarning(floorAfter, hostVersions)
            if (warning.isNotEmpty()) println("COVERAGE: $warning")
        }
    }

    /**
     * Validates removals against `@Deprecated` (§7.1) and materializes `R` polyfills from the
     * previous source snapshot for non-deprecated removed `@RDMA` top-level functions.
     */
    private fun checkRemoval(
        changelog: Changelog,
        analysis: RdmaAnalysis,
        changes: List<ChangelogChange>,
        internalModules: Set<String>,
        polyfillOutDir: File,
        versionsDir: File,
    ) {
        val removed = changes.filter { it.kind == ChangelogChangeKind.REMOVE }.map { it.fqn }.toSet()
        if (removed.isEmpty()) return

        val report = RdmaRemovalPolicy.validate(changelog, analysis.moduleId, removed, internalModules)
        for (e in report.errors) System.err.println("ERROR: $e")
        for (w in report.warnings) System.err.println("WARNING: $w")
        if (report.hasErrors) {
            System.err.println("Release rejected: removal without a prior @Deprecated period in an internal module.")
            kotlin.system.exitProcess(1)
        }

        val boundaries = RdmaRemovalPolicy.removedBoundaries(changelog, analysis.moduleId, removed)
        val rFunctions = boundaries
            .filter { it.symbolKind == RdmaSymbolKind.TOP_LEVEL_FUNCTION && !it.deprecated }
            .map { it.fqn }
        val rClasses = boundaries
            .filter { it.symbolKind == RdmaSymbolKind.CLASS && !it.deprecated }
            .map { it.fqn }

        if (rFunctions.isNotEmpty() || rClasses.isNotEmpty()) {
            val snapshot = RdmaSourceSnapshot.read(analysis.moduleId, versionsDir)
            polyfillOutDir.mkdirs()

            if (rFunctions.isNotEmpty()) {
                val files = RdmaPolyfillMaterializer.materializeRemoved(analysis, rFunctions, snapshot)
                if (files.isEmpty()) {
                    System.err.println("WARNING: no source snapshot found to materialize R polyfills for: ${rFunctions.joinToString()}")
                } else {
                    for (file in files) {
                        val target = File(polyfillOutDir, file.name)
                        target.writeText(file.content)
                        println("Wrote ${target.path}")
                    }
                }
            }

            if (rClasses.isNotEmpty()) {
                val classFiles = RdmaPolyfillMaterializer.materializeRemovedClasses(analysis, rClasses, snapshot)
                if (classFiles.isEmpty()) {
                    System.err.println("WARNING: no source snapshot found to materialize R class polyfills for: ${rClasses.joinToString()}")
                } else {
                    for (file in classFiles) {
                        val target = File(polyfillOutDir, file.name)
                        target.writeText(file.content)
                        println("Wrote ${target.path}")
                    }
                }
            }
        }
    }

    private fun writeSnapshots(changelog: Changelog, analysis: RdmaAnalysis, versionsDir: File) {
        val floors = RdmaFloor.compute(changelog, analysis)
        val floorLines = floors.entries
            .filter { it.value > 0 }
            .sortedBy { it.key }
            .joinToString("\n") { "  ${it.key} -> floor=${it.value}" }
        println("floor:")
        println(floorLines.ifEmpty { "  (none — all symbols emulatable)" })
        RdmaFloor.write(analysis.moduleId, floors, versionsDir)
        val sources = RdmaSourceSnapshot.write(analysis, versionsDir)
        println("Wrote floor map + source snapshot: ${sources.path}")
    }

    private fun readHostVersions(file: File): List<Int> =
        try {
            json.decodeFromString(ListSerializer(Int.serializer()), file.readText().trim())
        } catch (e: Exception) {
            System.err.println("WARNING: cannot parse telemetry file ${file.path}: ${e.message}")
            emptyList()
        }
}
