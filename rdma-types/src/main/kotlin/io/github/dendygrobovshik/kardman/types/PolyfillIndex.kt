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

/** A published polyfill bundle: its content hash and the kernel version it was built at. */
@Serializable
data class PolyfillRef(
    val hash: String,
    val version: Int,
)

/**
 * `versions/polyfills.json` (compatibility.md §8.1): which bundle serves which host. Bundles are
 * content-addressed, so hosts whose native state of a module is the same share one bundle.
 *
 *  - [f]: module → host version `H` → toolchain generation `G` → the current `F` polyfill;
 *  - [r]: module → `G` → the `R` polyfills, one per release that removed symbols (each holds
 *    only that release's removals, so all of them are served).
 *
 * `G` is `""` when the project has no toolchain generations. Written only at a release's
 * commit point, after every bundle it names is in the store.
 */
@Serializable
data class PolyfillIndex(
    val f: Map<String, Map<String, Map<String, PolyfillRef>>> = emptyMap(),
    val r: Map<String, Map<String, List<PolyfillRef>>> = emptyMap(),
)

/** One native state of a module to build an `F` polyfill against, and the hosts that have it. */
@Serializable
data class ReleaseFPlan(
    val state: String,
    val hosts: List<Int>,
    val dir: String,
)

/**
 * A prepared release (`<workDir>/plan.json`): everything the commit needs, computed and checked
 * before any bundle is built. Nothing in `versions/` changes until `commit-release`.
 */
@Serializable
data class ReleasePlan(
    val module: String,
    val command: String,
    val analysis: String,
    val changelog: Changelog,
    val version: Int,
    val generations: List<String>,
    val f: List<ReleaseFPlan> = emptyList(),
    val rDir: String? = null,
)
