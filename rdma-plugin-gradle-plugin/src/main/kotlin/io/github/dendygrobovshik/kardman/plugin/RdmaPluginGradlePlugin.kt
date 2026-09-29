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
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.io.File

private const val DEFAULT_PLUGIN_PROJECT = ":plugin"
private const val DEFAULT_PLUGIN_ROOT_PACKAGE = "com.example.plugin"
private const val DEFAULT_KERNEL_PROJECT = ":kernel"
private const val DEFAULT_KERNEL_ROOT_PACKAGE = "com.example.kernel"

/** The package of this plugin module, derived from its username path segment. */
private fun Project.pluginPackage(): String {
    val rootPackage = findProperty("rdmaPluginRootPackage")?.toString()
        ?: rootProject.findProperty("rdmaPluginRootPackage")?.toString()
        ?: DEFAULT_PLUGIN_ROOT_PACKAGE
    val username = pluginUsername()
    return if (username.isEmpty()) rootPackage else "$rootPackage.$username"
}

/** The username segment of this plugin module (first path segment after the plugin container). */
private fun Project.pluginUsername(): String {
    val container = findProperty("rdmaPluginProject")?.toString()
        ?: rootProject.findProperty("rdmaPluginProject")?.toString()
        ?: DEFAULT_PLUGIN_PROJECT
    return path.removePrefix(container).removePrefix(":").substringBefore(':')
}

/** Package of a kernel module identified by its project path. */
private fun kernelPackageOf(root: Project, kernelContainer: String, kernelPath: String, rootPackage: String): String {
    val suffix = kernelPath.removePrefix(kernelContainer).removePrefix(":").replace(':', '.')
    return if (suffix.isEmpty()) rootPackage else "$rootPackage.$suffix"
}

/** Sanitized module id of a kernel module path (mirrors `RdmaKernelGradlePlugin.kernelModuleId`). */
private fun kernelModuleIdOf(kernelContainer: String, kernelPath: String): String {
    val suffix = kernelPath.removePrefix(kernelContainer).removePrefix(":").replace(':', '_').replace('.', '_')
    return if (suffix.isEmpty()) "default" else suffix
}

/** Stable plugin id from the project path, e.g. `:plugin:alice:counter` → `alice:counter`. */
private fun Project.pluginId(): String {
    val container = findProperty("rdmaPluginProject")?.toString()
        ?: rootProject.findProperty("rdmaPluginProject")?.toString()
        ?: DEFAULT_PLUGIN_PROJECT
    val suffix = path.removePrefix(container).removePrefix(":")
    return suffix.replace(':', '.').replaceFirst('.', ':')
}

/** Latest kernel version from `versions/changelog.json` (the `builtAgainst` value). */
private fun Project.builtAgainstVersion(): Int {
    val changelog = File(rootProject.projectDir, "versions/changelog.json")
    if (!changelog.isFile) return 0
    return try {
        val parsed = JsonSlurper().parseText(changelog.readText()) as Map<*, *>
        val entries = parsed["entries"] as? List<*> ?: return 0
        entries.mapNotNull { (it as? Map<*, *>)?.get("version") as? Number }.maxOfOrNull { it.toInt() } ?: 0
    } catch (e: Exception) {
        0
    }
}

/** Merged floor map (node FQN → floor) over the visible kernel modules. */
private fun Project.floorMap(kernelContainer: String, visiblePaths: List<String>): Map<String, Int> {
    val merged = mutableMapOf<String, Int>()
    for (kernelPath in visiblePaths) {
        val moduleId = kernelModuleIdOf(kernelContainer, kernelPath)
        val file = File(rootProject.projectDir, "versions/$moduleId/rdma_floor.json")
        if (!file.isFile) continue
        try {
            val parsed = JsonSlurper().parseText(file.readText()) as Map<*, *>
            for ((k, v) in parsed) {
                val key = k as? String ?: continue
                val value = (v as? Number)?.toInt() ?: continue
                merged[key] = maxOf(merged[key] ?: 0, value)
            }
        } catch (e: Exception) {
            // ignore a malformed floor file — minHost simply falls back to 0
        }
    }
    return merged
}

private fun runtimeBridgeSource(pluginPackage: String) = """package $pluginPackage

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
        File(dir, "RdmaRuntimeBridge.kt").writeText(runtimeBridgeSource(target.pluginPackage()))
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm ||
            kotlinCompilation.platformType == KotlinPlatformType.androidJvm

    override fun getCompilerPluginId(): String = "rdma-plugin-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-plugin-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            val outputDir = "${project.buildDir}/generated/rdma"
            val pluginPackage = project.pluginPackage()

            val kernelContainer = project.findProperty("rdmaKernelProject")?.toString()
                ?: project.rootProject.findProperty("rdmaKernelProject")?.toString()
                ?: DEFAULT_KERNEL_PROJECT
            val kernelRootPackage = project.findProperty("rdmaKernelRootPackage")?.toString()
                ?: project.rootProject.findProperty("rdmaKernelRootPackage")?.toString()
                ?: DEFAULT_KERNEL_ROOT_PACKAGE

            // Internal kernel modules are always visible; the plugin additionally sees
            // its own user module `:kernel:user:<username>`.
            val internals = (project.findProperty("rdmaKernelInternals")?.toString()
                ?: project.rootProject.findProperty("rdmaKernelInternals")?.toString()
                ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val username = project.pluginUsername()
            val ownModule = if (username.isEmpty()) null else "$kernelContainer:user:$username"

            val visiblePaths = internals + listOfNotNull(ownModule)
            val manifestPaths = visiblePaths.mapNotNull { kernelPath ->
                project.rootProject.findProject(kernelPath)?.let { kp ->
                    File(kp.buildDir, "generated/rdma/rdma_manifest.json").absolutePath
                }
            }

            val runRdmaAppFqn = internals.firstOrNull()?.let { internalPath ->
                "${kernelPackageOf(project.rootProject, kernelContainer, internalPath, kernelRootPackage)}.runRdmaApp"
            }

            val moduleDeps = visiblePaths.map { kernelModuleIdOf(kernelContainer, it) }
            val pluginId = project.pluginId()
            val pluginVersion = (project.findProperty("rdmaPluginVersion")?.toString()
                ?: project.rootProject.findProperty("rdmaPluginVersion")?.toString()
                ?: "1").toIntOrNull() ?: 1
            val builtAgainst = project.builtAgainstVersion()
            val floorMapJson = JsonOutput.toJson(project.floorMap(kernelContainer, visiblePaths))

            listOf(
                SubpluginOption("rdmaManifest", manifestPaths.joinToString(File.pathSeparator)),
                SubpluginOption("pluginPackage", pluginPackage),
                SubpluginOption("runRdmaAppFqn", runRdmaAppFqn ?: ""),
                SubpluginOption("kernelRootPackage", kernelRootPackage),
                SubpluginOption("rdmaOutputDir", outputDir),
                SubpluginOption("pluginId", pluginId),
                SubpluginOption("pluginVersion", pluginVersion.toString()),
                SubpluginOption("builtAgainst", builtAgainst.toString()),
                SubpluginOption("rdmaModuleDeps", moduleDeps.joinToString(File.pathSeparator)),
                SubpluginOption("rdmaFloorMap", floorMapJson),
            )
        }
    }
}
