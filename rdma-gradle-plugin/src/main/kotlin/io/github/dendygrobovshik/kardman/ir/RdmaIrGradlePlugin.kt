package io.github.dendygrobovshik.kardman.ir

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

class RdmaIrGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // The compiler plugin is wired through KotlinCompilerPluginSupportPlugin callbacks.
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm

    override fun getCompilerPluginId(): String = "rdma-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            val jsonPath = (project.findProperty("rdmaClassesJson") as? String)
                ?: "${project.rootProject.projectDir}/kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/rdma_classes.json"
            val outputDir = "${project.buildDir}/generated/rdma"
            listOf(
                SubpluginOption("rdmaClassesJson", jsonPath),
                SubpluginOption("rdmaOutputDir", outputDir),
            )
        }
    }
}
