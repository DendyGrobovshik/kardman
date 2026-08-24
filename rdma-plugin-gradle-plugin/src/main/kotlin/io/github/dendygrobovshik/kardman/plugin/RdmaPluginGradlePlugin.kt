package io.github.dendygrobovshik.kardman.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption
import java.io.File

private const val RDMA_WIDGET_BRIDGE_SOURCE = """package com.example.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.MutableState
import kotlin.js.unsafeCast

external object RDMA {
    fun registerContent(content: dynamic)
    fun setComposerEmpty(empty: Any)
    fun mutableStateOf(value: dynamic): dynamic
    fun compose(name: String, args: dynamic): dynamic
    fun registerBlock(block: dynamic): dynamic
}

fun rdmaMutableStateOf(value: Int): MutableState<Int> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<Int>>()

fun rdmaMutableStateOf(value: Boolean): MutableState<Boolean> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<Boolean>>()

fun rdmaMutableStateOf(value: String): MutableState<String> =
    RDMA.mutableStateOf(value).unsafeCast<MutableState<String>>()

fun rdmaRunApp(content: @Composable () -> Unit) {
    RDMA.setComposerEmpty(Composer.Companion.Empty)
    RDMA.registerContent(content)
}

@Composable
fun rdmaText(text: String) {
    RDMA.compose("Text", arrayOf(text))
}

@Composable
fun rdmaColumn(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("Column", arrayOf(blockId))
}

@Composable
fun rdmaButton(text: String, onClick: () -> Unit) {
    val onClickId = RDMA.registerBlock(onClick)
    RDMA.compose("Button", arrayOf(text, onClickId))
}

@Composable
fun rdmaTextField(value: String, onValueChange: (String) -> Unit) {
    val cbId = RDMA.registerBlock(onValueChange)
    RDMA.compose("TextField", arrayOf(value, cbId))
}
"""

class RdmaPluginGradlePlugin : KotlinCompilerPluginSupportPlugin {

    override fun apply(target: Project) {
        // Static guest-side bridge (external RDMA + @Composable protocol stubs).
        // Written up-front so it is available to the JS compilation alongside the
        // FIR-rewritten `*_rdma.kt` sources.
        val dir = File(target.buildDir, "generated/rdma")
        dir.mkdirs()
        File(dir, "RdmaWidgetBridge.kt").writeText(RDMA_WIDGET_BRIDGE_SOURCE)
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.jvm

    override fun getCompilerPluginId(): String = "rdma-plugin-compiler-plugin"

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact("io.github.dendygrobovshik.kardman", "rdma-plugin-compiler-plugin", "1.0")

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        return project.provider {
            val jsonPath = (project.findProperty("rdmaClassesJson") as? String)
                ?: "${project.rootProject.projectDir}/kernel/build/generated/rdma/rdma_classes.json"
            val widgetsPath = (project.findProperty("rdmaWidgetsJson") as? String)
                ?: "${project.rootProject.projectDir}/kernel/build/generated/rdma/rdma_widgets.json"
            val outputDir = "${project.buildDir}/generated/rdma"
            listOf(
                SubpluginOption("rdmaClassesJson", jsonPath),
                SubpluginOption("rdmaWidgetsJson", widgetsPath),
                SubpluginOption("rdmaOutputDir", outputDir),
            )
        }
    }
}
