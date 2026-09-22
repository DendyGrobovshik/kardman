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

private const val DEFAULT_KERNEL_PROJECT = ":kernel"
private const val DEFAULT_KERNEL_ROOT_PACKAGE = "com.example.kernel"
private const val DEFAULT_PLUGIN_PROJECT = ":plugin"
private const val RDMA_RUNTIME_VERSION = "1.0"
private const val HERMES_VERSION = "0.76.9"

/**
 * Applied to the app module. It discovers the kernel modules (subprojects of
 * `rdmaKernelProject`) and plugin modules (subprojects of `rdmaPluginProject`), then:
 *   - adds every kernel module as a dependency,
 *   - copies the generated `@RDMA` C++ from all kernel modules and compiles it, together
 *     with a generated aggregate bridge, into a single `librdma_user.so`,
 *   - wires the compiled plugin JS from every plugin module into the app's assets.
 *
 * Configuration (via `gradle.properties`, single source of truth):
 *   rdmaKernelRootPackage  root package of kernel modules (default `com.example.kernel`)
 *   rdmaKernelProject      container path of kernel modules (default `:kernel`)
 *   rdmaKernelInternals    comma-separated project paths of framework-owned kernel modules
 *   rdmaPluginRootPackage  root package of plugin modules (default `com.example.plugin`)
 *   rdmaPluginProject      container path of plugin modules (default `:plugin`)
 *   rdmaHermesc            path to the Hermes compiler (optional).
 */
class RdmaAppGradlePlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val kernelContainer = target.findProperty("rdmaKernelProject")?.toString()
            ?: target.rootProject.findProperty("rdmaKernelProject")?.toString()
            ?: DEFAULT_KERNEL_PROJECT
        val pluginContainer = target.findProperty("rdmaPluginProject")?.toString()
            ?: target.rootProject.findProperty("rdmaPluginProject")?.toString()
            ?: DEFAULT_PLUGIN_PROJECT
        val kernelRootPackage = target.findProperty("rdmaKernelRootPackage")?.toString()
            ?: target.rootProject.findProperty("rdmaKernelRootPackage")?.toString()
            ?: DEFAULT_KERNEL_ROOT_PACKAGE
        val hermesc = target.findProperty("rdmaHermesc")?.toString()
            ?: target.rootProject.findProperty("rdmaHermesc")?.toString()

        // Enumerate kernel/plugin modules by project path (stable at configuration time).
        // Gradle auto-creates the intermediate container projects (`:plugin:alice`, …),
        // so only count projects that actually have a build script.
        val subprojects = target.rootProject.subprojects
            .filter { it.buildFile.exists() }
            .map { it.path }
        val kernelModulePaths = subprojects.filter { it.startsWith("$kernelContainer:") }
        val pluginModulePaths = subprojects.filter { it.startsWith("$pluginContainer:") }

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
        val pluginDevSyncDirs = pluginProjects.map { File(it.buildDir, "compileSync/js/main/developmentExecutable/kotlin") }
        val pluginProdSyncDirs = pluginProjects.map { File(it.buildDir, "compileSync/js/main/productionExecutable/kotlin") }

        // 1) Generate the user bridge + CMake (no kernel dependency).
        val generateBridge = target.tasks.register("generateRdmaBridge") { task ->
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

        // 3) Wire the compiled plugin JS of every plugin module into the app's assets.
        if (pluginProjects.isNotEmpty()) {
            if (!hermesc.isNullOrBlank()) {
                val hc = if (File(hermesc).isAbsolute) hermesc
                else File(target.rootProject.projectDir, hermesc).absolutePath
                val aotCompileJs = target.tasks.register("aotCompileJs") { task ->
                    pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsProductionExecutableCompileSync") }
                    task.doLast {
                        assetsKotlin.mkdirs()
                        assetsKotlin.listFiles { f -> f.extension in listOf("js", "map", "hbc") }?.forEach { it.delete() }
                        // Multiple plugin modules each produce a tree-shaken copy of the
                        // shared dependencies. A plugin that doesn't use a dependency (e.g.
                        // compose) yields a near-empty stub that must not overwrite the
                        // fuller copy another plugin needs. Pick the largest (most complete)
                        // copy per file name.
                        val jsByName = mutableMapOf<String, File>()
                        for (srcDir in pluginProdSyncDirs) {
                            srcDir.listFiles { f -> f.extension == "js" }?.forEach { js ->
                                val prev = jsByName[js.name]
                                if (prev == null || js.length() > prev.length()) {
                                    jsByName[js.name] = js
                                }
                            }
                        }
                        for ((name, js) in jsByName) {
                            val hbc = File(assetsKotlin, name.removeSuffix(".js") + ".hbc")
                            val cmd = listOf(hc, "-O", "-emit-binary", "-out", hbc.absolutePath, js.absolutePath)
                            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                            val output = proc.inputStream.bufferedReader().readText()
                            if (proc.waitFor() != 0) {
                                throw org.gradle.api.GradleException("hermesc failed for $name:\n$output")
                            }
                        }
                    }
                }
                target.tasks.named("preBuild") { it.dependsOn(aotCompileJs) }
                target.tasks.configureEach(Action<Task> { task ->
                    if (task.name == "mergeDebugAssets" || task.name == "mergeReleaseAssets") {
                        pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsProductionExecutableCompileSync") }
                    }
                })
            } else {
                val copyPluginJs = target.tasks.register("copyPluginJs") { task ->
                    pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsBrowserDevelopmentExecutableDistribution") }
                    task.doLast {
                        assetsKotlin.mkdirs()
                        assetsKotlin.listFiles { f -> f.extension in listOf("js", "map") }?.forEach { it.delete() }
                        for (srcDir in pluginDevSyncDirs) {
                            srcDir.listFiles { f -> f.extension in listOf("js", "map") }?.forEach { f ->
                                f.copyTo(File(assetsKotlin, f.name), overwrite = true)
                            }
                        }
                    }
                }
                target.tasks.named("preBuild") { it.dependsOn(copyPluginJs) }
                target.tasks.configureEach(Action<Task> { task ->
                    if (task.name == "mergeDebugAssets") {
                        pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsBrowserDevelopmentExecutableDistribution") }
                    }
                    if (task.name == "mergeReleaseAssets") {
                        pluginProjects.forEach { pp -> task.dependsOn("${pp.path}:jsBrowserProductionExecutableDistribution") }
                    }
                })
            }
        }

        // 4) Android wiring: dependencies + native build + source dirs + task deps.
        target.pluginManager.withPlugin("com.android.application") {
            val android = target.extensions.getByType(ApplicationExtension::class.java)

            // Framework + kernel dependencies.
            kernelProjects.forEach { kp ->
                target.dependencies.add("implementation", target.dependencies.project(mapOf("path" to kp.path)))
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

        // Kernel-side vtable dispatch: one JNI export per kernel module package.
        val dispatchBody = """
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
"""
        for (modulePackage in kernelModulePackages.distinct()) {
            val mp = modulePackage.replace('.', '_')
            sb.append(
                """// Kernel module package: $modulePackage
extern "C" JNIEXPORT jobject JNICALL
Java_${mp}_RdmaVtableKt_rdmaVtableDispatch(JNIEnv* env, jclass, jlong vtablePtr, jint vtableId) {
$dispatchBody}
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
