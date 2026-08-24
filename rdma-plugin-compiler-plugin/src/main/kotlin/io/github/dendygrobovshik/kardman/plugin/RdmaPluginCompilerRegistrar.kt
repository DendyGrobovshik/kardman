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
    val CLASSES_JSON: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaClassesJson")
    val WIDGETS_JSON: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaWidgetsJson")
    val OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaOutputDir")
}

class RdmaPluginCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-plugin-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("rdmaClassesJson", "<path>", "Path to rdma_classes.json manifest", required = false),
        CliOption("rdmaWidgetsJson", "<path>", "Path to rdma_widgets.json manifest", required = false),
        CliOption("rdmaOutputDir", "<dir>", "Output directory for generated plugin sources", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "rdmaClassesJson" -> configuration.put(RdmaPluginKeys.CLASSES_JSON, value)
            "rdmaWidgetsJson" -> configuration.put(RdmaPluginKeys.WIDGETS_JSON, value)
            "rdmaOutputDir" -> configuration.put(RdmaPluginKeys.OUTPUT_DIR, value)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class RdmaPluginCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "rdma-plugin-compiler-plugin"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        RdmaPluginTransformState.configure(
            jsonPath = configuration.get(RdmaPluginKeys.CLASSES_JSON),
            widgetsJsonPath = configuration.get(RdmaPluginKeys.WIDGETS_JSON),
            outputDir = configuration.get(RdmaPluginKeys.OUTPUT_DIR),
        )
        FirExtensionRegistrarAdapter.registerExtension(RdmaPluginFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(RdmaPluginGenerationExtension())
    }
}
