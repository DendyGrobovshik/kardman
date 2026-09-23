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
package io.github.dendygrobovshik.kardman.kernel

import io.github.dendygrobovshik.kardman.types.RdmaAnalysis
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
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import java.io.File

object RdmaKernelKeys {
    val CPP_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("cppOutputDir")
    val JSON_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("jsonOutputDir")
    val KOTLIN_OUTPUT_DIR: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("kotlinOutputDir")
    val KERNEL_PACKAGE: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("kernelPackage")
    val MODULE_ID: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("moduleId")
}

private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

class RdmaKernelCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-kernel-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("cppOutputDir", "<dir>", "Output directory for generated C++ glue", required = false),
        CliOption("jsonOutputDir", "<dir>", "Output directory for rdma_manifest.json", required = false),
        CliOption("kotlinOutputDir", "<dir>", "Output directory for generated Kotlin widget entries", required = false),
        CliOption("kernelPackage", "<package>", "Package name of the user's kernel module", required = false),
        CliOption("moduleId", "<id>", "Sanitized identifier used to namespace this kernel module's generated C++", required = false),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "cppOutputDir" -> configuration.put(RdmaKernelKeys.CPP_OUTPUT_DIR, value)
            "jsonOutputDir" -> configuration.put(RdmaKernelKeys.JSON_OUTPUT_DIR, value)
            "kotlinOutputDir" -> configuration.put(RdmaKernelKeys.KOTLIN_OUTPUT_DIR, value)
            "kernelPackage" -> configuration.put(RdmaKernelKeys.KERNEL_PACKAGE, value)
            "moduleId" -> configuration.put(RdmaKernelKeys.MODULE_ID, value)
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
        val kernelPackage = configuration.get(RdmaKernelKeys.KERNEL_PACKAGE)
        val moduleId = configuration.get(RdmaKernelKeys.MODULE_ID)
        IrGenerationExtension.registerExtension(RdmaKernelGenerationExtension(cppDir, jsonDir, kotlinDir, kernelPackage, moduleId))
    }
}

class RdmaKernelGenerationExtension(
    private val cppOutputDir: String?,
    private val jsonOutputDir: String?,
    private val kotlinOutputDir: String?,
    private val kernelPackage: String?,
    private val moduleId: String?,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val publicApiErrors = RdmaPublicApiChecker.check(moduleFragment)
        if (publicApiErrors.isNotEmpty()) {
            error("Non-@RDMA public declarations in kernel module:\n" + publicApiErrors.joinToString("\n"))
        }

        val classes = RdmaClassExtractor.extractWithClasses(moduleFragment)
        val classInfos = classes.map { it.info }
        val rdmaClassFqns = classInfos.map { it.qualifiedName }.toSet()

        val functions = RdmaFunctionExtractor.extract(moduleFragment)
        // A type is bridgeable if it is an @RDMA class declared in this module, or an
        // @RDMA class resolved from a dependency (another kernel module) via the plugin
        // context. This is what makes cross-module @RDMA references legal.
        val isRdmaClass: (String) -> Boolean = { fqn ->
            fqn in rdmaClassFqns ||
                pluginContext.referenceClass(ClassId.topLevel(FqName(fqn)))?.owner?.hasAnnotation(RDMA_ANNOTATION) == true
        }
        val errors = functions.flatMap { RdmaTypeValidator.validateFunction(it, isRdmaClass) }
        if (errors.isNotEmpty()) {
            error("Invalid @RDMA types crossing the runtime boundary:\n" + errors.joinToString("\n"))
        }

        // Clean stale generated C++ files so removing an @RDMA class/function doesn't leave
        // dangling proxy sources referencing a no-longer-existing JNI cache entry.
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
        val pkg = kernelPackage ?: "com.example.kernel"
        val modId = moduleId ?: ""
        cppOutputDir?.let { cppDir ->
            kotlinOutputDir?.let { kotlinDir ->
                RdmaWidgetGenerator(
                    { fileName, _ -> File(cppDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                    { fileName, _ -> File(kotlinDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                    pkg,
                    modId,
                ).generate(widgets)
            }
        }

        if (classes.isEmpty() && functions.isEmpty()) return

        cppOutputDir?.let { dir ->
            CppGenerator(modId) { fileName, _ ->
                File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
            }.generate(classInfos, functions)
        }

        jsonOutputDir?.let { dir ->
            val json = Json.encodeToString(RdmaManifest.serializer(), RdmaManifest(classInfos, functions))
            File(dir, "rdma_manifest.json").also { it.parentFile.mkdirs() }.writeText(json)

            val index = RdmaSymbolIndexer.index(moduleFragment)
            val analysis = RdmaAnalysis(moduleId ?: "", index.declarations, index.uses)
            val analysisJson = Json.encodeToString(RdmaAnalysis.serializer(), analysis)
            File(dir, "rdma_analysis.json").also { it.parentFile.mkdirs() }.writeText(analysisJson)
        }

        val transformer = RdmaVtableTransformer(pluginContext, pkg)
        for (entry in classes) {
            transformer.transform(entry.cls, entry.info)
        }
    }
}
