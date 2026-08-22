package io.github.dendygrobovshik.kardman.kernel

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import java.io.File

object RdmaKernelKeys {
    val CPP_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("cppOutputDir")
    val JSON_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("jsonOutputDir")
}

class RdmaKernelCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-kernel-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("cppOutputDir", "<dir>", "Output directory for generated C++ glue", required = false),
        CliOption("jsonOutputDir", "<dir>", "Output directory for rdma_classes.json", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "cppOutputDir" -> configuration.put(RdmaKernelKeys.CPP_OUTPUT_DIR, value)
            "jsonOutputDir" -> configuration.put(RdmaKernelKeys.JSON_OUTPUT_DIR, value)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class RdmaKernelCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "rdma-kernel-compiler-plugin"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val cppDir = configuration.get(RdmaKernelKeys.CPP_OUTPUT_DIR)
        val jsonDir = configuration.get(RdmaKernelKeys.JSON_OUTPUT_DIR)
        IrGenerationExtension.registerExtension(RdmaKernelGenerationExtension(cppDir, jsonDir))
    }
}

class RdmaKernelGenerationExtension(
    private val cppOutputDir: String?,
    private val jsonOutputDir: String?,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val classes = RdmaClassExtractor.extractWithClasses(moduleFragment)
        if (classes.isEmpty()) return
        val classInfos = classes.map { it.info }

        cppOutputDir?.let { dir ->
            CppGenerator { fileName, _ ->
                File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
            }.generate(classInfos)
        }

        jsonOutputDir?.let { dir ->
            val json = RdmaJsonWriter.write(classInfos)
            val out = File(dir, "rdma_classes.json").also { it.parentFile.mkdirs() }
            out.writeText(json)
        }

        val transformer = RdmaVtableTransformer(pluginContext)
        for (entry in classes) {
            transformer.transform(entry.cls, entry.info)
        }
    }
}
