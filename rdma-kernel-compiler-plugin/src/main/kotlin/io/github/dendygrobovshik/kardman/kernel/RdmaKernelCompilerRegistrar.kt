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
    val BACKEND: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("backend")
    val GLUE_MODULES: CompilerConfigurationKey<String> = CompilerConfigurationKey.create("glueModules")
}

private val RDMA_ANNOTATION = FqName("io.github.dendygrobovshik.kardman.RDMA")

/** File name of a kernel module's API description (read by the plugin compiler). */
const val API_FILE = "rdma_api.json"

class RdmaKernelCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = "rdma-kernel-compiler-plugin"

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption("backend", "<jni|capi>", "Native bridge backend: JNI (jvm/android) or C ABI (native)", required = false),
        CliOption("cppOutputDir", "<dir>", "Output directory for generated C++ glue", required = false),
        CliOption("jsonOutputDir", "<dir>", "Output directory for rdma_api.json and kernel_manifest_<platform>.json", required = false),
        CliOption("kotlinOutputDir", "<dir>", "Output directory for generated Kotlin widget entries", required = false),
        CliOption("kernelPackage", "<package>", "Package name of the user's kernel module", required = false),
        CliOption("moduleId", "<id>", "Sanitized identifier used to namespace this kernel module's generated C++", required = false),
        CliOption(
            "glueModules", "<moduleId=package=apiJson;…>",
            "backend=capi-glue: the kernel modules whose generated C ABI glue this (app) compilation compiles",
            required = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "backend" -> configuration.put(RdmaKernelKeys.BACKEND, value)
            "cppOutputDir" -> configuration.put(RdmaKernelKeys.CPP_OUTPUT_DIR, value)
            "jsonOutputDir" -> configuration.put(RdmaKernelKeys.JSON_OUTPUT_DIR, value)
            "kotlinOutputDir" -> configuration.put(RdmaKernelKeys.KOTLIN_OUTPUT_DIR, value)
            "kernelPackage" -> configuration.put(RdmaKernelKeys.KERNEL_PACKAGE, value)
            "moduleId" -> configuration.put(RdmaKernelKeys.MODULE_ID, value)
            "glueModules" -> configuration.put(RdmaKernelKeys.GLUE_MODULES, value)
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
        val backend = configuration.get(RdmaKernelKeys.BACKEND) ?: "jni"
        if (backend == "capi-glue") {
            val modules = configuration.get(RdmaKernelKeys.GLUE_MODULES).orEmpty()
                .split(';').filter { it.isNotBlank() }
                .map { it.split('=', limit = 3).let { (id, pkg, api) -> CAbiGlueModule(id, pkg, api) } }
            IrGenerationExtension.registerExtension(CAbiGlueGenerationExtension(modules))
            return
        }
        IrGenerationExtension.registerExtension(RdmaKernelGenerationExtension(cppDir, jsonDir, kotlinDir, kernelPackage, moduleId, backend))
    }
}

/** A kernel module whose generated C ABI glue the app's iOS compilation compiles. */
data class CAbiGlueModule(val moduleId: String, val kernelPackage: String, val apiJson: String)

/**
 * The app's iOS compilation compiles the C ABI glue every kernel module generated during
 * its own compilation (`capi/kotlin`), in the same build — like Android's `widget-kotlin`
 * (docs/design/runtime_rework.md §11, iOS two-pass generation). Here the widget entry
 * stubs of that glue get their bodies: a direct call of the lowered widget from the
 * kernel klib.
 */
class CAbiGlueGenerationExtension(private val modules: List<CAbiGlueModule>) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        for (m in modules) {
            val file = File(m.apiJson)
            if (!file.exists()) continue
            val api = Json { ignoreUnknownKeys = true }.decodeFromString(RdmaManifest.serializer(), file.readText())
            val widgets = api.functions.filter { it.composable }
            CAbiWidgetIrGenerator(pluginContext, moduleFragment, m.moduleId, m.kernelPackage).generate(widgets)
        }
    }
}

