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
import io.github.dendygrobovshik.kardman.types.RdmaSymbolKind

/**
 * Enforces the `@Deprecated`-before-removal rule (§7.1, S3/S4, §5.5).
 *
 * Removing a public `@RDMA` boundary symbol (a class or a top-level function/property) without a
 * prior `@Deprecated` period is:
 *  - for an `internal` (framework-owned) module — an **error** (the release is rejected);
 *  - for a user module — a **warning**: the release passes and we back the user up by serving an
 *    `R` polyfill for the removed symbol.
 *
 * Private helpers and class members are exempt: they are not public API (helpers are removable
 * freely, members are covered by their enclosing class's deprecation).
 */
object RdmaRemovalPolicy {

    data class Report(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val hasErrors: Boolean get() = errors.isNotEmpty()
    }

    fun validate(
        changelog: Changelog,
        module: String,
        removedFqns: Set<String>,
        internalModules: Set<String>,
    ): Report {
        if (removedFqns.isEmpty()) return Report()
        val lastKnown = RdmaChangelog.lastKnown(changelog, module)
        val isInternal = module in internalModules
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        for (fqn in removedFqns.sorted()) {
            val info = lastKnown[fqn] ?: continue
            if (!info.isRdma) continue
            val symbolKind = info.symbolKind ?: continue
            val boundary = symbolKind == RdmaSymbolKind.CLASS ||
                symbolKind == RdmaSymbolKind.TOP_LEVEL_FUNCTION ||
                symbolKind == RdmaSymbolKind.TOP_LEVEL_PROPERTY
            if (!boundary) continue
            if (info.deprecated) continue

            if (isInternal) {
                errors += "internal removal of '@RDMA $fqn' without a prior @Deprecated period is rejected"
            } else {
                warnings += "user-module removal of '@RDMA $fqn' without @Deprecated; serving an R polyfill"
            }
        }
        return Report(errors, warnings)
    }

    /**
     * The removed `@RDMA` boundary symbols (classes / top-level functions / top-level properties)
     * among [removedFqns], with their last-known metadata. These are the symbols that, when
     * removed without a completed migration, need an `R` polyfill.
     */
    fun removedBoundaries(changelog: Changelog, module: String, removedFqns: Set<String>): List<ChangelogChange> {
        if (removedFqns.isEmpty()) return emptyList()
        val lastKnown = RdmaChangelog.lastKnown(changelog, module)
        return removedFqns.mapNotNull { fqn ->
            val info = lastKnown[fqn] ?: return@mapNotNull null
            if (!info.isRdma) return@mapNotNull null
            val kind = info.symbolKind ?: return@mapNotNull null
            val boundary = kind == RdmaSymbolKind.CLASS ||
                kind == RdmaSymbolKind.TOP_LEVEL_FUNCTION ||
                kind == RdmaSymbolKind.TOP_LEVEL_PROPERTY
            if (!boundary) null else info
        }.sortedBy { it.fqn }
    }
}
