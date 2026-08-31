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
fun rdmaTitleText(text: String) {
    RDMA.compose("TitleText", arrayOf(text))
}

@Composable
fun rdmaPriceText(text: String) {
    RDMA.compose("PriceText", arrayOf(text))
}

@Composable
fun rdmaOldPriceText(text: String) {
    RDMA.compose("OldPriceText", arrayOf(text))
}

@Composable
fun rdmaCaptionText(text: String) {
    RDMA.compose("CaptionText", arrayOf(text))
}

@Composable
fun rdmaSectionTitle(text: String) {
    RDMA.compose("SectionTitle", arrayOf(text))
}

@Composable
fun rdmaColumn(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("Column", arrayOf(blockId))
}

@Composable
fun rdmaCard(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("Card", arrayOf(blockId))
}

@Composable
fun rdmaRow(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("Row", arrayOf(blockId))
}

@Composable
fun rdmaHorizontalScrollRow(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("HorizontalScrollRow", arrayOf(blockId))
}

@Composable
fun rdmaVerticalScrollColumn(content: @Composable () -> Unit) {
    val blockId = RDMA.registerBlock(content)
    RDMA.compose("VerticalScrollColumn", arrayOf(blockId))
}

@Composable
fun rdmaImage(url: String) {
    RDMA.compose("Image", arrayOf(url))
}

@Composable
fun rdmaSearchBar(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    val cbId = RDMA.registerBlock(onValueChange)
    val clearId = RDMA.registerBlock(onClear)
    RDMA.compose("SearchBar", arrayOf(value, cbId, clearId))
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
            val manifestPath = (project.findProperty("rdmaManifest") as? String)
                ?: "${project.rootProject.projectDir}/kernel/build/generated/rdma/rdma_manifest.json"
            val outputDir = "${project.buildDir}/generated/rdma"
            listOf(
                SubpluginOption("rdmaManifest", manifestPath),
                SubpluginOption("rdmaOutputDir", outputDir),
            )
        }
    }
}
