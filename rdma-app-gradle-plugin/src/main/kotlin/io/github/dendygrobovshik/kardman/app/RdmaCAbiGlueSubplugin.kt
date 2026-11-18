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

import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies the kernel compiler plugin to the app's iOS (native) main compilations in
 * `capi-glue` mode: those compilations compile the C ABI glue the kernel modules generated,
 * and the plugin fills its widget entry bodies (see [RdmaAppGradlePlugin], step 2c).
 */
class RdmaCAbiGlueSubplugin : KotlinCompilerPluginSupportPlugin {
    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.native &&
            kotlinCompilation.compilationName == KotlinCompilation.MAIN_COMPILATION_NAME

    override fun getCompilerPluginId(): String = "rdma-kernel-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-kernel-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            val modules = project.extensions.extraProperties.get(GLUE_MODULES_PROPERTY)?.toString().orEmpty()
            listOf(
                SubpluginOption("backend", "capi-glue"),
                SubpluginOption("glueModules", modules),
            )
        }
    }

    companion object {
        const val GLUE_MODULES_PROPERTY = "rdmaCAbiGlueModules"
    }
}
