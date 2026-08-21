package io.github.dendygrobovshik.kardman.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

object RdmaIrKeys {
    val CLASSES_JSON: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaClassesJson")
    val OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("rdmaOutputDir")
}

class RdmaIrCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("rdmaClassesJson", "<path>", "Path to rdma_classes.json manifest", required = false),
        CliOption("rdmaOutputDir", "<dir>", "Output directory for generated plugin sources", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "rdmaClassesJson" -> configuration.put(RdmaIrKeys.CLASSES_JSON, value)
            "rdmaOutputDir" -> configuration.put(RdmaIrKeys.OUTPUT_DIR, value)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class RdmaIrPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "rdma-compiler-plugin"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        RdmaTransformState.configure(
            jsonPath = configuration.get(RdmaIrKeys.CLASSES_JSON),
            outputDir = configuration.get(RdmaIrKeys.OUTPUT_DIR),
        )
        FirExtensionRegistrarAdapter.registerExtension(RdmaFirExtensionRegistrar())
        IrGenerationExtension.registerExtension(RdmaIrGenerationExtension())
    }
}
