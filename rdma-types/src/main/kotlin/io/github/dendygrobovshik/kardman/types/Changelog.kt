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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kind of a single kernel declaration change recorded in the changelog. Mirrors the
 * `add` / `modify` / `remove` vocabulary of the compatibility design (see §2.1).
 */
@Serializable
enum class ChangelogChangeKind {
    @SerialName("add") ADD,
    @SerialName("modify") MODIFY,
    @SerialName("remove") REMOVE,
}

/**
 * One declaration change. [hash] is the full content hash of the declaration in its current
 * form for `add`/`modify`, and `null` for `remove` (the declaration no longer exists).
 *
 * [deprecated] records whether the declaration is `@Deprecated` at this change — the deprecation
 * status is folded along with the hash so removal can be validated against it (§7.1, S3/S4).
 *
 * [isRdma] and [kind] record the declaration's public-API role at this change, so a later
 * `remove` can distinguish a public `@RDMA` boundary symbol (removal requires prior
 * `@Deprecated`) from a private helper (removable freely) and from a class member (covered by
 * its enclosing class's deprecation).
 */
@Serializable
data class ChangelogChange(
    val fqn: String,
    val kind: ChangelogChangeKind,
    val hash: String? = null,
    val deprecated: Boolean = false,
    val isRdma: Boolean = false,
    val symbolKind: RdmaSymbolKind? = null,
)

/**
 * A single append-only changelog entry: one kernel release = one globally monotonic [version].
 * [time] is written as a safety net by the release tooling (only CI appends entries).
 */
@Serializable
data class ChangelogEntry(
    val version: Int,
    val time: String,
    val module: String,
    val changes: List<ChangelogChange> = emptyList(),
)

/**
 * The single source of truth for kernel versioning (see §2.1). Append-only; entries are
 * ordered by ascending [ChangelogEntry.version].
 *
 * [h] is the native version — the counter value baked into the app at the last
 * `static-release`. The manifest and the native state are both derived from [entries] by
 * folding, so they are never stored separately.
 */
@Serializable
data class Changelog(
    val h: Int = 0,
    val entries: List<ChangelogEntry> = emptyList(),
)

/**
 * A snapshot of every symbol's declaration source, keyed by FQN. Each value is a self-contained
 * compilation-unit fragment (the file's `package`/`import` header followed by the declaration
 * text). Persisted at release time so a later `remove` can materialize the `R` polyfill from the
 * symbol's last-known source (§5.1, §8.1).
 */
@Serializable
data class RdmaSymbolSources(
    val moduleId: String,
    val sources: Map<String, String> = emptyMap(),
)
