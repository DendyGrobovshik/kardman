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
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import java.io.File

private const val DEFAULT_KERNEL_PACKAGE = "com.example.kernel"
private const val DEFAULT_KERNEL_PROJECT = ":kernel"
private const val DEFAULT_PLUGIN_PROJECT = ":plugin"
private const val RDMA_RUNTIME_VERSION = "1.0"
private const val HERMES_VERSION = "0.76.9"

/**
 * Applied to the app module. It:
 *   - adds the framework dependencies (`:kernel`, `rdma-runtime-android`, `hermes-android`),
 *   - generates the user-side native bridge (UserBridge.kt + UserBridgeJni.cpp + CMakeLists.txt)
 *     and compiles the generated `@RDMA` C++ into `librdma_user.so`,
 *   - wires the compiled plugin JS into the app's assets.
 *
 * Configuration (via `gradle.properties`, single source of truth):
 *   rdmaKernelPackage  package of the kernel module (default `com.example.kernel`)
 *   rdmaKernelProject  Gradle path of the kernel module (default `:kernel`)
 *   rdmaPluginProject  Gradle path of the plugin module (default `:plugin`)
 *   rdmaHermesc        path to the Hermes compiler (optional). When set, the plugin JS is
 *                      AOT-compiled to `.hbc`; otherwise the JS is copied as source.
 */
class RdmaAppGradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val kernelPackage = target.findProperty("rdmaKernelPackage")?.toString()
            ?: target.rootProject.findProperty("rdmaKernelPackage")?.toString()
            ?: DEFAULT_KERNEL_PACKAGE
        val kernelProjectPath = target.findProperty("rdmaKernelProject")?.toString()
            ?: target.rootProject.findProperty("rdmaKernelProject")?.toString()
            ?: DEFAULT_KERNEL_PROJECT
        val pluginProjectPath = target.findProperty("rdmaPluginProject")?.toString()
            ?: target.rootProject.findProperty("rdmaPluginProject")?.toString()
            ?: DEFAULT_PLUGIN_PROJECT
        val hermesc = target.findProperty("rdmaHermesc")?.toString()
            ?: target.rootProject.findProperty("rdmaHermesc")?.toString()

        val genDir = File(target.buildDir, "generated/rdma")
        val cppDir = File(genDir, "cpp")
        val generatedCppDir = File(cppDir, "generated")
        val assetsKotlin = File(target.projectDir, "src/main/assets/kotlin")

        val kernelProject = target.rootProject.findProject(kernelProjectPath)
        val pluginProject = target.rootProject.findProject(pluginProjectPath)

        // 1) Generate the bridge sources as a task (config-cache safe: no file
        //    writes at configuration time).
        val generateBridge = target.tasks.register("generateRdmaBridge") { task ->
            task.doLast {
                writeUserBridgeKt(File(genDir, "UserBridge.kt"), kernelPackage)
                writeUserBridgeJniCpp(File(cppDir, "UserBridgeJni.cpp"), kernelPackage)
                writeCMakeLists(File(cppDir, "CMakeLists.txt"))
            }
        }

        // 2) Copy the kernel's generated C++ into the app and force CMake re-config.
        val copyGeneratedCpp = if (kernelProject != null) {
            val kernelCppDir = File(kernelProject.buildDir, "generated/rdma/cpp")
            target.tasks.register("copyGeneratedCpp") { task ->
                task.dependsOn("$kernelProjectPath:compileKotlinJvm")
                task.doLast {
                    generatedCppDir.mkdirs()
                    kernelCppDir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.extension == "h" || f.extension == "cpp") &&
                            f.name != "RdmaComposerProxy.h" && f.name != "RdmaComposerProxy.cpp"
                        ) {
                            f.copyTo(File(generatedCppDir, f.name), overwrite = true)
                        }
                    }
                }
            }
        } else {
            null
        }

        val invalidateCmake = if (copyGeneratedCpp != null) {
            val cxxDir = File(target.projectDir, ".cxx")
            target.tasks.register("invalidateCmake") { task ->
                task.dependsOn(copyGeneratedCpp)
                task.doLast {
                    cxxDir.deleteRecursively()
                }
            }
        } else {
            null
        }

        // 3) Wire the compiled plugin JS into the app's assets.
        if (pluginProject != null) {
            if (!hermesc.isNullOrBlank()) {
                val hc = if (File(hermesc).isAbsolute) hermesc
                else File(target.rootProject.projectDir, hermesc).absolutePath
                val aotCompileJs = target.tasks.register("aotCompileJs") { task ->
                    task.dependsOn("$pluginProjectPath:jsProductionExecutableCompileSync")
                    val srcDir = File(pluginProject.buildDir, "compileSync/js/main/productionExecutable/kotlin")
                    task.doLast {
                        assetsKotlin.mkdirs()
                        assetsKotlin.listFiles { f -> f.extension in listOf("js", "map", "hbc") }?.forEach { it.delete() }
                        srcDir.listFiles { f -> f.extension == "js" }?.forEach { js ->
                            val hbc = File(assetsKotlin, js.nameWithoutExtension + ".hbc")
                            val cmd = listOf(hc, "-O", "-emit-binary", "-out", hbc.absolutePath, js.absolutePath)
                            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                            val output = proc.inputStream.bufferedReader().readText()
                            if (proc.waitFor() != 0) {
                                throw org.gradle.api.GradleException("hermesc failed for ${js.name}:\n$output")
                            }
                        }
                    }
                }
                target.tasks.named("preBuild") { it.dependsOn(aotCompileJs) }
                target.tasks.configureEach(Action<Task> { task ->
                    if (task.name == "mergeDebugAssets" || task.name == "mergeReleaseAssets") {
                        task.dependsOn("$pluginProjectPath:jsProductionExecutableCompileSync")
                    }
                })
            } else {
                val copyPluginJs = target.tasks.register("copyPluginJs") { task ->
                    task.dependsOn("$pluginProjectPath:jsBrowserDevelopmentExecutableDistribution")
                    val srcDir = File(pluginProject.buildDir, "compileSync/js/main/developmentExecutable/kotlin")
                    task.doLast {
                        assetsKotlin.mkdirs()
                        assetsKotlin.listFiles { f -> f.extension in listOf("js", "map") }?.forEach { it.delete() }
                        srcDir.listFiles { f -> f.extension in listOf("js", "map") }?.forEach { f ->
                            f.copyTo(File(assetsKotlin, f.name), overwrite = true)
                        }
                    }
                }
                target.tasks.named("preBuild") { it.dependsOn(copyPluginJs) }
                target.tasks.configureEach(Action<Task> { task ->
                    if (task.name == "mergeDebugAssets") {
                        task.dependsOn("$pluginProjectPath:jsBrowserDevelopmentExecutableDistribution")
                    }
                    if (task.name == "mergeReleaseAssets") {
                        task.dependsOn("$pluginProjectPath:jsBrowserProductionExecutableDistribution")
                    }
                })
            }
        }

        // 4) Android wiring: dependencies + native build + source dirs + task deps.
        target.pluginManager.withPlugin("com.android.application") {
            val android = target.extensions.getByType(ApplicationExtension::class.java)

            // Framework dependencies.
            kernelProject?.let {
                target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to kernelProjectPath)))
            }
            target.dependencies.add("implementation", "io.github.dendygrobovshik.kardman:rdma-runtime-android:$RDMA_RUNTIME_VERSION")
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

            val main = android.sourceSets.getByName("main")
            main.kotlin.srcDir(genDir)
            kernelProject?.let { kernel ->
                main.kotlin.srcDir(File(kernel.buildDir, "generated/rdma/widget-kotlin"))
            }

            target.tasks.configureEach(Action<Task> { task ->
                if (task.name.startsWith("compile") && task.name.endsWith("Kotlin")) {
                    task.dependsOn(generateBridge)
                }
                if (task.name.startsWith("configureCMake")) {
                    task.dependsOn(generateBridge)
                    invalidateCmake?.let { task.dependsOn(it) }
                }
            })
        }
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

    private fun writeUserBridgeJniCpp(file: File, kernelPackage: String) {
        file.parentFile.mkdirs()
        val p = jniPrefix(kernelPackage)
        file.writeText(
            """#include <jni.h>
#include <string>
#include <android/log.h>

#include "RdmaBridge.h"
#include "RdmaCompose.h"
#include "RdmaVtable.h"

#define LOG_TAG "RdmaUserBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Registers the user's generated bridge (installUserBridge) with the generic
// runtime. The runtime invokes it at the end of installRdmaComposeBridge.
extern "C" JNIEXPORT void JNICALL
${p}UserBridge_nativeInstall(JNIEnv* env, jclass) {
    facebook::rdma::rdmaSetUserBridgeInstaller(&facebook::rdma::installUserBridge);
    facebook::rdma::rdmaSetUserBridgeJniInit(&facebook::rdma::initUserBridgeJniCaches);
    LOGI("User bridge installer registered");
}

// Kernel-side vtable dispatch: called from Kotlin open methods of @RDMA classes.
extern "C" JNIEXPORT jobject JNICALL
${p}RdmaVtableKt_rdmaVtableDispatch(JNIEnv* env, jclass, jlong vtablePtr, jint vtableId) {
    if (vtablePtr == 0) return nullptr;

    auto* vt = reinterpret_cast<RdmaVtable*>(vtablePtr);
    if (vtableId < 0 || (size_t)vtableId >= vt->entries.size()) return nullptr;
    auto& entry = vt->entries[vtableId];
    if (!entry) return nullptr;

    try {
        auto& irt = *(facebook::jsi::IRuntime*)vt->rt;
        facebook::jsi::Value result = entry->call(irt, nullptr, 0);
        if (result.isString()) {
            std::string s = result.getString(*vt->rt).utf8(*vt->rt);
            return env->NewStringUTF(s.c_str());
        }
    } catch (const std::exception& e) {
        LOGI("rdmaVtableDispatch error: %s", e.what());
    }
    return nullptr;
}
""",
        )
    }

    private fun writeCMakeLists(file: File) {
        file.parentFile.mkdirs()
        file.writeText(
            """cmake_minimum_required(VERSION 3.22.1)
project(RdmaUserBridge)
set(CMAKE_CXX_STANDARD 17)

find_package(rdma-runtime-android REQUIRED CONFIG)
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
        rdma-runtime-android::rdma_runtime
        hermes-engine::hermesvm
        android
        log
)
""",
        )
    }
}
