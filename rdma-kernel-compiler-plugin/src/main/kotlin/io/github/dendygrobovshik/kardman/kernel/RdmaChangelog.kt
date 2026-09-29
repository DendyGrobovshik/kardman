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
import io.github.dendygrobovshik.kardman.types.ChangelogEntry
import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/**
 * Read/write and fold of the append-only changelog — the single source of truth for kernel
 * versioning (§2.1). The manifest (state at version `V`) and the native state (state at `H`)
 * are derived here by folding entries, never stored separately.
 *
 * Versioning rules encoded here:
 *  - the counter is globally monotonic (`nextVersion` = max existing + 1);
 *  - `H` moves only on `static-release` (`bumpH`), never on `dynamic-release`;
 *  - a removed FQN is **tombstoned** forever: re-adding it is a hard error.
 */
object RdmaChangelog {
    private val json = Json { prettyPrint = true }

    fun read(file: File): Changelog =
        json.decodeFromString(Changelog.serializer(), file.readText())

    fun write(file: File, changelog: Changelog) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Changelog.serializer(), changelog))
    }

    fun toJson(changelog: Changelog): String =
        json.encodeToString(Changelog.serializer(), changelog)

    fun fromJson(text: String): Changelog =
        json.decodeFromString(Changelog.serializer(), text)

    /** The highest version seen so far, or 0 for an empty changelog. */
    fun latestVersion(changelog: Changelog): Int =
        changelog.entries.maxOfOrNull { it.version } ?: 0

    /** The next globally monotonic counter value. */
    fun nextVersion(changelog: Changelog): Int = latestVersion(changelog) + 1

    /**
     * Advances `H` to the latest recorded version without adding an entry. Used by
     * `static-release` when the accumulated changes are already in the changelog (recorded by
     * prior `dynamic-release`s): the static release just "bakes" them by moving `H` forward.
     */
    fun bake(changelog: Changelog): Changelog =
        changelog.copy(h = latestVersion(changelog))

    /** FQNs that were removed at any point — they can never be re-added. */
    fun tombstoned(changelog: Changelog): Set<String> =
        changelog.entries
            .flatMap { entry -> entry.changes.filter { it.kind == ChangelogChangeKind.REMOVE } }
            .map { it.fqn }
            .toSet()

    /** The removal timestamp of each removed FQN (from its entry's [ChangelogEntry.time]). */
    fun removedAt(changelog: Changelog): Map<String, Instant> {
        val result = mutableMapOf<String, Instant>()
        for (entry in changelog.entries) {
            val time = runCatching { Instant.parse(entry.time) }.getOrNull() ?: continue
            for (change in entry.changes) {
                if (change.kind == ChangelogChangeKind.REMOVE) result[change.fqn] = time
            }
        }
        return result
    }

    /**
     * The manifest of [module] at [version]: fold all entries with `version ≤ [version]` in
     * order; `add`/`modify` set the hash, `remove` deletes the FQN.
     */
    fun manifest(changelog: Changelog, module: String, version: Int): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in changelog.entries) {
            if (entry.version > version || entry.module != module) continue
            for (change in entry.changes) {
                when (change.kind) {
                    ChangelogChangeKind.ADD, ChangelogChangeKind.MODIFY -> change.hash?.let { result[change.fqn] = it }
                    ChangelogChangeKind.REMOVE -> result.remove(change.fqn)
                }
            }
        }
        return result
    }

    /** The native state of [module]: the manifest folded at `H`. */
    fun nativeState(changelog: Changelog, module: String): Map<String, String> =
        manifest(changelog, module, changelog.h)

    /**
     * The version at which each FQN was first `add`ed (its introduction version), for [module].
     * Non-emulatable symbols are introduced only via `static-release`, so this is the version at
     * which they became native — the value used as their `floor` (§5.3).
     */
    fun introductionVersions(changelog: Changelog, module: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for (entry in changelog.entries) {
            if (entry.module != module) continue
            for (change in entry.changes) {
                if (change.kind == ChangelogChangeKind.ADD && change.fqn !in result) {
                    result[change.fqn] = entry.version
                }
            }
        }
        return result
    }

    /** Converts a diff between current declarations and a baseline into changelog changes. */
    fun changes(analysis: RdmaAnalysis, baseline: Map<String, String>): List<ChangelogChange> {
        val dirty = RdmaHashDiff.diff(analysis.declarations, baseline)
        if (dirty.isEmpty) return emptyList()
        val declByFqn = analysis.declarations.associateBy { it.fqn }
        val changes = mutableListOf<ChangelogChange>()
        for (fqn in dirty.added.sorted()) {
            val d = declByFqn[fqn]
            changes += ChangelogChange(fqn, ChangelogChangeKind.ADD, d?.hash, d?.deprecated == true, d?.isRdma == true, d?.kind)
        }
        for (fqn in dirty.changed.sorted()) {
            val d = declByFqn[fqn]
            changes += ChangelogChange(fqn, ChangelogChangeKind.MODIFY, d?.hash, d?.deprecated == true, d?.isRdma == true, d?.kind)
        }
        for (fqn in dirty.removed.sorted()) changes += ChangelogChange(fqn, ChangelogChangeKind.REMOVE, null)
        return changes
    }

    /** The set of FQNs currently `@Deprecated` in [module] (folded from all entries). */
    fun deprecated(changelog: Changelog, module: String): Set<String> {
        val result = mutableSetOf<String>()
        for (entry in changelog.entries) {
            if (entry.module != module) continue
            for (change in entry.changes) {
                when (change.kind) {
                    ChangelogChangeKind.ADD, ChangelogChangeKind.MODIFY ->
                        if (change.deprecated) result.add(change.fqn) else result.remove(change.fqn)
                    ChangelogChangeKind.REMOVE -> result.remove(change.fqn)
                }
            }
        }
        return result
    }

    /** The set of FQNs currently `@Deprecated` across all modules (folded from all entries). */
    fun deprecatedAll(changelog: Changelog): Set<String> {
        val result = mutableSetOf<String>()
        for (entry in changelog.entries) {
            for (change in entry.changes) {
                when (change.kind) {
                    ChangelogChangeKind.ADD, ChangelogChangeKind.MODIFY ->
                        if (change.deprecated) result.add(change.fqn) else result.remove(change.fqn)
                    ChangelogChangeKind.REMOVE -> result.remove(change.fqn)
                }
            }
        }
        return result
    }

    /**
     * The last-known metadata (isRdma + symbol kind) of each FQN in [module], folded from the
     * changelog. Used by the removal gate to tell a public `@RDMA` boundary symbol from a private
     * helper or a class member.
     */
    fun lastKnown(changelog: Changelog, module: String): Map<String, ChangelogChange> {
        val result = mutableMapOf<String, ChangelogChange>()
        for (entry in changelog.entries) {
            if (entry.module != module) continue
            for (change in entry.changes) {
                when (change.kind) {
                    ChangelogChangeKind.ADD, ChangelogChangeKind.MODIFY -> result[change.fqn] = change
                    ChangelogChangeKind.REMOVE -> result.remove(change.fqn)
                }
            }
        }
        return result
    }

    /**
     * Appends a new entry with the next counter value. `bumpH = true` (static release) also
     * moves `H` to the new version. Re-adding a tombstoned FQN throws.
     */
    fun append(
        changelog: Changelog,
        module: String,
        changes: List<ChangelogChange>,
        bumpH: Boolean,
    ): Changelog {
        if (changes.isEmpty()) return changelog
        val version = nextVersion(changelog)
        val tombs = tombstoned(changelog)
        for (change in changes) {
            if ((change.kind == ChangelogChangeKind.ADD || change.kind == ChangelogChangeKind.MODIFY) && change.fqn in tombs) {
                error("FQN '${change.fqn}' was tombstoned (removed) and cannot be re-added")
            }
        }
        val entry = ChangelogEntry(
            version = version,
            time = Instant.now().toString(),
            module = module,
            changes = changes,
        )
        return changelog.copy(
            h = if (bumpH) version else changelog.h,
            entries = changelog.entries + entry,
        )
    }
}
