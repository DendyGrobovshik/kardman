package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaManifest
import kotlinx.serialization.json.Json
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
    val KOTLIN_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("kotlinOutputDir")
}

class RdmaKernelCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-kernel-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("cppOutputDir", "<dir>", "Output directory for generated C++ glue", required = false),
        CliOption("jsonOutputDir", "<dir>", "Output directory for rdma_manifest.json", required = false),
        CliOption("kotlinOutputDir", "<dir>", "Output directory for generated Kotlin widget entries", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "cppOutputDir" -> configuration.put(RdmaKernelKeys.CPP_OUTPUT_DIR, value)
            "jsonOutputDir" -> configuration.put(RdmaKernelKeys.JSON_OUTPUT_DIR, value)
            "kotlinOutputDir" -> configuration.put(RdmaKernelKeys.KOTLIN_OUTPUT_DIR, value)
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
        val kotlinDir = configuration.get(RdmaKernelKeys.KOTLIN_OUTPUT_DIR)
        IrGenerationExtension.registerExtension(RdmaKernelGenerationExtension(cppDir, jsonDir, kotlinDir))
    }
}

class RdmaKernelGenerationExtension(
    private val cppOutputDir: String?,
    private val jsonOutputDir: String?,
    private val kotlinOutputDir: String?,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val classes = RdmaClassExtractor.extractWithClasses(moduleFragment)
        val classInfos = classes.map { it.info }
        val rdmaClassFqns = classInfos.map { it.qualifiedName }.toSet()

        val functions = RdmaFunctionExtractor.extract(moduleFragment)
        val errors = functions.flatMap { RdmaTypeValidator.validateFunction(it, rdmaClassFqns) }
        if (errors.isNotEmpty()) {
            error("Invalid @RDMA types crossing the runtime boundary:\n" + errors.joinToString("\n"))
        }

        // Clean stale generated C++ files so removing an @RDMA class/function doesn't leave
        // dangling HostObject sources referencing a no-longer-existing JNI cache entry.
        cppOutputDir?.let { dir ->
            File(dir).listFiles { f ->
                f.isFile && (f.extension == "h" || f.extension == "cpp")
            }?.forEach { it.delete() }
        }
        kotlinOutputDir?.let { dir ->
            File(dir).listFiles { f -> f.isFile && f.extension == "kt" }?.forEach { it.delete() }
        }

        // The Composer proxy is part of the base protocol and does not depend on any
        // @RDMA class/function, so it is always regenerated (and version-checked against
        // the resolved `androidx.compose.runtime.Composer` IR).
        cppOutputDir?.let { dir ->
            val protocolErrors = RdmaComposerProtocol.validateAgainst(pluginContext)
            if (protocolErrors.isNotEmpty()) {
                error("Compose base protocol mismatch:\n" + protocolErrors.joinToString("\n"))
            }
            RdmaComposerProxyGenerator { fileName, _ ->
                File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
            }.generate(RdmaComposerProtocol.baseProtocol)
        }

        // Typed per-widget bridge (Variant A): generated Kotlin entries + C++ HostFunctions.
        val widgets = functions.filter { it.composable }
        cppOutputDir?.let { cppDir ->
            kotlinOutputDir?.let { kotlinDir ->
                RdmaWidgetGenerator(
                    { fileName, _ -> File(cppDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                    { fileName, _ -> File(kotlinDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                ).generate(widgets)
            }
        }

        if (classes.isEmpty() && functions.isEmpty()) return

        cppOutputDir?.let { dir ->
            CppGenerator { fileName, _ ->
                File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
            }.generate(classInfos, functions)
        }

        jsonOutputDir?.let { dir ->
            val json = Json.encodeToString(RdmaManifest.serializer(), RdmaManifest(classInfos, functions))
            File(dir, "rdma_manifest.json").also { it.parentFile.mkdirs() }.writeText(json)
        }

        val transformer = RdmaVtableTransformer(pluginContext)
        for (entry in classes) {
            transformer.transform(entry.cls, entry.info)
        }
    }
}
