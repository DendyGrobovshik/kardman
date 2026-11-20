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
package io.github.dendygrobovshik.kardman.app

import com.android.build.api.dsl.ApplicationExtension
import io.github.dendygrobovshik.kardman.types.RdmaConfig
import io.github.dendygrobovshik.kardman.types.RdmaToml
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

private const val DEFAULT_KERNEL_PROJECT = ":kernel"
private const val DEFAULT_KERNEL_ROOT_PACKAGE = "com.example.kernel"
private const val DEFAULT_PLUGIN_PROJECT = ":plugin"
private const val RDMA_RUNTIME_VERSION = "1.0"
private const val HERMES_VERSION = "0.76.9"

/** Loads the project's `rdma.toml` (overridable via `-PrdmaConfigFile`). */
private fun Project.rdmaConfig(): RdmaConfig {
    val file = findProperty("rdmaConfigFile")?.toString()?.let {
        val f = File(it); if (f.isAbsolute) f else File(rootProject.projectDir, it)
    } ?: File(rootProject.projectDir, "rdma.toml")
    return RdmaToml.load(file)
}

/**
 * Applied to the app module. It discovers the kernel modules (subprojects of
 * `kernel.project`) and plugin modules (subprojects of `plugin.project`), then:
 *   - adds every kernel module as a dependency,
 *   - copies the generated `@RDMA` C++ from all kernel modules and compiles it, together
 *     with a generated aggregate bridge, into a single `librdma_user.so`,
 *   - wires the compiled plugin JS from every plugin module into the app's assets.
 *
 * Configuration (via `rdma.toml`, single source of truth):
 *   kernel.rootPackage  root package of kernel modules (default `com.example.kernel`)
 *   kernel.project      container path of kernel modules (default `:kernel`)
 *   kernel.internals    framework-owned kernel modules
 *   plugin.rootPackage  root package of plugin modules (default `com.example.plugin`)
 *   plugin.project      container path of plugin modules (default `:plugin`)
 *   hermes.path         path to the Hermes compiler (optional).
 */
class RdmaAppGradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val config = target.rdmaConfig()
        val kernelContainer = config.kernel.project.ifEmpty { DEFAULT_KERNEL_PROJECT }
        val pluginContainer = config.plugin.project.ifEmpty { DEFAULT_PLUGIN_PROJECT }
        val kernelRootPackage = config.kernel.rootPackage.ifEmpty { DEFAULT_KERNEL_ROOT_PACKAGE }
        val hermesc = config.hermes.path

        // Enumerate kernel/plugin modules by project path (stable at configuration time).
        // Gradle auto-creates the intermediate container projects (`:plugin:alice`, …),
        // so only count projects that actually have a build script. A plugin module may opt
        // out of being baked into the app (`rdmaBakeInApp=false`) — it is then delivered
        // through the store only (dynamic plugin scenario). `-PrdmaForceBakePlugins=<paths>`
        // (comma-separated project paths) bakes such modules anyway, for one build.
        val subprojects = target.rootProject.subprojects
            .filter { it.buildFile.exists() }
        val kernelModulePaths = subprojects.map { it.path }.filter { it.startsWith("$kernelContainer:") }
        val forceBaked = target.rootProject.findProperty("rdmaForceBakePlugins")?.toString()
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
        val pluginModulePaths = subprojects
            .filter { it.path in forceBaked || it.findProperty("rdmaBakeInApp")?.toString() != "false" }
            .map { it.path }
            .filter { it.startsWith("$pluginContainer:") }

        fun moduleIdOf(path: String, container: String): String {
            val suffix = path.removePrefix(container).removePrefix(":").replace(':', '_').replace('.', '_')
            return if (suffix.isEmpty()) "default" else suffix
        }

        fun modulePackageOf(path: String, container: String): String {
            val suffix = path.removePrefix(container).removePrefix(":").replace(':', '.')
            return if (suffix.isEmpty()) kernelRootPackage else "$kernelRootPackage.$suffix"
        }

        val kernelModuleIds = kernelModulePaths.map { moduleIdOf(it, kernelContainer) }
        val kernelModulePackages = kernelModulePaths.map { modulePackageOf(it, kernelContainer) }

        val genDir = File(target.buildDir, "generated/rdma")
        val cppDir = File(genDir, "cpp")
        val generatedCppDir = File(cppDir, "generated")
        val assetsKotlin = File(target.projectDir, "src/main/assets/kotlin")
        val projectDir = target.projectDir

        val kernelProjects = kernelModulePaths.mapNotNull { target.rootProject.findProject(it) }
        val pluginProjects = pluginModulePaths.mapNotNull { target.rootProject.findProject(it) }

        // Immutable, config-cache-safe values captured by the task actions.
        val kernelCppDirs = kernelProjects.map { File(it.buildDir, "generated/rdma/cpp") }
        val pluginProdSyncDirs = pluginProjects.map { File(it.buildDir, "compileSync/js/main/productionExecutable/kotlin") }

        // The shared-runtime module aggregates the common dependencies (compose runtime,
        // Kotlin stdlib, coroutines, …) into one full-DCE compilation. Its full dep modules
        // are used verbatim in the app; each plugin contributes only its own module.
        val sharedRuntimePath = target.findProperty("rdmaSharedRuntimeProject")?.toString()
            ?: target.rootProject.findProperty("rdmaSharedRuntimeProject")?.toString()
            ?: ":shared-runtime"
        // Required, even with one plugin: every plugin (baked in or delivered by the store) runs
        // on the same full libraries, while a plugin's own copies are trimmed to what that plugin
        // uses — taking one plugin's copy for everyone breaks the others.
        val sharedRuntimeProject = target.rootProject.findProject(sharedRuntimePath)
            ?: throw org.gradle.api.GradleException(
                "Kardman needs a shared runtime module ($sharedRuntimePath): the Kotlin/JS libraries every plugin " +
                    "runs on (stdlib, Compose runtime, coroutines), compiled whole with -Xir-export-all and " +
                    "-Xir-dce=false. Add it like the repository's shared-runtime/ module, or point " +
                    "rdmaSharedRuntimeProject at yours.",
            )
        val sharedRuntimeProdSyncDir = File(sharedRuntimeProject.buildDir, "compileSync/js/main/productionExecutable/kotlin")
        // A plugin's own JS module, by the Kotlin/JS module naming (`<root>-<path with dashes>`).
        val pluginModuleNames = pluginProjects.map { "${target.rootProject.name}${it.path.replace(':', '-')}.js" }
        val sharedRuntimeModuleName = "${target.rootProject.name}-shared-runtime"

        // The host's identity for re-sync and bundle checks, taken from the release state this app
        // is built with: `H` (the changelog's native version), the toolchain generation, and the
        // trusted signing keys (compatibility.md §8). Hosts use these instead of hard-coded values.
        val versionsDirForHost = (target.findProperty("rdmaVersionsDir") ?: target.rootProject.findProperty("rdmaVersionsDir"))
            ?.toString()?.let { File(it).let { f -> if (f.isAbsolute) f else File(target.rootProject.projectDir, it.toString()) } }
            ?: File(target.rootProject.projectDir, "versions")
        val hostPublicKeys = target.rdmaConfig().signing.publicKeys
        val hostKotlinDir = File(genDir, "host-kotlin")
        val generateHost = target.tasks.register("generateRdmaHost") { task ->
            task.doLast {
                writeRdmaHostKt(File(hostKotlinDir, "RdmaHost.kt"), kernelRootPackage, versionsDirForHost, hostPublicKeys)
            }
        }

        // 1) Generate the user bridge + CMake (no kernel dependency).
        val generateBridge = target.tasks.register("generateRdmaBridge") { task ->
            task.dependsOn(generateHost)
            task.doLast {
                writeUserBridgeKt(File(genDir, "UserBridge.kt"), kernelRootPackage)
                writeUserBridgeJniCpp(File(cppDir, "UserBridgeJni.cpp"), kernelRootPackage, kernelModulePackages)
                writeCMakeLists(File(cppDir, "CMakeLists.txt"))
            }
        }

        // 2) Clean, aggregate and copy the generated C++ from every kernel module.
        val kernelCompileTasks = kernelProjects.map { kp ->
            kp.provider {
                kp.tasks.findByName("compileAndroidMain")
                    ?: kp.tasks.findByName("compileKotlinJvm")
                    ?: error("No kernel Kotlin compile task found in ${kp.path}")
            }
        }
        val copyGeneratedCpp = target.tasks.register("copyGeneratedCpp") { task ->
            kernelCompileTasks.forEach { task.dependsOn(it) }
            task.doLast {
                generatedCppDir.mkdirs()
                generatedCppDir.listFiles { f -> f.isFile && (f.extension == "h" || f.extension == "cpp") }
                    ?.forEach { it.delete() }
                writeAggregateBridge(
                    File(generatedCppDir, "RdmaBridgeAggregate.h"),
                    File(generatedCppDir, "RdmaBridgeAggregate.cpp"),
                    kernelModuleIds,
                )
                for (dir in kernelCppDirs) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.extension == "h" || f.extension == "cpp") &&
                            f.name != "RdmaComposerProxy.h" && f.name != "RdmaComposerProxy.cpp"
                        ) {
                            f.copyTo(File(generatedCppDir, f.name), overwrite = true)
                        }
                    }
                }
            }
        }

        val invalidateCmake = target.tasks.register("invalidateCmake") { task ->
            task.dependsOn(copyGeneratedCpp)
            task.doLast {
                File(projectDir, ".cxx").deleteRecursively()
            }
        }

        // 2b) C ABI (iOS): generate the aggregate C-ABI bridge + the install entry, and
        // copy the generated C ABI C++ from every kernel module into `capi-cpp/`.
        val capiCppDir = File(genDir, "capi-cpp")
        val kernelCapiCppDirs = kernelProjects.map { File(it.buildDir, "generated/rdma/capi/cpp") }
        val kernelIosCompileTasks = kernelProjects.map { kp ->
            kp.provider {
                kp.tasks.findByName("compileKotlinIosSimulatorArm64")
                    ?: kp.tasks.findByName("compileKotlinIosArm64")
                    ?: error("No kernel iOS compile task found in ${kp.path}")
            }
        }
        val generateBridgeCAbi = target.tasks.register("generateRdmaBridgeCAbi") { task ->
            task.doLast {
                writeUserBridgeCAbiCpp(File(capiCppDir, "UserBridgeCAbi.cpp"))
            }
        }
        // 2c) C ABI Kotlin glue (iOS). Every kernel module generates its C ABI glue
        // (`capi/kotlin`: C-compatible entries, helpers, registration) while it compiles. The
        // app compiles that glue right after the kernel modules, in the same build — like
        // `widget-kotlin` on Android — so a clean build works in one pass and a removed
        // `@RDMA` symbol can never leave a stale file in a kernel compilation
        // (docs/design/runtime_rework.md §11). The kernel compiler plugin runs here in
        // `capi-glue` mode to fill the widget entry bodies.
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val kotlinExt = target.extensions.getByName("kotlin") as KotlinSourceSetContainer
            kotlinExt.sourceSets.matching { it.name == "iosMain" }.configureEach { ss: KotlinSourceSet ->
                kernelProjects.forEach { kp -> ss.kotlin.srcDir(File(kp.buildDir, "generated/rdma/capi/kotlin")) }
                ss.kotlin.srcDir(hostKotlinDir)
            }
            target.tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach { task ->
                kernelProjects.forEach { kp -> task.dependsOn(kp.tasks.matching { it.name == task.name }) }
                task.dependsOn(generateHost)
            }
            val glueModules = kernelModulePaths.mapNotNull { path ->
                val kp = target.rootProject.findProject(path) ?: return@mapNotNull null
                val api = File(kp.buildDir, "generated/rdma/rdma_api.json").absolutePath
                "${moduleIdOf(path, kernelContainer)}=${modulePackageOf(path, kernelContainer)}=$api"
            }.joinToString(";")
            target.extensions.extraProperties.set(RdmaCAbiGlueSubplugin.GLUE_MODULES_PROPERTY, glueModules)
            target.pluginManager.apply(RdmaCAbiGlueSubplugin::class.java)

            // Kernel types in the framework's ObjC header, so Swift code of the host can use them
            // (implement a kernel interface, call a kernel service). Kotlin/Native puts only the
            // framework's own API there unless a dependency is `api` and `export`ed; whole kernel
            // modules are exported for now. Opt out with `rdmaExportKernelToObjC=false`.
            val exportKernel = (target.findProperty("rdmaExportKernelToObjC")?.toString() ?: "true") != "false"
            if (exportKernel) {
                kotlinExt.sourceSets.matching { it.name == "iosMain" }.configureEach { ss: KotlinSourceSet ->
                    ss.dependencies { kernelProjects.forEach { kp -> api(kp) } }
                }
                (kotlinExt as KotlinMultiplatformExtension).targets.withType(KotlinNativeTarget::class.java).configureEach { t ->
                    t.binaries.withType(Framework::class.java).configureEach { fw ->
                        kernelProjects.forEach { kp -> fw.export(kp) }
                    }
                }
            }
        }

        val copyGeneratedCppCAbi = target.tasks.register("copyGeneratedCppCAbi") { task ->
            kernelIosCompileTasks.forEach { task.dependsOn(it) }
            task.dependsOn(generateBridgeCAbi)
            task.doLast {
                capiCppDir.mkdirs()
                capiCppDir.listFiles { f -> f.isFile && (f.extension == "h" || f.extension == "cpp") }
                    ?.forEach { it.delete() }
                writeUserBridgeCAbiCpp(File(capiCppDir, "UserBridgeCAbi.cpp"))
                writeAggregateBridgeCAbi(
                    File(capiCppDir, "RdmaBridgeAggregate.h"),
                    File(capiCppDir, "RdmaBridgeAggregate.cpp"),
                    kernelModuleIds,
                )
                for (dir in kernelCapiCppDirs) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.extension == "h" || f.extension == "cpp") &&
                            f.name != "RdmaComposerProxy.h" && f.name != "RdmaComposerProxy.cpp"
                        ) {
                            f.copyTo(File(capiCppDir, f.name), overwrite = true)
                        }
                    }
                }
            }
        }

        // 3) Wire the compiled plugin JS of every plugin module into the app's assets.
        // `hermesc` is resolved from the configured `rdmaHermesc` path or, when unset, from
        // the `hermesc` maven artifact (see `resolveHermesc`), so the AOT path is always used.
        // The shared-runtime bundles are emitted even with no baked-in plugins, because
        // store-delivered (dynamic) plugins depend on them at load time.
        run {
            val (hermescResolve, hermescBinary) = resolveHermesc(target, hermesc ?: "")
            val aotCompileJs = target.tasks.register("aotCompileJs") { task ->
                pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsProductionExecutableCompileSync") }
                task.dependsOn("${sharedRuntimeProject.path}:jsProductionExecutableCompileSync")
                if (hermescResolve != null) {
                    task.dependsOn(hermescResolve)
                }
                task.doLast {
                    compileSharedBundles(hermescBinary.get(), assetsKotlin, sharedRuntimeProdSyncDir, pluginProdSyncDirs, pluginModuleNames, sharedRuntimeModuleName)
                }
            }
            target.pluginManager.withPlugin("com.android.application") {
                target.tasks.named("preBuild") { it.dependsOn(aotCompileJs) }
                target.tasks.configureEach(Action<Task> { task ->
                    if (task.name == "mergeDebugAssets" || task.name == "mergeReleaseAssets") {
                        pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsProductionExecutableCompileSync") }
                    }
                })
            }
            // iOS: emit the same AOT-compiled `.hbc` bundles into the app's build dir
            // (`build/generated/rdma/hbc`). The Xcode project copies them into the app
            // bundle and the host evaluates them via RdmaBridge.nativeEvalBytes.
            target.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
                val hbcDir = File(genDir, "hbc")
                target.tasks.register("aotCompileJsCAbi") { task ->
                    pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsProductionExecutableCompileSync") }
                    task.dependsOn("${sharedRuntimeProject.path}:jsProductionExecutableCompileSync")
                    if (hermescResolve != null) {
                        task.dependsOn(hermescResolve)
                    }
                    task.doLast {
                        compileSharedBundles(hermescBinary.get(), hbcDir, sharedRuntimeProdSyncDir, pluginProdSyncDirs, pluginModuleNames, sharedRuntimeModuleName)
                    }
                }
            }
        }

        // 4) Android wiring: dependencies + native build + source dirs + task deps.
        target.pluginManager.withPlugin("com.android.application") {
            val android = target.extensions.getByType(ApplicationExtension::class.java)

            // Framework + kernel dependencies.
            kernelProjects.forEach { kp ->
                target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to kp.path)))
            }
            target.dependencies.add("implementation", "io.github.dendygrobovshik.kardman:rdma-runtime:$RDMA_RUNTIME_VERSION")
            target.dependencies.add("implementation", "com.facebook.hermes:hermes-android:$HERMES_VERSION")

            android.buildFeatures {
                prefab = true
            }
            android.packaging {
                jniLibs {
                    useLegacyPackaging = true
                    pickFirsts.add("**/libhermesvm.so")
                    pickFirsts.add("**/libc++_shared.so")
                    pickFirsts.add("**/librdma_runtime.so")
                }
            }
            android.externalNativeBuild {
                cmake {
                    path = File(cppDir, "CMakeLists.txt")
                }
            }
            android.defaultConfig {
                externalNativeBuild {
                    cmake {
                        arguments("-DANDROID_STL=c++_shared")
                    }
                }
            }

            // Contract tests (compatibility.md §10): the kernel modules' `commonTest` runs as the
            // app's instrumented tests, against the native code or with polyfills loaded by the
            // generated runner (scripts/contract-test.sh drives both runs).
            // Outside `generated/rdma`, which is a `main` source dir: the runner is test-only.
            val contractDir = File(target.buildDir, "generated/rdma-contract")
            writeContractRunner(File(contractDir, "RdmaContractRunner.kt"), kernelRootPackage)
            android.defaultConfig.testInstrumentationRunner = "$kernelRootPackage.RdmaContractRunner"
            val androidTest = android.sourceSets.getByName("androidTest")
            androidTest.kotlin.srcDir(contractDir)
            kernelProjects.forEach { kernel -> androidTest.kotlin.srcDir(File(kernel.projectDir, "src/commonTest/kotlin")) }
            target.dependencies.add("androidTestImplementation", "androidx.test:runner:1.7.0")
            target.dependencies.add("androidTestImplementation", "junit:junit:4.13.2")
            target.dependencies.add(
                "androidTestImplementation",
                "org.jetbrains.kotlin:kotlin-test-junit:${org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(target.logger)}",
            )

            val main = android.sourceSets.getByName("main")
            main.kotlin.srcDir(genDir) // includes host-kotlin/
            kernelProjects.forEach { kernel ->
                main.kotlin.srcDir(File(kernel.buildDir, "generated/rdma/widget-kotlin"))
            }

            target.tasks.configureEach(Action<Task> { task ->
                if (task.name.startsWith("compile") && task.name.endsWith("Kotlin")) {
                    task.dependsOn(generateBridge)
                }
                if (task.name.startsWith("configureCMake")) {
                    task.dependsOn(generateBridge)
                    task.dependsOn(invalidateCmake)
                }
            })
        }
    }

    /**
     * Returns the path to the `hermesc` compiler binary, plus the task that produces it.
     *
     * If `rdmaHermesc` points at an existing file it is used as-is (no resolve task).
     * Otherwise the `hermesc` maven artifact (`io.github.dendygrobovshik.kardman:hermesc`)
     * is resolved for the current OS/architecture and extracted to the build dir, so a
     * consumer project never has to vendor the binary itself.
     */
    private fun resolveHermesc(
        target: Project,
        configured: String,
    ): Pair<TaskProvider<Task>?, Provider<String>> {
        if (configured.isNotBlank()) {
            val file = if (File(configured).isAbsolute) File(configured)
            else File(target.rootProject.projectDir, configured)
            if (file.isFile) {
                return null to target.providers.provider { file.absolutePath }
            }
        }

        val classifier = hostClassifier()
        val config = target.configurations.create("rdmaHermescTool")
        config.setTransitive(false)
        config.setCanBeConsumed(false)
        target.dependencies.add(
            config.name,
            "io.github.dendygrobovshik.kardman:hermesc:$RDMA_RUNTIME_VERSION:$classifier@zip",
        )

        // Resolve eagerly so the task action only captures plain Files (configuration-cache
        // safe); the hermesc artifact is tiny and always local.
        val zip = config.singleFile
        val out = File(target.buildDir, "rdma/hermesc/hermesc")
        val extract = target.tasks.register("resolveRdmaHermesc") { task ->
            task.outputs.file(out)
            task.doLast {
                val tmp = File(out.parentFile, "tmp")
                tmp.deleteRecursively()
                tmp.mkdirs()
                ZipInputStream(FileInputStream(zip)).use { zin ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        val entryFile = File(tmp, entry.name)
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { fos -> zin.copyTo(fos) }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
                out.parentFile.mkdirs()
                File(tmp, "hermesc").copyTo(out, overwrite = true)
                out.setExecutable(true)
            }
        }
        return extract to target.providers.provider { out.absolutePath }
    }

    /** Maps the host OS/architecture to the classifier used for the `hermesc` artifact. */
    private fun hostClassifier(): String {
        val osName = System.getProperty("os.name").lowercase()
        val os = when {
            osName.contains("mac") -> "macos"
            osName.contains("linux") -> "linux"
            osName.contains("win") -> "windows"
            else -> osName.replace(" ", "")
        }
        val archName = System.getProperty("os.arch").lowercase()
        val arch = when (archName) {
            "aarch64", "arm64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> archName
        }
        return "$os-$arch"
    }

    /**
     * Collects the shared-runtime's full dependency modules plus each plugin's own module,
     * AOT-compiles them to Hermes bytecode, and writes an ordered `rdma-modules.json`
     * manifest (topological order so dependencies load before their dependents).
     */
    private fun compileSharedBundles(
        hermesc: String,
        outDir: File,
        sharedRuntimeProdSyncDir: File,
        pluginProdSyncDirs: List<File>,
        pluginModuleNames: List<String>,
        sharedRuntimeModuleName: String,
    ) {
        val jsByName = linkedMapOf<String, File>()
        val sharedNames = mutableSetOf<String>()
        sharedRuntimeProdSyncDir.listFiles { f -> f.extension == "js" }?.forEach { js ->
            if (js.name != "$sharedRuntimeModuleName.js") {
                sharedNames.add(js.name)
                jsByName[js.name] = js
            }
        }
        // Every library comes from the shared runtime; a plugin contributes only its own module.
        val missing = sortedSetOf<String>()
        for (srcDir in pluginProdSyncDirs) {
            srcDir.listFiles { f -> f.extension == "js" }?.forEach { js ->
                when {
                    js.name in sharedNames -> {}
                    js.name in pluginModuleNames -> jsByName[js.name] = js
                    else -> missing += js.name.removeSuffix(".js")
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Plugins use libraries the shared runtime does not contain: ${missing.joinToString()}. " +
                    "Add them to the shared runtime module's dependencies.",
            )
        }

        val order = topologicalOrder(jsByName)

        outDir.mkdirs()
        outDir.listFiles { f -> f.extension in listOf("js", "map", "hbc", "json") }?.forEach { it.delete() }

        for (name in order) {
            val js = jsByName[name] ?: continue
            val hbc = File(outDir, name.removeSuffix(".js") + ".hbc")
            val cmd = listOf(hermesc, "-O", "-emit-binary", "-out", hbc.absolutePath, js.absolutePath)
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) {
                throw org.gradle.api.GradleException("hermesc failed for $name:\n$output")
            }
        }

        val modules = order.map { it.removeSuffix(".js") }
        File(outDir, "rdma-modules.json").writeText(
            modules.joinToString(prefix = "{\"modules\":[", postfix = "]}") { "\"$it\"" },
        )
    }

    /** Returns module base names in load order (Kahn's algorithm over the AMD `define` deps). */
    private fun topologicalOrder(files: Map<String, File>): List<String> {
        val names = files.keys.toList()
        val deps = names.associateWith { name -> parseModuleDeps(files[name]!!).filter { it in files }.toSet() }
        val indegree = names.associateWith { 0 }.toMutableMap()
        val dependents = mutableMapOf<String, MutableSet<String>>()
        for ((name, ds) in deps) {
            for (d in ds) {
                dependents.getOrPut(d) { sortedSetOf() }.add(name)
                indegree[name] = indegree[name]!! + 1
            }
        }
        val queue = ArrayDeque<String>()
        names.filter { indegree[it] == 0 }.sorted().forEach { queue.add(it) }
        val result = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            result.add(n)
            for (m in (dependents[n] ?: emptySet()).sorted()) {
                indegree[m] = indegree[m]!! - 1
                if (indegree[m] == 0) queue.add(m)
            }
        }
        if (result.size < names.size) {
            (names - result.toSet()).sorted().forEach { result.add(it) }
        }
        return result
    }

    /** Extracts the direct dependency base names from a module's AMD `define([...])` header. */
    private fun parseModuleDeps(file: File): List<String> {
        val text = file.readText()
        val marker = "define(['"
        val start = text.indexOf(marker)
        if (start < 0) return emptyList()
        val begin = start + marker.length
        val end = text.indexOf("]", begin)
        if (end < 0) return emptyList()
        return text.substring(begin, end)
            .split(",")
            .map { it.trim().trim('\'', '"') }
            .filter { it != "exports" && it.startsWith("./") }
            .map { it.removePrefix("./") }
    }

    private fun writeContractRunner(file: File, kernelPackage: String) {
        file.parentFile.mkdirs()
        file.writeText(
            """package $kernelPackage

import android.os.Bundle
import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import org.json.JSONObject

/**
 * Instrumentation runner of the kernel contract tests (generated by the rdma-app plugin).
 * Starts the plugin runtime with the app's bundles before the tests, then loads the polyfill
 * files named by the `rdmaPolyfills` argument (comma-separated, readable by the app): kernel
 * calls of the polyfilled functions then run in JS through their hotfix slots, so the same
 * tests check the native code (no argument) and each polyfill.
 */
class RdmaContractRunner : androidx.test.runner.AndroidJUnitRunner() {
    private var polyfills: List<String> = emptyList()

    override fun onCreate(arguments: Bundle?) {
        polyfills = arguments?.getString("rdmaPolyfills").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        super.onCreate(arguments)
    }

    override fun onStart() {
        val context = targetContext
        runOnMainSync {
            UserBridge.nativeInstall()
            RdmaBridge.nativeInit(context.assets)
        }
        val manifest = context.assets.open("kotlin/rdma-modules.json").use { it.bufferedReader().readText() }
        val modules = JSONObject(manifest).getJSONArray("modules")
        for (i in 0 until modules.length()) RdmaBridge.nativeEvalAsset("kotlin/" + modules.getString(i) + ".hbc")
        polyfills.forEach { RdmaBridge.stageUpdate(it) }
        RdmaBridge.applyUpdates()
        val deadline = System.currentTimeMillis() + 30_000
        while (!RdmaBridge.nativeIsReady()) {
            check(System.currentTimeMillis() < deadline) { "the plugin runtime did not get ready" }
            Thread.sleep(10)
        }
        android.util.Log.i("RDMA_CONTRACT", "runtime ready, polyfills: " + polyfills)
        super.onStart()
    }
}
""",
        )
    }

    private fun writeRdmaHostKt(file: File, kernelPackage: String, versionsDir: File, publicKeys: List<String>) {
        val slurper = groovy.json.JsonSlurper()
        val h = File(versionsDir, "changelog.json").takeIf { it.isFile }
            ?.let { ((slurper.parseText(it.readText()) as Map<*, *>)["h"] as? Number)?.toInt() } ?: 0
        // The app is built with the newest active generation.
        val g = File(versionsDir, "toolchain.json").takeIf { it.isFile }
            ?.let { (slurper.parseText(it.readText()) as Map<*, *>)["generations"] as? List<*> }
            ?.mapNotNull { it as? Map<*, *> }
            ?.lastOrNull { (it["status"] ?: "active") == "active" }
            ?.get("g")?.toString() ?: ""
        file.parentFile.mkdirs()
        file.writeText(
            """package $kernelPackage

/**
 * This app's identity for the store, from the release state it was built with
 * (generated by the rdma-app plugin; do not edit).
 */
object RdmaHost {
    /** The native kernel version `H` baked into this app. */
    const val VERSION: Int = $h

    /** The toolchain generation `G` this app was built with ("" when there are none). */
    const val GENERATION: String = "$g"

    /** Trusted Ed25519 public keys for downloaded bundles (raw 32 bytes, base64). */
    val PUBLIC_KEYS: List<String> = listOf(${publicKeys.joinToString { "\"$it\"" }})
}
""",
        )
    }

    private fun writeUserBridgeKt(file: File, kernelPackage: String) {
        file.parentFile.mkdirs()
        file.writeText(
            """package $kernelPackage

/**
 * Loads the user-side native bridge (librdma_user.so) and registers it with the
 * generic framework runtime. Must be initialized before `RdmaBridge.nativeInit(...)`.
 */
object UserBridge {
    init {
        System.loadLibrary("rdma_user")
    }

    external fun nativeInstall()
}
""",
        )
    }

    private fun jniPrefix(kernelPackage: String): String =
        "Java_" + kernelPackage.replace('.', '_') + "_"

    private fun writeUserBridgeJniCpp(file: File, kernelPackage: String, kernelModulePackages: List<String>) {
        file.parentFile.mkdirs()
        val p = jniPrefix(kernelPackage)
        val sb = StringBuilder()
        sb.append(
            """#include <jni.h>
#include <string>
#include <android/log.h>

#include "RdmaBridgeAggregate.h"
#include "RdmaCompose.h"
#include "RdmaVtable.h"

#define LOG_TAG "RdmaUserBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Registers the aggregate user bridge (composing all kernel modules) with the
// generic runtime. The runtime invokes it at the end of installRdmaComposeBridge.
extern "C" JNIEXPORT void JNICALL
${p}UserBridge_nativeInstall(JNIEnv* env, jclass) {
    facebook::rdma::rdmaSetUserBridgeInstaller(&facebook::rdma::installUserBridge);
    facebook::rdma::rdmaSetUserBridgeJniInit(&facebook::rdma::initUserBridgeJniCaches);
    facebook::rdma::rdmaSetObjectWrapper(&facebook::rdma::wrapUserObject);
    LOGI("User bridge installer registered");
}

""",
        )

        // Kernel-side vtable dispatch: one JNI export per kernel module package; the logic
        // (threads, marshalling, exceptions) lives in the runtime.
        for (modulePackage in kernelModulePackages.distinct()) {
            val mp = modulePackage.replace('.', '_')
            sb.append(
                """// Kernel module package: $modulePackage
extern "C" JNIEXPORT jobject JNICALL
Java_${mp}_RdmaVtableKt_rdmaVtableDispatch(JNIEnv* env, jclass, jlong vtablePtr, jint vtableId, jobjectArray args,
                                            jstring paramDescs, jstring resultDesc, jobject fallthrough) {
    return facebook::rdma::rdmaVtableInvokeJni(env, vtablePtr, vtableId, args, paramDescs, resultDesc, fallthrough);
}

// Hotfix dispatch slots of this kernel module (docs/design/runtime_rework.md §6).
extern "C" JNIEXPORT void JNICALL
Java_${mp}_RdmaSlotsNativeKt_rdmaSlotRegister(JNIEnv* env, jclass, jobject cell, jstring fqn) {
    facebook::rdma::rdmaSlotRegisterJni(env, cell, fqn);
}

extern "C" JNIEXPORT jobject JNICALL
Java_${mp}_RdmaSlotsNativeKt_rdmaSlotInvokeNative(JNIEnv* env, jclass, jlong blockId, jobjectArray args,
                                                   jstring paramDescs, jstring resultDesc, jobject fallthrough) {
    return facebook::rdma::rdmaSlotInvokeJni(env, blockId, args, paramDescs, resultDesc, fallthrough);
}
""",
            )
        }

        file.writeText(sb.toString())
    }

    private fun writeAggregateBridge(header: File, cpp: File, moduleIds: List<String>) {
        header.parentFile.mkdirs()
        header.writeText(
            """#pragma once
#include <jsi/jsi.h>
#include <jni.h>

namespace facebook {
namespace rdma {

void installUserBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma);
void initUserBridgeJniCaches(JNIEnv* env);
jsi::Value wrapUserObject(jsi::Runtime& rt, JavaVM* jvm, jobject obj);

} // namespace rdma
} // namespace facebook
""",
        )

        val ids = moduleIds.distinct()
        val sb = StringBuilder()
        sb.append("#include \"RdmaBridgeAggregate.h\"\n")
        for (id in ids) {
            sb.append("#include \"RdmaBridge_$id.h\"\n")
        }
        sb.append("""
#include <string>
#include <utility>
#include <android/log.h>

#define LOG_TAG "RdmaBridgeAggregate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace facebook {
namespace rdma {

static jsi::Value createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides);

void initUserBridgeJniCaches(JNIEnv* env) {
""")
        for (id in ids) {
            sb.append("    $id::initUserBridgeJniCaches(env);\n")
        }
        sb.append("""}

void installUserBridge(jsi::Runtime& rt, JavaVM* jvm, jsi::Object& rdma) {
""")
        for (id in ids) {
            sb.append("    $id::installUserBridge(rt, jvm, rdma);\n")
        }
        sb.append("""
    {
        auto createOverridesFn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "createWithOverrides"), 3,
            [jvm](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 3) return jsi::Value::undefined();
                std::string className = args[0].getString(r).utf8(r);
                jsi::Array ctorArgs = args[1].asObject(r).asArray(r);
                jsi::Object overrides = args[2].asObject(r);
                return createWithOverrides(r, jvm, className, ctorArgs, overrides);
            }
        );
        rdma.setProperty(rt, "createWithOverrides", std::move(createOverridesFn));
    }
    LOGI("RDMA aggregate user bridge installed");
}

static jsi::Value createWithOverrides(jsi::Runtime& rt, JavaVM* jvm, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides) {
""")
        for (id in ids) {
            sb.append("    {\n")
            sb.append("        jsi::Value v = $id::createWithOverrides(rt, jvm, className, ctorArgs, overrides);\n")
            sb.append("        if (!v.isUndefined()) return v;\n")
            sb.append("    }\n")
        }
        sb.append("""    return jsi::Value::undefined();
}

jsi::Value wrapUserObject(jsi::Runtime& rt, JavaVM* jvm, jobject obj) {
""")
        for (id in ids) {
            sb.append("    {\n")
            sb.append("        jsi::Value v = $id::wrapUserObject(rt, jvm, obj);\n")
            sb.append("        if (!v.isUndefined()) return v;\n")
            sb.append("    }\n")
        }
        sb.append("""    return jsi::Value::undefined();
}

} // namespace rdma
} // namespace facebook
""")
        cpp.writeText(sb.toString())
    }

    private fun writeCMakeLists(file: File) {
        file.parentFile.mkdirs()
        file.writeText(
            """cmake_minimum_required(VERSION 3.22.1)
project(RdmaUserBridge)
set(CMAKE_CXX_STANDARD 17)

find_package(rdma-runtime REQUIRED CONFIG)
find_package(hermes-engine REQUIRED CONFIG)

file(GLOB GENERATED_SOURCES "${'$'}{CMAKE_CURRENT_SOURCE_DIR}/generated/*.cpp")

add_library(rdma_user SHARED
        UserBridgeJni.cpp
        ${'$'}{GENERATED_SOURCES}
)

target_include_directories(rdma_user PRIVATE
        ${'$'}{CMAKE_CURRENT_SOURCE_DIR}/generated
)

target_link_libraries(rdma_user
        rdma-runtime::rdma_runtime
        hermes-engine::hermesvm
        android
        log
)
""",
        )
    }

    // ---------------------------------------------------------------- C ABI (iOS)

    private fun writeUserBridgeCAbiCpp(file: File) {
        file.parentFile.mkdirs()
        file.writeText(
            """#include "RdmaBridgeAggregate.h"
#include "RdmaComposeCAbi.h"

// Registers the aggregate C-ABI user bridge (composing all kernel modules) with the
// generic runtime. Called once from the iOS host before rdmaStart().
extern "C" void rdma_userBridgeInstall() {
    facebook::rdma::rdmaSetUserBridgeInstaller(&facebook::rdma::installUserBridge);
    facebook::rdma::rdmaSetObjectWrapper(&facebook::rdma::wrapUserObject);
}
""",
        )
    }

    private fun writeAggregateBridgeCAbi(header: File, cpp: File, moduleIds: List<String>) {
        header.parentFile.mkdirs()
        header.writeText(
            """#pragma once
#include <jsi/jsi.h>
#include "RdmaPlatform.h"

namespace facebook {
namespace rdma {

void installUserBridge(jsi::Runtime& rt, HostContext ctx, jsi::Object& rdma);
jsi::Value wrapUserObject(jsi::Runtime& rt, void* handle);

} // namespace rdma
} // namespace facebook
""",
        )

        val ids = moduleIds.distinct()
        val sb = StringBuilder()
        sb.append("#include \"RdmaBridgeAggregate.h\"\n")
        for (id in ids) {
            sb.append("#include \"RdmaBridge_$id.h\"\n")
        }
        sb.append(
            """
#include <string>
#include <utility>

namespace facebook {
namespace rdma {

static jsi::Value createWithOverridesImpl(jsi::Runtime& rt, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides);

void installUserBridge(jsi::Runtime& rt, HostContext ctx, jsi::Object& rdma) {
""",
        )
        for (id in ids) {
            sb.append("    $id::installUserBridge(rt, rdma);\n")
        }
        sb.append(
            """
    {
        auto createOverridesFn = jsi::Function::createFromHostFunction(
            rt, jsi::PropNameID::forAscii(rt, "createWithOverrides"), 3,
            [](jsi::Runtime& r, const jsi::Value&, const jsi::Value* args, size_t count) -> jsi::Value {
                if (count < 3) return jsi::Value::undefined();
                std::string className = args[0].getString(r).utf8(r);
                jsi::Array ctorArgs = args[1].asObject(r).asArray(r);
                jsi::Object overrides = args[2].asObject(r);
                return createWithOverridesImpl(r, className, ctorArgs, overrides);
            }
        );
        rdma.setProperty(rt, "createWithOverrides", std::move(createOverridesFn));
    }
}

static jsi::Value createWithOverridesImpl(jsi::Runtime& rt, const std::string& className, const jsi::Array& ctorArgs, const jsi::Object& overrides) {
""",
        )
        for (id in ids) {
            sb.append("    {\n")
            sb.append("        jsi::Value v = $id::createWithOverrides(rt, className, ctorArgs, overrides);\n")
            sb.append("        if (!v.isUndefined()) return v;\n")
            sb.append("    }\n")
        }
        sb.append(
            """    return jsi::Value::undefined();
}

jsi::Value wrapUserObject(jsi::Runtime& rt, void* handle) {
""",
        )
        for (id in ids) {
            sb.append("    {\n")
            sb.append("        jsi::Value v = $id::wrapUserObject(rt, handle);\n")
            sb.append("        if (!v.isUndefined()) return v;\n")
            sb.append("    }\n")
        }
        sb.append(
            """    return jsi::Value::undefined();
}

} // namespace rdma
} // namespace facebook
""",
        )
        cpp.writeText(sb.toString())
    }
}
