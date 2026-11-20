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
import io.github.dendygrobovshik.kardman.types.PolyfillRef
import io.github.dendygrobovshik.kardman.types.ReleaseFPlan
import io.github.dendygrobovshik.kardman.types.ReleasePlan
import io.github.dendygrobovshik.kardman.types.ReleaseType
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
 *   publish-bundle   <versionsDir> <key> <bundleFile>
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
        if (command == "publish-bundle") {
            publishBundle(args)
            return
        }

        if (command == "commit-release") {
            commitRelease(args)
            return
        }
        if (command == "generate-signing-key") {
            val (privateKey, publicKey) = RdmaBundleFiles.generateKeyPair()
            println("RDMA_SIGNING_KEY (keep secret, CI only):\n$privateKey")
            println("public key (rdma.toml [signing] publicKeys):\n$publicKey")
            return
        }

        val analysisPath = args.getOrNull(1)
        val versionsDir = args.getOrNull(2)?.let(::File)
        if (command !in setOf("static-release", "dynamic-release", "hotfix-release") || analysisPath == null || versionsDir == null) {
            System.err.println(
                "usage: (static-release|dynamic-release|hotfix-release) <analysis.json[,analysis.json]> <versionsDir> <workDir> <internalModules> [telemetryFile] [--dry-run]\n" +
                    "       commit-release <workDir> <versionsDir>",
            )
            kotlin.system.exitProcess(2)
        }
        // One analysis per platform (android, ios): a change on either platform is a change.
        val analysis = RdmaAnalysisMerge.merge(
            analysisPath.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { json.decodeFromString(RdmaAnalysis.serializer(), File(it).readText()) },
        )
        val workDir = args.getOrNull(3)?.let { it.trim().takeIf(String::isNotEmpty)?.let(::File) } ?: File("build/rdma-release")
        val internalModules = args.getOrNull(4)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: setOf("internal")
        val telemetryFile = args.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("--") }?.let(::File)
        val hostVersions = telemetryFile?.let(::readHostVersions).orEmpty()
        val config = RdmaToml.load(resolveRdmaConfigFile(args))
        prepareRelease(
            command = command!!,
            analysisPath = analysisPath,
            analysis = analysis,
            versionsDir = versionsDir,
            workDir = workDir,
            internalModules = internalModules,
            hostVersions = hostVersions,
            oldestActiveKernel = config.kernel.oldestActiveKernel,
            generations = RdmaToolchain.liveGenerations(RdmaToolchain.read(versionsDir), config.toolchain.oldestActiveGeneration).map { it.g },
            dryRun = args.contains("--dry-run"),
        )
    }

    /**
     * Prepares a kernel release (compatibility.md §9): runs every check and writes, into
     * [workDir] only, the polyfill sources to build and `plan.json` — the changelog entry, which
     * hosts each `F` serves, the generations to build for. `versions/` does not change; after
     * the bundles are built (and tested), `commit-release` publishes them and writes the
     * changelog — the commit point.
     *
     * `F` is prepared for every supported host `H` (`RdmaChangelog.hosts`) whose native state of
     * the module differs from the new code, once per distinct native state: hosts sharing a
     * state share a bundle. A dynamic release is all or nothing across hosts. A static release
     * delivers its changes to older hosts only where they can be delivered; a host group whose
     * changes cannot gets no new `F`, and the store keeps serving it plugins that do not need it.
     */
    private fun prepareRelease(
        command: String,
        analysisPath: String,
        analysis: RdmaAnalysis,
        versionsDir: File,
        workDir: File,
        internalModules: Set<String>,
        hostVersions: List<Int>,
        oldestActiveKernel: Int?,
        generations: List<String>,
        dryRun: Boolean,
    ) {
        val module = analysis.moduleId
        val changelog = File(versionsDir, "changelog.json").let { if (it.isFile) RdmaChangelog.read(it) else Changelog() }
        val static = command == "static-release"
        val hotfix = command == "hotfix-release"

        // Changes are diffed against the changelog head: changes already recorded by earlier
        // releases are not recorded again.
        val head = RdmaChangelog.manifest(changelog, module, RdmaChangelog.latestVersion(changelog))
        val changes = RdmaChangelog.changes(analysis, head)
        val pending = when {
            changes.isNotEmpty() -> RdmaChangelog.append(
                changelog, module, changes, bumpH = static,
                type = when { static -> ReleaseType.STATIC; hotfix -> ReleaseType.HOTFIX; else -> ReleaseType.DYNAMIC },
            )
            static -> RdmaChangelog.bake(changelog, module)
            else -> changelog
        }

        val problems = mutableListOf<String>()
        problems += signatureErrors(analysis, versionsDir)
        if (changes.isNotEmpty()) {
            problems += removalErrors(changelog, analysis, changes, internalModules)
            problems += gateErrors(pending, analysis, hotfix, RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)))
        }

        // F: one per distinct native state of the module among the supported hosts.
        val hosts = RdmaChangelog.hosts(pending, oldestActiveKernel)
        val byState = hosts.groupBy { RdmaChangelog.manifest(pending, module, it) }
        data class Group(val hosts: List<Int>, val dirty: RdmaDirtySet, val result: PolyfillResult, val problems: List<String>)
        val groups = byState.mapNotNull { (native, groupHosts) ->
            val dirty = RdmaHashDiff.diff(analysis.declarations, native)
            val result = RdmaSubgraph.compute(analysis, dirty)
            if (result.isEmpty) return@mapNotNull null
            val groupProblems = hotfixErrors(analysis, dirty, result) +
                nonEmulatableErrors(analysis, result) +
                RdmaStateSplit.detect(analysis, dirty, result).map { it.message() }
            Group(groupHosts, dirty, result, groupProblems)
        }
        for (g in groups) {
            println("hosts ${g.hosts.joinToString()}: entries ${g.result.entries.sorted().joinToString()}")
        }
        val deliverable = if (static) {
            groups.filter { g ->
                g.problems.isEmpty().also { ok ->
                    if (!ok) println("NOT DELIVERED to hosts ${g.hosts.joinToString()} (they keep their native version; plugins needing it are not served there): ${g.problems.joinToString("; ")}")
                }
            }
        } else {
            groups.forEach { g -> problems += g.problems.map { "[hosts ${g.hosts.joinToString()}] $it" } }
            groups
        }
        if (problems.isNotEmpty()) {
            reject(problems.distinct(), groups.flatMap { it.result.entries }.toSet())
        }
        if (dryRun) {
            println(
                "Dry run: no problems. The release would record ${changes.size} change(s)" +
                    (if (static) " and move H" else "") +
                    " and build F for hosts ${deliverable.joinToString("; ") { it.hosts.joinToString(",") }.ifEmpty { "none" }}. Nothing was written.",
            )
            return
        }

        workDir.deleteRecursively()
        workDir.mkdirs()
        val fPlans = deliverable.map { g ->
            val state = RdmaContentHasher.sha256(byState.entries.first { it.value == g.hosts }.key.toSortedMap().toString()).take(16)
            val dir = File(workDir, "F/$state").apply { mkdirs() }
            for (file in RdmaPolyfillMaterializer.materialize(analysis, g.result)) File(dir, file.name).writeText(file.content)
            ReleaseFPlan(state, g.hosts, dir.path)
        }
        val rDir = File(workDir, "R")
        if (changes.isNotEmpty()) checkRemoval(changelog, analysis, changes, internalModules, rDir, versionsDir)
        if (changes.isNotEmpty()) {
            gate(pending, analysis, hostVersions, hotfix = false, RdmaReleaseGates.minHostOf(RdmaFloor.compute(changelog, analysis)), oldestActiveKernel)
        }
        val plan = ReleasePlan(
            module = module,
            command = command,
            analysis = analysisPath,
            changelog = pending,
            version = RdmaChangelog.latestVersion(pending),
            generations = generations.ifEmpty { listOf("") },
            f = fPlans,
            rDir = rDir.takeIf { d -> d.listFiles { f -> f.extension == "kt" }?.isNotEmpty() == true }?.path,
        )
        File(workDir, "plan.json").writeText(prettyJson.encodeToString(ReleasePlan.serializer(), plan))
        println("Prepared ${command} of '$module' in ${workDir.path}: F for ${fPlans.size} host state(s), R ${if (plan.rDir != null) "yes" else "no"}. Build the bundles, then run commit-release.")
    }

    /**
     * `commit-release <workDir> <versionsDir>` — the commit point (compatibility.md §9): puts the
     * built polyfill bundles into the store (content-addressed, with their gzip copy and, when a
     * signing key is configured, signature), updates `polyfills.json`, then writes the changelog
     * and the release snapshots. A generation without a built bundle is reported `blocked`: its
     * hosts keep the polyfills they had.
     */
    private fun commitRelease(args: Array<String>) {
        val workDir = args.getOrNull(1)?.let(::File)
        val versionsDir = args.getOrNull(2)?.let(::File)
        val planFile = workDir?.let { File(it, "plan.json") }
        if (planFile == null || !planFile.isFile || versionsDir == null) {
            System.err.println("usage: commit-release <workDir> <versionsDir> (a prepared release with plan.json)")
            kotlin.system.exitProcess(2)
        }
        val plan = json.decodeFromString(ReleasePlan.serializer(), planFile.readText())
        var index = RdmaPolyfillIndex.read(versionsDir)
        for (g in plan.generations) {
            val label = g.ifEmpty { "-" }
            for (f in plan.f) {
                val bundle = File(f.dir, RdmaPolyfillIndex.bundleName(g))
                if (!bundle.isFile) { println("  F hosts ${f.hosts.joinToString()} gen $label  blocked (no bundle built)"); continue }
                val bytes = bundle.readBytes()
                val hash = RdmaContentHasher.sha256Bytes(bytes)
                RdmaBundleFiles.publish(versionsDir, hash, bytes)
                index = RdmaPolyfillIndex.putF(index, plan.module, f.hosts, g, PolyfillRef(hash, plan.version))
                println("  F hosts ${f.hosts.joinToString()} gen $label  built-ok  $hash")
            }
            val rDir = plan.rDir ?: continue
            val bundle = File(rDir, RdmaPolyfillIndex.bundleName(g))
            if (!bundle.isFile) { println("  R gen $label  blocked (no bundle built)"); continue }
            val bytes = bundle.readBytes()
            val hash = RdmaContentHasher.sha256Bytes(bytes)
            RdmaBundleFiles.publish(versionsDir, hash, bytes)
            index = RdmaPolyfillIndex.addR(index, plan.module, g, PolyfillRef(hash, plan.version))
            println("  R gen $label  built-ok  $hash")
        }
        RdmaPolyfillIndex.write(versionsDir, index)
        val changelogFile = File(versionsDir, "changelog.json")
        RdmaChangelog.write(changelogFile, plan.changelog)
        val analysis = RdmaAnalysisMerge.merge(
            plan.analysis.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { json.decodeFromString(RdmaAnalysis.serializer(), File(it).readText()) },
        )
        if (plan.command == "static-release") RdmaBaseline.write(analysis, versionsDir)
        writeSnapshots(plan.changelog, analysis, versionsDir)
        println("Committed ${plan.command} of '${plan.module}': v${plan.version} (H=${plan.changelog.h}). Wrote ${changelogFile.path}")
    }

    private val prettyJson = Json { prettyPrint = true }

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
            // The bundle goes into the store before its release record, so a recorded release
            // always has its bundle (and the bundle's gzip copy, §8.1).
            RdmaBundleFiles.publish(versionsDir, bundleHash, bundle.readBytes())
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

    /**
     * `publish-bundle <versionsDir> <key> <bundleFile>` — puts a bundle into the store under its
     * content key (`F:<module>:<H>[:<G>]`, `R:<module>[:<G>]`, or a plugin bundle hash), with
     * its release-time gzip copy (§8.1). Plugin bundles are published by `plugin-release`.
     */
    private fun publishBundle(args: Array<String>) {
        val versionsDir = args.getOrNull(1)?.let(::File)
        val key = args.getOrNull(2)
        val bundle = args.getOrNull(3)?.let(::File)
        if (versionsDir == null || key.isNullOrBlank() || bundle == null || !bundle.isFile) {
            System.err.println("usage: publish-bundle <versionsDir> <key> <bundleFile>")
            kotlin.system.exitProcess(2)
        }
        val file = RdmaBundleFiles.publish(versionsDir, key, bundle.readBytes())
        println("published bundle $key -> ${file.path} (+${RdmaBundleFiles.GZIP_SUFFIX})")
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
        RdmaBaseline.writeSignatures(analysis, versionsDir)
    }

    /**
     * Structural signature gate: rejects reorder / remove / rename / type-change / non-trailing
     * insertion / adding a required parameter on any `@RDMA` callable. Only appending trailing
     * defaulted parameters is compatible.
     */
    private fun signatureErrors(analysis: RdmaAnalysis, versionsDir: File): List<String> {
        val oldSignatures = RdmaBaseline.readSignatures(analysis.moduleId, versionsDir)
        return RdmaSignatureClassifier.classify(oldSignatures, analysis.declarations).errors
            .map { "$it (only trailing defaulted parameters may be appended)" }
    }

    private fun checkSignature(analysis: RdmaAnalysis, versionsDir: File) {
        val oldSignatures = RdmaBaseline.readSignatures(analysis.moduleId, versionsDir)
        val report = RdmaSignatureClassifier.classify(oldSignatures, analysis.declarations)
        for (e in report.errors) System.err.println("ERROR: $e")
        if (report.hasErrors) {
            System.err.println("Release rejected: incompatible @RDMA signature change (only trailing defaulted parameters may be appended).")
            kotlin.system.exitProcess(1)
        }
    }

    /**
     * Release gate for partial hotfixes (runtime_rework §2.7, §6.8): an F polyfill may change
     * an existing symbol only if every call site of it runs the new version. Added symbols
     * have no native call sites on older hosts and pass.
     */
    private fun hotfixErrors(analysis: RdmaAnalysis, dirty: RdmaDirtySet, result: PolyfillResult): List<String> {
        val byFqn = analysis.declarations.associateBy { it.fqn }
        return result.entries.filter { it !in dirty.added }.sorted().mapNotNull { fqn ->
            val decl = byFqn[fqn] ?: return@mapNotNull null
            val reason = when (decl.kind) {
                RdmaSymbolKind.CLASS -> "class members are not hotfixable yet"
                RdmaSymbolKind.TOP_LEVEL_PROPERTY -> "top-level properties are not hotfixable yet"
                else -> decl.hotfixBlocker
            } ?: return@mapNotNull null
            "$fqn changed, but $reason: some call sites would keep running the old version"
        }
    }

    /**
     * Code the polyfill would have to run in JS but that does not exist there (§5.2): a node
     * of the polyfill calling platform or JVM/Native-only API, or a widget. A manual polyfill
     * of the entry replaces it, together with its closure.
     */
    private fun nonEmulatableErrors(analysis: RdmaAnalysis, result: PolyfillResult): List<String> {
        val manual = analysis.polyfills.map { it.target }.toSet()
        val boundaries = analysis.declarations.filter { it.isRdma }.map { RdmaSubgraph.nodeOf(it) }.toSet()
        val adjacency = analysis.uses.groupBy({ it.from }, { it.to })
        val emitted = mutableSetOf<String>()
        for (entry in result.entries - manual) {
            val stack = ArrayDeque(listOf(entry))
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!emitted.add(node)) continue
                for (next in adjacency[node].orEmpty()) if (next !in boundaries) stack.addLast(next)
            }
        }
        return analysis.declarations
            .filter { !it.emulatable && it.fqn !in manual && RdmaSubgraph.nodeOf(it) in emitted }
            .sortedBy { it.fqn }
            .map { decl ->
                val why = if (decl.nativeRefs.isNotEmpty()) "it calls ${decl.nativeRefs.joinToString()}, which does not exist in the plugin runtime"
                else "widget polyfills are not supported yet"
                "${decl.fqn} cannot run as a polyfill: $why. Write a manual polyfill (@Polyfill) or ship it with a static release"
            }
    }

    /** Removal without a prior `@Deprecated` period in an internal module (§7.1). */
    private fun removalErrors(changelog: Changelog, analysis: RdmaAnalysis, changes: List<ChangelogChange>, internalModules: Set<String>): List<String> {
        val removed = changes.filter { it.kind == ChangelogChangeKind.REMOVE }.map { it.fqn }.toSet()
        if (removed.isEmpty()) return emptyList()
        return RdmaRemovalPolicy.validate(changelog, analysis.moduleId, removed, internalModules).errors
    }

    private fun gateErrors(updated: Changelog, analysis: RdmaAnalysis, hotfix: Boolean, minHostBefore: Int): List<String> {
        val minHostAfter = RdmaReleaseGates.minHostOf(RdmaFloor.compute(updated, analysis))
        return RdmaReleaseGates.checkHotfix(hotfix, minHostBefore, minHostAfter)
    }

    /** Prints every problem and what does not ship because of them, then exits (nothing written). */
    private fun reject(problems: List<String>, entries: Set<String>): Nothing {
        System.err.println("Release rejected — nothing was written. ${problems.size} problem(s):")
        for (p in problems) System.err.println("ERROR: $p")
        if (entries.isNotEmpty()) {
            System.err.println("Not shipped because of them (a release ships all of its changes or none): ${entries.sorted().joinToString()}")
        }
        System.err.println("Fix the problems above, or ship the changes with a static release.")
        kotlin.system.exitProcess(1)
    }

    private fun readHostVersions(file: File): List<Int> =
        try {
            json.decodeFromString(ListSerializer(Int.serializer()), file.readText().trim())
        } catch (e: Exception) {
            System.err.println("WARNING: cannot parse telemetry file ${file.path}: ${e.message}")
            emptyList()
        }
}
