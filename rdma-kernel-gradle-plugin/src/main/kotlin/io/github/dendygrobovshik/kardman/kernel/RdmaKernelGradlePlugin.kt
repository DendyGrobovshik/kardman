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

private const val RDMA_VTABLE_SOURCE = """package com.example.kernel

external fun rdmaVtableDispatch(vtablePtr: Long, vtableId: Int): Any?
"""

class RdmaKernelGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // The compiler plugin runs during compilation and can't add sources to the same pass.
        // `rdmaVtableDispatch` is a static external declaration, so we generate it up-front.
        val dir = File(target.buildDir, "generated/rdma/kotlin")
        dir.mkdirs()
        File(dir, "RdmaVtable.kt").writeText(RDMA_VTABLE_SOURCE)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm

    override fun getCompilerPluginId(): String = "rdma-kernel-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-kernel-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            listOf(
                SubpluginOption("cppOutputDir", "${project.buildDir}/generated/rdma/cpp"),
                SubpluginOption("jsonOutputDir", "${project.buildDir}/generated/rdma"),
                SubpluginOption("kotlinOutputDir", "${project.buildDir}/generated/rdma/widget-kotlin"),
            )
        }
    }
}
