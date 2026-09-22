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

object RdmaPluginKeys {
    val MANIFEST: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaManifest")
    val PLUGIN_PACKAGE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("pluginPackage")
    val RUN_RDMA_APP_FQN: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("runRdmaAppFqn")
    val KERNEL_ROOT_PACKAGE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("kernelRootPackage")
    val OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaOutputDir")
}

class RdmaPluginCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-plugin-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("rdmaManifest", "<path>", "Path(s) to rdma_manifest.json, joined by the platform path separator", required = false),
        CliOption("pluginPackage", "<package>", "Package of the plugin module", required = false),
        CliOption("runRdmaAppFqn", "<fqn>", "Fully-qualified name of the kernel's runRdmaApp entry point", required = false),
        CliOption("kernelRootPackage", "<package>", "Root package of the kernel modules (for visibility checks)", required = false),
        CliOption("rdmaOutputDir", "<dir>", "Output directory for generated plugin sources", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "rdmaManifest" -> configuration.put(RdmaPluginKeys.MANIFEST, value)
            "pluginPackage" -> configuration.put(RdmaPluginKeys.PLUGIN_PACKAGE, value)
            "runRdmaAppFqn" -> configuration.put(RdmaPluginKeys.RUN_RDMA_APP_FQN, value)
            "kernelRootPackage" -> configuration.put(RdmaPluginKeys.KERNEL_ROOT_PACKAGE, value)
            "rdmaOutputDir" -> configuration.put(RdmaPluginKeys.OUTPUT_DIR, value)
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
        RdmaPluginTransformState.configure(
            manifestPaths = manifestPaths,
            pluginPackage = configuration.get(RdmaPluginKeys.PLUGIN_PACKAGE),
            runRdmaAppFqn = configuration.get(RdmaPluginKeys.RUN_RDMA_APP_FQN),
            kernelRootPackage = configuration.get(RdmaPluginKeys.KERNEL_ROOT_PACKAGE),
            outputDir = configuration.get(RdmaPluginKeys.OUTPUT_DIR),
        )
        FirExtensionRegistrarAdapter.registerExtension(RdmaPluginFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(RdmaPluginGenerationExtension())
    }
}
