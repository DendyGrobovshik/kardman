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
import io.github.dendygrobovshik.kardman.types.RdmaToml
import io.github.dendygrobovshik.kardman.types.ToolchainPins
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
 *   plugin-release   [--no-bump] <versionsDir> <plugin.json> (<generation> <bundlePath>)+
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
        if (command == "generation-release") {
            generationRelease(args)
            return
        }
        if (command == "retire-generation") {
            retireGeneration(args)
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
        val oldestActiveKernel = RdmaToml.load(resolveRdmaConfigFile(args)).kernel.oldestActiveKernel

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
                gate(updated, analysis, hostVersions, hotfix = false, minHostBefore = RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)), oldestActiveKernel = oldestActiveKernel)
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
                    gate(appended, analysis, hostVersions, hotfix, RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)), oldestActiveKernel = oldestActiveKernel)
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

    /** Resolves the `--rdmaConfig <path>` (or `--rdmaConfig=<path>`) flag, defaulting to `rdma.toml`. */
    private fun resolveRdmaConfigFile(args: Array<String>): File {
        for (k in args.indices) {
            if (args[k] == "--rdmaConfig" && k + 1 < args.size) return File(args[k + 1])
            if (args[k].startsWith("--rdmaConfig=")) return File(args[k].removePrefix("--rdmaConfig="))
        }
        return File("rdma.toml")
    }

    /** Returns the positional args from index [from], skipping any `--rdmaConfig` flag/value. */
    private fun stripRdmaConfig(args: Array<String>, from: Int): List<String> {
        val result = mutableListOf<String>()
        var k = from
        while (k < args.size) {
            when {
                args[k] == "--rdmaConfig" -> k += 2
                args[k].startsWith("--rdmaConfig=") -> k += 1
                else -> { result.add(args[k]); k++ }
            }
        }
        return result
    }

    /**
     * `plugin-release [--no-bump] <versionsDir> <plugin.json> (<generation> <bundlePath>)+` —
     * a plugin-only release (§9, toolchain_compatibility.md §6–§7). The plugin `version` is
     * bumped **once** (globally, `max` across all generations + 1) and the bundle is released
     * for **every live generation** listed in `toolchain.json` (the kernel counter is **not**
     * touched). `--no-bump` records the *current* version instead (used to bootstrap a newly
     * created generation) and releases only the generations explicitly listed.
     *
     * Every release prints a per-generation report: `built-ok` for a generation that got a
     * bundle, `blocked` for a live generation with no bundle (the store keeps serving it the
     * last successfully built version), and `ignored` for a bundle supplied for a generation
     * that is not active.
     */
    private fun pluginRelease(args: Array<String>) {
        var i = 1
        var noBump = false
        if (args.getOrNull(i) == "--no-bump") {
            noBump = true
            i++
        }
        val versionsDir = args.getOrNull(i++)?.let(::File)
        val contractPath = args.getOrNull(i++)
        if (versionsDir == null || contractPath == null) {
            System.err.println("usage: plugin-release [--no-bump] <versionsDir> <plugin.json> (<generation> <bundlePath>)+")
            kotlin.system.exitProcess(2)
        }
        val rest = stripRdmaConfig(args, from = i)
        if (rest.size % 2 != 0) {
            System.err.println("plugin-release: <generation> <bundlePath> must come in pairs, got: ${rest.joinToString()}")
            kotlin.system.exitProcess(2)
        }
        val provided = LinkedHashMap<String, File>()
        for (k in rest.indices step 2) provided[rest[k]] = File(rest[k + 1])

        val contract = json.decodeFromString(RdmaPluginContract.serializer(), File(contractPath).readText())
        val existing = RdmaPluginReleases.read(versionsDir, contract.pluginId)
        val version = if (noBump) RdmaPluginReleases.currentVersion(existing) else RdmaPluginReleases.nextVersion(existing)

        val toolchain = RdmaToolchain.read(versionsDir)
        val floor = RdmaToml.load(resolveRdmaConfigFile(args)).toolchain.oldestActiveGeneration
        val active = RdmaToolchain.liveGenerations(toolchain, floor).map { it.g }
        if (!noBump && active.isEmpty()) {
            System.err.println("plugin-release: no live generations (run generation-release first).")
            kotlin.system.exitProcess(1)
        }

        val written = LinkedHashMap<String, String>()
        for (g in (if (noBump) provided.keys else active)) {
            val bundle = provided[g] ?: continue
            val bundleHash = RdmaContentHasher.sha256Bytes(bundle.readBytes())
            val release = PluginRelease(contract.copy(version = version, builtAgainstToolchain = g), bundleHash)
            RdmaPluginReleases.append(versionsDir, release)
            written[g] = bundleHash
        }
        if (written.isEmpty()) {
            System.err.println("plugin-release: nothing was released (no bundle for any targeted generation).")
            kotlin.system.exitProcess(1)
        }

        println("plugin-release '${contract.pluginId}' v$version (${if (noBump) "inherit" else "bump"}):")
        if (noBump) {
            for ((g, hash) in written) println("  $g  built-ok     bundle $hash")
        } else {
            for (g in active) {
                val hash = written[g]
                if (hash != null) println("  $g  built-ok     bundle $hash")
                else println("  $g  blocked      (no bundle provided; serving last built version)")
            }
            for (g in provided.keys) {
                if (g !in active) println("  $g  ignored      (generation not active)")
            }
        }
    }

    /** `generation-release <g> <versionsDir> [alias]` — records a new toolchain generation and, on the first generation, sets the FIFO floor. */
    private fun generationRelease(args: Array<String>) {
        val g = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        if (g == null || versionsDir == null) {
            System.err.println("usage: generation-release <g> <versionsDir> [alias]")
            kotlin.system.exitProcess(2)
        }
        val alias = args.getOrNull(3) ?: ""
        val toolchain = RdmaToolchain.append(RdmaToolchain.read(versionsDir), g, alias, ToolchainPins())
        val file = RdmaToolchain.write(versionsDir, toolchain)

        val configFile = resolveRdmaConfigFile(args)
        val floor = RdmaToml.load(configFile).toolchain.oldestActiveGeneration
        if (floor.isEmpty()) {
            // The first generation becomes the oldest live generation (the floor).
            val first = toolchain.generations.firstOrNull()?.g ?: g
            RdmaToml.writeOldestActiveGeneration(configFile, first)
        }
        println("Released toolchain generation '$g' (alias '$alias'). Wrote ${file.path}")
    }

    /** `retire-generation <g> <versionsDir>` — retires the oldest live generation (FIFO) and advances the floor. */
    private fun retireGeneration(args: Array<String>) {
        val g = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        if (g == null || versionsDir == null) {
            System.err.println("usage: retire-generation <g> <versionsDir>")
            kotlin.system.exitProcess(2)
        }
        val configFile = resolveRdmaConfigFile(args)
        val toolchain = RdmaToolchain.read(versionsDir)
        val gens = toolchain.generations
        val idx = gens.indexOfFirst { it.g == g }
        if (idx < 0) {
            System.err.println("retire-generation: generation '$g' not found.")
            kotlin.system.exitProcess(1)
        }
        val floor = RdmaToml.load(configFile).toolchain.oldestActiveGeneration
        val oldestLive = RdmaToolchain.liveGenerations(toolchain, floor).firstOrNull()?.g
        if (oldestLive != g) {
            System.err.println("retire-generation: '$g' is not the oldest live generation (oldest is '$oldestLive'); FIFO retirement only.")
            kotlin.system.exitProcess(1)
        }

        val successor = gens.getOrNull(idx + 1)?.g ?: ""
        val retired = RdmaToolchain.retire(toolchain, g)
        val file = RdmaToolchain.write(versionsDir, retired)
        RdmaToml.writeOldestActiveGeneration(configFile, successor)
        println("Retired toolchain generation '$g'; oldestActiveGeneration advanced to '${successor.ifEmpty { "<none>" }}'. Wrote ${file.path}")
    }

    /** Runs the hotfix gate and prints the coverage warning; aborts the release on a gate failure. */
    private fun gate(updated: Changelog, analysis: RdmaAnalysis, hostVersions: List<Int>, hotfix: Boolean, minHostBefore: Int, oldestActiveKernel: Int? = null) {
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
        if (oldestActiveKernel != null && minHostAfter > oldestActiveKernel) {
            println("COVERAGE: minHost rises to $minHostAfter, above the support floor oldestActiveKernel=$oldestActiveKernel (hosts H in [$oldestActiveKernel, $minHostAfter) stop receiving this module).")
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
