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

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

object RdmaPluginKeys {
    val MANIFEST: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaManifest")
    val PLUGIN_PACKAGE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("pluginPackage")
    val RUN_RDMA_APP_FQN: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("runRdmaAppFqn")
    val KERNEL_ROOT_PACKAGE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("kernelRootPackage")
    val OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaOutputDir")
    val PLUGIN_ID: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("pluginId")
    val PLUGIN_VERSION: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("pluginVersion")
    val BUILT_AGAINST: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("builtAgainst")
    val MODULE_DEPS: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaModuleDeps")
    val FLOOR_MAP: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaFloorMap")
}

class RdmaPluginCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-plugin-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("rdmaManifest", "<path>", "Path(s) to rdma_manifest.json, joined by the platform path separator", required = false),
        CliOption("pluginPackage", "<package>", "Package of the plugin module", required = false),
        CliOption("runRdmaAppFqn", "<fqn>", "Fully-qualified name of the kernel's runRdmaApp entry point", required = false),
        CliOption("kernelRootPackage", "<package>", "Root package of the kernel modules (for visibility checks)", required = false),
        CliOption("rdmaOutputDir", "<dir>", "Output directory for generated plugin sources", required = false),
        CliOption("pluginId", "<id>", "Stable plugin identifier (username:name)", required = false),
        CliOption("pluginVersion", "<int>", "Monotonic plugin version", required = false),
        CliOption("builtAgainst", "<int>", "Kernel version the plugin is compiled against", required = false),
        CliOption("rdmaModuleDeps", "<ids>", "Kernel module ids visible to the plugin, joined by the platform path separator", required = false),
        CliOption("rdmaFloorMap", "<json>", "Merged kernel floor map (node FQN -> floor) as a JSON object", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "rdmaManifest" -> configuration.put(RdmaPluginKeys.MANIFEST, value)
            "pluginPackage" -> configuration.put(RdmaPluginKeys.PLUGIN_PACKAGE, value)
            "runRdmaAppFqn" -> configuration.put(RdmaPluginKeys.RUN_RDMA_APP_FQN, value)
            "kernelRootPackage" -> configuration.put(RdmaPluginKeys.KERNEL_ROOT_PACKAGE, value)
            "rdmaOutputDir" -> configuration.put(RdmaPluginKeys.OUTPUT_DIR, value)
            "pluginId" -> configuration.put(RdmaPluginKeys.PLUGIN_ID, value)
            "pluginVersion" -> configuration.put(RdmaPluginKeys.PLUGIN_VERSION, value)
            "builtAgainst" -> configuration.put(RdmaPluginKeys.BUILT_AGAINST, value)
            "rdmaModuleDeps" -> configuration.put(RdmaPluginKeys.MODULE_DEPS, value)
            "rdmaFloorMap" -> configuration.put(RdmaPluginKeys.FLOOR_MAP, value)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class RdmaPluginCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "rdma-plugin-compiler-plugin"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val manifest = configuration.get(RdmaPluginKeys.MANIFEST)
        val manifestPaths = manifest
            ?.split(java.io.File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val moduleDeps = configuration.get(RdmaPluginKeys.MODULE_DEPS)
            ?.split(java.io.File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val floorMap = configuration.get(RdmaPluginKeys.FLOOR_MAP)
            ?.let { parseFloorMap(it) }
            ?: emptyMap()
        RdmaPluginTransformState.configure(
            manifestPaths = manifestPaths,
            pluginPackage = configuration.get(RdmaPluginKeys.PLUGIN_PACKAGE),
            runRdmaAppFqn = configuration.get(RdmaPluginKeys.RUN_RDMA_APP_FQN),
            kernelRootPackage = configuration.get(RdmaPluginKeys.KERNEL_ROOT_PACKAGE),
            outputDir = configuration.get(RdmaPluginKeys.OUTPUT_DIR),
            pluginId = configuration.get(RdmaPluginKeys.PLUGIN_ID),
            pluginVersion = configuration.get(RdmaPluginKeys.PLUGIN_VERSION)?.toIntOrNull(),
            builtAgainst = configuration.get(RdmaPluginKeys.BUILT_AGAINST)?.toIntOrNull(),
            moduleDeps = moduleDeps,
            floorMap = floorMap,
        )
        FirExtensionRegistrarAdapter.registerExtension(RdmaPluginFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(RdmaPluginGenerationExtension())
    }

    private fun parseFloorMap(json: String): Map<String, Int> =
        try {
            kotlinx.serialization.json.Json.decodeFromString(
                MapSerializer(String.serializer(), Int.serializer()),
                json,
            )
        } catch (e: Exception) {
            emptyMap()
        }
}