class RdmaKernelGenerationExtension(
    private val cppOutputDir: String?,
    private val jsonOutputDir: String?,
    private val kotlinOutputDir: String?,
    private val kernelPackage: String?,
    private val moduleId: String?,
    private val backend: String,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val publicApiErrors = RdmaPublicApiChecker.check(moduleFragment)
        if (publicApiErrors.isNotEmpty()) {
            error("Non-@RDMA public declarations in kernel module:\n" + publicApiErrors.joinToString("\n"))
        }

        val classes = RdmaClassExtractor.extractWithClasses(moduleFragment)
        val classInfos = classes.map { it.info }
        val rdmaClassFqns = classInfos.map { it.qualifiedName }.toSet()

        val enums = RdmaClassExtractor.extractEnums(moduleFragment)
        val rdmaEnumFqns = enums.map { it.qualifiedName }.toSet()

        val functions = RdmaFunctionExtractor.extract(moduleFragment)
        // A type is bridgeable if it is an @RDMA class declared in this module, an
        // @RDMA enum, or an @RDMA class resolved from a dependency (another kernel
        // module) via the plugin context. This is what makes cross-module @RDMA
        // references legal.
        val isRdmaClass: (String) -> Boolean = { fqn ->
            fqn in rdmaClassFqns ||
                fqn in rdmaEnumFqns ||
                pluginContext.referenceClass(ClassId.topLevel(FqName(fqn)))?.owner?.hasAnnotation(RDMA_ANNOTATION) == true
        }
        RdmaCallbackDescs.isEnum = { fqn ->
            fqn in rdmaEnumFqns ||
                pluginContext.referenceClass(ClassId.topLevel(FqName(fqn)))?.owner?.let {
                    it.kind == org.jetbrains.kotlin.descriptors.ClassKind.ENUM_CLASS && it.hasAnnotation(RDMA_ANNOTATION)
                } == true
        }
        val errors = functions.flatMap { RdmaTypeValidator.validateFunction(it, isRdmaClass) } +
            classInfos.flatMap { RdmaTypeValidator.validateClass(it, isRdmaClass) } +
            RdmaDefaultGate.check(moduleFragment)
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

        val isCapi = backend == "capi"

        val widgets = functions.filter { it.composable }
        val pkg = kernelPackage ?: "com.example.kernel"
        val modId = moduleId ?: ""

        // The Composer proxy is part of the base protocol and does not depend on any
        // @RDMA class/function, so it is always regenerated (and version-checked against
        // the resolved `androidx.compose.runtime.Composer` IR). The backend picks the
        // concrete generator (JNI vs C ABI).
        cppOutputDir?.let { dir ->
            val protocolErrors = RdmaComposerProtocol.validateAgainst(pluginContext)
            if (protocolErrors.isNotEmpty()) {
                error("Compose base protocol mismatch:\n" + protocolErrors.joinToString("\n"))
            }
            if (isCapi) {
                CAbiComposerProxyGenerator { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(RdmaComposerProtocol.baseProtocol)
            } else {
                RdmaComposerProxyGenerator { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(RdmaComposerProtocol.baseProtocol)
            }
        }

        // Typed per-widget bridge (Variant A): generated Kotlin entries + C++ HostFunctions.
        if (isCapi) {
            cppOutputDir?.let { cppDir ->
                kotlinOutputDir?.let { kotlinDir ->
                    CAbiWidgetGenerator(
                        { fileName, _ -> File(cppDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                        { fileName, _ -> File(kotlinDir, fileName).also { it.parentFile.mkdirs() }.outputStream() },
                        pkg,
                        modId,
                    ).generate(widgets)
                }
            }
        } else {
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
        }

        // IR pass: fill in the C ABI widget entry stub bodies. Runs after the compose
        // compiler has lowered the @Composable widgets to (args..., $composer, $changed).
        if (isCapi) {
            CAbiWidgetIrGenerator(pluginContext, moduleFragment, modId, pkg).generate(widgets)
        }

        // A module with nothing `@RDMA` in this compilation (e.g. all of it in another platform's
        // source set) still gets its (empty) bridge and manifest: the app's aggregate bridge
        // includes `RdmaBridge_<module>.h` for every kernel module.

        if (isCapi) {
            kotlinOutputDir?.let { dir ->
                CAbiKotlinGenerator(modId, pkg) { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(classInfos, functions, enums)
            }
            cppOutputDir?.let { dir ->
                CAbiCppGenerator(modId) { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(classInfos, functions, enums)
            }
        } else {
            cppOutputDir?.let { dir ->
                CppGenerator(modId, pkg) { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(classInfos, functions, enums)
            }
            // JVM-side async bridge wrappers for @RDMA suspend functions/methods,
            // compiled into the app (the widget-kotlin dir is added as an app source dir).
            kotlinOutputDir?.let { dir ->
                RdmaSuspendBridgeGenerator(pkg) { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(classInfos, functions)
            }
            // JVM-side default-parameter wrappers, compiled into the app: the C++ JNI bridge
            // computes a bitmask of omitted trailing parameters and calls these wrappers, which
            // evaluate the defaults natively before delegating to the original declaration.
            kotlinOutputDir?.let { dir ->
                RdmaDefaultWrapperGenerator(pkg) { fileName, _ ->
                    File(dir, fileName).also { it.parentFile.mkdirs() }.outputStream()
                }.generate(classInfos, functions)
            }
        }

        jsonOutputDir?.let { dir ->
            // API description of the module's @RDMA surface: what plugins may call, with types
            // (the plugin compiler generates its kernel stubs from it). Platform-independent.
            val json = Json.encodeToString(RdmaManifest.serializer(), RdmaManifest(classInfos, functions, enums))
            File(dir, API_FILE).also { it.parentFile.mkdirs() }.writeText(json)

            // Kernel manifest of this platform's compilation: every declaration with its content
            // hash, signature and uses — the input of kernel releases. Per platform, because
            // `actual` implementations differ (and so do their hashes).
            val index = RdmaSymbolIndexer.index(moduleFragment)
            val analysis = RdmaAnalysis(moduleId ?: "", index.declarations, index.uses, index.polyfills)
            val analysisJson = Json.encodeToString(RdmaAnalysis.serializer(), analysis)
            val platform = if (isCapi) "ios" else "android"
            File(dir, "kernel_manifest_$platform.json").also { it.parentFile.mkdirs() }.writeText(analysisJson)
        }

        val transformer = RdmaVtableTransformer(pluginContext, pkg, isCapi)
        for (entry in classes) {
            transformer.transform(entry.cls, entry.info)
        }

        // Hotfix dispatch slots for top-level @RDMA functions (docs/design/runtime_rework.md §6).
        RdmaSlotTransformer(pluginContext, pkg).transform(moduleFragment, functions)
    }
}
