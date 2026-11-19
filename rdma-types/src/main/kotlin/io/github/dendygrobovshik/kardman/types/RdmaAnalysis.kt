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

/** The granularity at which a kernel declaration is hashed and diffed. */
@Serializable
enum class RdmaSymbolKind {
    TOP_LEVEL_FUNCTION,
    TOP_LEVEL_PROPERTY,
    CLASS,
    CLASS_METHOD,
    CLASS_PROPERTY,
}

/**
 * Source range of a declaration within its source file. Offsets are UTF-16 code unit offsets
 * into the file's text. `null` for declarations without a stable source range (never hashed).
 *
 * For a class this covers the full class body; for every other kind it covers the whole
 * declaration (signature + body). It is what Phase 3 uses to materialize the polyfill source.
 */
@Serializable
data class RdmaSourceRange(
    val file: String,
    val start: Int,
    val end: Int,
)

/**
 * A single hashed kernel declaration. `isRdma` is true when the declaration (or its enclosing
 * class) is marked `@RDMA`, i.e. it lives inside the native/exported boundary.
 *
 * The [hash] is computed over the class header (signature/members signature) for classes and over
 * the whole declaration for everything else, so class-member changes are detected independently.
 *
 * [emulatable] is `false` when the symbol cannot have a JS polyfill: it calls code that does not
 * exist in the plugin runtime ([nativeRefs]) and has no manual polyfill, or it is a widget (the
 * materializer cannot build widget polyfills yet). Such symbols are non-emulatable anchors that
 * set the `floor` (§5.2, §5.3).
 *
 * [deprecated] is `true` when the declaration is annotated with Kotlin's `@Deprecated` — the
 * mandatory first step before removal (§7.1, S3).
 *
 * [isMutable] is `true` for `var` properties; used by shared-state detection (§6.5) to decide
 * whether a polyfill can be cut into delta pieces.
 *
 * [signature] is the ordered parameter list (name, type, hasDefault) of a callable boundary
 * symbol (top-level function, class method, or a class's primary constructor). It is what the
 * release-time [RdmaSignatureClassifier] diffs to reject incompatible signature changes
 * (reorder / remove / rename / type change / non-trailing insertion / adding a required param).
 */
@Serializable
data class RdmaDeclaration(
    val fqn: String,
    val kind: RdmaSymbolKind,
    val hash: String,
    val isRdma: Boolean,
    val sourceRange: RdmaSourceRange? = null,
    val emulatable: Boolean = true,
    val deprecated: Boolean = false,
    val isMutable: Boolean = false,
    val signature: List<RdmaParameterSignature>? = null,
    /**
     * Why a change of this top-level function cannot be shipped as a hotfix yet (some call
     * sites could not run the new version, docs/design/runtime_rework.md §2.7, §6.8);
     * null when it can. Classes and properties are never hotfixable yet, whatever this says.
     */
    val hotfixBlocker: String? = null,
    /** External declarations this one calls that do not exist in the plugin runtime (§5.2). */
    val nativeRefs: List<String> = emptyList(),
    /** A property's value type FQN (`enum:<fqn>` for an enum); decides the runtime-local exemption (§6.6). */
    val valueType: String? = null,
    /** A property that holds memory (a backing field or a delegate), i.e. state (§6.6). */
    val hasField: Boolean = false,
    /** Annotated `@RuntimeLocal`: a split of this state between runtimes is accepted (§6.6). */
    val runtimeLocal: Boolean = false,
    /** A class declared as `object` (its properties are module state, §6.6). */
    val isObject: Boolean = false,
)

/** A single callable parameter as seen by the release-time signature classifier. */
@Serializable
data class RdmaParameterSignature(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false,
    /** Source text of the default expression (used to fill a masked parameter in a polyfill). */
    val defaultValue: String? = null,
)

/**
 * The lean, committed form of a callable parameter written to `rdma_signatures.json`.
 *
 * Only `(name, type, hasDefault)` is persisted: it is exactly what the release-time
 * [RdmaSignatureClassifier] needs to reject incompatible signature changes, and it keeps the
 * committed baseline free of the raw default-expression source text. The `defaultValue` text is
 * transient — it lives only in the in-memory analysis (`kernel_manifest_<platform>.json`) where the polyfill
 * materializer reads it to re-fill a masked parameter.
 */
@Serializable
data class RdmaSignatureEntry(
    val name: String,
    val type: String,
    val hasDefault: Boolean = false,
)

/** A directed "uses" edge: `from` references `to`. Both are collapsed polyfill-node fqns. */
@Serializable
data class RdmaUse(
    val from: String,
    val to: String,
)

/**
 * A manual polyfill (§6.3): a `@Polyfill(for = [target])` function that reimplements a kernel
 * symbol the auto-materializer cannot compile to JS. Not part of the public `@RDMA` surface.
 */
@Serializable
data class RdmaPolyfill(
    val target: String,
    val fqn: String,
    val sourceRange: RdmaSourceRange? = null,
)

/**
 * The analysis artifact emitted by the kernel compiler plugin on every compile: a full index
 * of the module's declarations (hashes + source ranges) plus the usage graph. Consumed by
 * `dynamic-release` to diff against the committed baseline and compute the dirty subgraph.
 */
@Serializable
data class RdmaAnalysis(
    val moduleId: String,
    val declarations: List<RdmaDeclaration> = emptyList(),
    val uses: List<RdmaUse> = emptyList(),
    val polyfills: List<RdmaPolyfill> = emptyList(),
)

/** The committed baseline in `versions/<moduleId>/rdma_hashes.json`. */
@Serializable
data class RdmaHashesFile(
    val moduleId: String,
    val hashes: Map<String, String> = emptyMap(),
)

/** The committed signature baseline in `versions/<moduleId>/rdma_signatures.json`. */
@Serializable
data class RdmaSignaturesFile(
    val moduleId: String,
    val signatures: Map<String, List<RdmaSignatureEntry>> = emptyMap(),
)
