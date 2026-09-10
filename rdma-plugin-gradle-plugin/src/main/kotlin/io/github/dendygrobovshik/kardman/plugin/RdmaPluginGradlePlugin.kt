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

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

private const val RDMA_RUNTIME_BRIDGE_SOURCE = """package com.example.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import kotlin.js.unsafeCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

external object RDMA {
    fun registerContent(content: dynamic)
    fun setComposerEmpty(empty: Any)
    fun mutableStateOf(value: dynamic): dynamic
    fun registerBlock(block: dynamic): dynamic
    fun sideEffect(blockId: dynamic)
    fun disposableEffect(keys: dynamic, effectFn: dynamic)
}

fun rdmaMutableStateOf(value: Int): MutableState<Int> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<Int>>()

fun rdmaMutableStateOf(value: Boolean): MutableState<Boolean> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<Boolean>>()

fun rdmaMutableStateOf(value: String): MutableState<String> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<String>>()

fun rdmaMutableIntStateOf(value: Int): MutableState<Int> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<Int>>()

fun rdmaSideEffect(block: () -> Unit) {
    RDMA.sideEffect(RDMA.registerBlock(block))
}

// Guest-side stub for the `DisposableEffect` DSL. The effect body runs entirely
// in the plugin (JS); only the wrapped result object crosses back to the kernel,
// which calls its `dispose` property on `onForgotten`/`onAbandoned`. The result is
// a plain JS object literal (`{ dispose: fn }`) rather than a Kotlin class so the
// `dispose` name cannot be dead-code-eliminated or mangled: it is only ever called
// from C++, never from plugin Kotlin.
class RdmaDisposableEffectScope internal constructor() {
    fun onDispose(onDisposeEffect: () -> Unit): dynamic {
        val result = js("({})")
        result.dispose = onDisposeEffect
        return result
    }
}

private val RDMA_UNIT_KEY = "@rdma:unit"

@Composable
fun rdmaDisposableEffect(vararg keys: Any?, effect: RdmaDisposableEffectScope.() -> dynamic) {
    // Normalize `Unit` to a stable sentinel so `DisposableEffect(Unit)` is not
    // restarted on every recomposition (the kernel cannot marshal `Unit` itself).
    val marshalled = Array<Any?>(keys.size) { i -> if (keys[i] === Unit) RDMA_UNIT_KEY else keys[i] }
    RDMA.disposableEffect(marshalled, { effect(RdmaDisposableEffectScope()) })
}

// LaunchedEffect = DisposableEffect with a `suspend` body. Reuses the exact same
// DisposableEffect bridge: the effect body launches the block on the Hermes-thread
// `Dispatchers.Main` (deferred to the low-priority queue, so it runs after the
// current composition) and returns `{ dispose: { job.cancel() } }` so the kernel
// cancels it on key change / leaving composition.
@Composable
fun rdmaLaunchedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val marshalled = Array<Any?>(keys.size) { i -> if (keys[i] === Unit) RDMA_UNIT_KEY else keys[i] }
    RDMA.disposableEffect(marshalled, {
        val job = CoroutineScope(Dispatchers.Main).launch { block() }
        val result = js("({})")
        result.dispose = { job.cancel() }
        result
    })
}

// Plugin-local coroutine scope tied to composition lifetime. The scope is
// remembered (stable across recompositions) and cancelled when this call site
// leaves the composition. Uses `Dispatchers.Main.immediate` so `launch { }` runs
// inline on the (already Hermes) thread, matching `rememberCoroutineScope`'s
// current-thread semantics.
@Composable
fun rdmaRememberCoroutineScope(): CoroutineScope {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    rdmaDisposableEffect(RDMA_UNIT_KEY) { onDispose { scope.cancel() } }
    return scope
}

fun rdmaRunApp(content: @Composable () -> Unit) {
    RDMA.setComposerEmpty(Composer.Companion.Empty)
    RDMA.registerContent(content)
}
"""

class RdmaPluginGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // Static guest-side bridge (external RDMA object + generic protocol helpers).
        // Per-widget `rdmaXxx` proxies are generated by the compiler plugin into
        // `RdmaWidgetBridge.kt`.
        val dir = File(target.buildDir, "generated/rdma")
        dir.mkdirs()
        File(dir, "RdmaRuntimeBridge.kt").writeText(RDMA_RUNTIME_BRIDGE_SOURCE)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm

    override fun getCompilerPluginId(): String = "rdma-plugin-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-plugin-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            val manifestPath = (project.findProperty("rdmaManifest") as? String)
                ?: "${project.rootProject.projectDir}/kernel/build/generated/rdma/rdma_manifest.json"
            val outputDir = "${project.buildDir}/generated/rdma"
            listOf(
                SubpluginOption("rdmaManifest", manifestPath),
                SubpluginOption("rdmaOutputDir", outputDir),
            )
        }
    }
}
