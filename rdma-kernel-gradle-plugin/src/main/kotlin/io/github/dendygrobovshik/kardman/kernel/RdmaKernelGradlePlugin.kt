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

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

private const val DEFAULT_KERNEL_PROJECT = ":kernel"
private const val DEFAULT_KERNEL_ROOT_PACKAGE = "com.example.kernel"

/** The package of this kernel module, derived from its project path. */
private fun Project.kernelPackage(): String {
    val rootPackage = findProperty("rdmaKernelRootPackage")?.toString()
        ?: rootProject.findProperty("rdmaKernelRootPackage")?.toString()
        ?: DEFAULT_KERNEL_ROOT_PACKAGE
    val container = findProperty("rdmaKernelProject")?.toString()
        ?: rootProject.findProperty("rdmaKernelProject")?.toString()
        ?: DEFAULT_KERNEL_PROJECT
    val suffix = path.removePrefix(container).removePrefix(":").replace(':', '.')
    return if (suffix.isEmpty()) rootPackage else "$rootPackage.$suffix"
}

/** A sanitized, filesystem/C++-safe identifier for this kernel module. */
private fun Project.kernelModuleId(): String {
    val container = findProperty("rdmaKernelProject")?.toString()
        ?: rootProject.findProperty("rdmaKernelProject")?.toString()
        ?: DEFAULT_KERNEL_PROJECT
    val suffix = path.removePrefix(container).removePrefix(":").replace(':', '_').replace('.', '_')
    return if (suffix.isEmpty()) "default" else suffix
}

private fun vtableExpectSource(kernelPackage: String) = """package $kernelPackage

expect fun rdmaVtableDispatch(vtablePtr: Long, vtableId: Int): Any?
"""

private fun vtableAndroidActualSource(kernelPackage: String) = """package $kernelPackage

actual external fun rdmaVtableDispatch(vtablePtr: Long, vtableId: Int): Any?
"""

private fun vtableIosActualSource(kernelPackage: String) = """package $kernelPackage

import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost

actual fun rdmaVtableDispatch(vtablePtr: Long, vtableId: Int): Any? =
    RdmaComposeHost.rdmaVtableDispatch(vtablePtr, vtableId)
"""

class RdmaKernelGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // The compiler plugin runs during compilation and can't add sources to the same pass.
        // `rdmaVtableDispatch` is a static declaration, so we generate it up-front. On JVM it is
        // a JNI `external`; on native it wraps the C-ABI `rdma_vtableDispatch` handle.
        val pkg = target.kernelPackage()
        File(target.buildDir, "generated/rdma/kotlin").also {
            it.mkdirs()
            File(it, "RdmaVtable.kt").writeText(vtableExpectSource(pkg))
        }
        File(target.buildDir, "generated/rdma/androidMain").also {
            it.mkdirs()
            File(it, "RdmaVtable.kt").writeText(vtableAndroidActualSource(pkg))
        }
        File(target.buildDir, "generated/rdma/iosMain").also {
            it.mkdirs()
            File(it, "RdmaVtable.kt").writeText(vtableIosActualSource(pkg))
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm ||
            kotlinCompilation.platformType == KotlinPlatformType.androidJvm ||
            kotlinCompilation.platformType == KotlinPlatformType.native

    override fun getCompilerPluginId(): String = "rdma-kernel-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-kernel-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        val backend = when (kotlinCompilation.platformType) {
            KotlinPlatformType.native -> "capi"
            else -> "jni"
        }
        // Native (C ABI) and JVM (JNI) outputs must not share a directory: a module
        // with both an android and an ios target compiles each backend in parallel.
        val cppDir = if (backend == "capi")
            "${project.buildDir}/generated/rdma/capi/cpp"
        else
            "${project.buildDir}/generated/rdma/cpp"
        val kotlinDir = if (backend == "capi")
            "${project.buildDir}/generated/rdma/capi/kotlin"
        else
            "${project.buildDir}/generated/rdma/widget-kotlin"
        return project.provider {
            listOf(
                SubpluginOption("backend", backend),
                SubpluginOption("cppOutputDir", cppDir),
                SubpluginOption("jsonOutputDir", "${project.buildDir}/generated/rdma"),
                SubpluginOption("kotlinOutputDir", kotlinDir),
                SubpluginOption("kernelPackage", project.kernelPackage()),
                SubpluginOption("moduleId", project.kernelModuleId()),
            )
        }
    }
}
