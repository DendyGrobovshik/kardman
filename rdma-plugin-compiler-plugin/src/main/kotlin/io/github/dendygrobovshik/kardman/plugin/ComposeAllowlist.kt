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
package io.github.dendygrobovshik.kardman.plugin

/**
 * Whitelist of Compose symbols the plugin is allowed to use.
 *
 * The host kernel exposes only the base `Composer` protocol (see
 * `RdmaComposerProtocol` in `rdma-kernel-compiler-plugin`). Everything else from
 * `androidx.compose.*` must be rejected at compile time with a clear error, because
 * the kernel cannot faithfully execute it (effects, derived state, movable content,
 * snapshot flows, animations, coroutine scopes, ...).
 */
object ComposeAllowlist {

    private val allowedRuntime = setOf(
        "androidx.compose.runtime.Composable",
        "androidx.compose.runtime.remember",
        "androidx.compose.runtime.mutableStateOf",
        "androidx.compose.runtime.mutableIntStateOf",
        "androidx.compose.runtime.getValue",
        "androidx.compose.runtime.setValue",
        "androidx.compose.runtime.Composer",
        "androidx.compose.runtime.State",
        "androidx.compose.runtime.MutableState",
        "androidx.compose.runtime.MutableIntState",
        // Structural helpers that lower to the base Composer protocol (groups,
        // remember/changed) and therefore work through the proxy unchanged.
        "androidx.compose.runtime.key",
        "androidx.compose.runtime.rememberUpdatedState",
        // Pure annotations (erased at runtime).
        "androidx.compose.runtime.Stable",
        "androidx.compose.runtime.Immutable",
        // Rewritten to the kernel-backed `rdmaSideEffect` bridge.
        "androidx.compose.runtime.SideEffect",
        // Rewritten to the kernel-backed `rdmaDisposableEffect` bridge. The
        // scope/result types and `onDispose` stay in the plugin (pure JS) and are
        // re-targeted to guest-side stubs by the rewrite; they never cross the
        // boundary themselves.
        "androidx.compose.runtime.DisposableEffect",
        "androidx.compose.runtime.DisposableEffectScope",
        "androidx.compose.runtime.DisposableEffectResult",
        "androidx.compose.runtime.DisposableEffectScope.onDispose",
    )

    fun isAllowed(fqn: String): Boolean = when {
        fqn in allowedRuntime -> true
        !fqn.startsWith("androidx.compose") -> true
        else -> false
    }

    fun reason(fqn: String): String {
        val short = fqn.substringAfterLast('.')
        return "kernel doesn't support '$short' — the plugin is limited to the base Compose protocol " +
            "(remember/mutableStateOf/widgets)"
    }
}
