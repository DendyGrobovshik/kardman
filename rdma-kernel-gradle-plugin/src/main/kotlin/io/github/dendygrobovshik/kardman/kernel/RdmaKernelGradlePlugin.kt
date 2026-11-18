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

import io.github.dendygrobovshik.kardman.types.RdmaConfig
import io.github.dendygrobovshik.kardman.types.RdmaToml
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

/** Loads the project's `rdma.toml` (overridable via `-PrdmaConfigFile`). */
private fun Project.rdmaConfig(): RdmaConfig {
    val file = findProperty("rdmaConfigFile")?.toString()?.let {
        val f = File(it); if (f.isAbsolute) f else File(rootProject.projectDir, it)
    } ?: File(rootProject.projectDir, "rdma.toml")
    return RdmaToml.load(file)
}

/** The package of this kernel module, derived from its project path. */
private fun Project.kernelPackage(): String {
    val rootPackage = rdmaConfig().kernel.rootPackage.ifEmpty { DEFAULT_KERNEL_ROOT_PACKAGE }
    val container = rdmaConfig().kernel.project.ifEmpty { DEFAULT_KERNEL_PROJECT }
    val suffix = path.removePrefix(container).removePrefix(":").replace(':', '.')
    return if (suffix.isEmpty()) rootPackage else "$rootPackage.$suffix"
}

/** A sanitized, filesystem/C++-safe identifier for this kernel module. */
private fun Project.kernelModuleId(): String {
    val container = rdmaConfig().kernel.project.ifEmpty { DEFAULT_KERNEL_PROJECT }
    val suffix = path.removePrefix(container).removePrefix(":").replace(':', '_').replace('.', '_')
    return if (suffix.isEmpty()) "default" else suffix
}

private fun vtableExpectSource(kernelPackage: String) = """package $kernelPackage

// Calls a plugin override of an `open` method (the vtable): arguments and the result are
// marshalled by the method's type descriptors; returns `fallthrough` when the method is not
// overridden; a JS exception is thrown as `RdmaException` (runtime_rework §6.4, §2.8).
expect fun rdmaVtableDispatch(
    vtablePtr: Long,
    vtableId: Int,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any?
"""

private fun vtableAndroidActualSource(kernelPackage: String) = """package $kernelPackage

actual external fun rdmaVtableDispatch(
    vtablePtr: Long,
    vtableId: Int,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any?
"""

private fun vtableIosActualSource(kernelPackage: String) = """package $kernelPackage

import io.github.dendygrobovshik.kardman.runtime.RdmaComposeHost

actual fun rdmaVtableDispatch(
    vtablePtr: Long,
    vtableId: Int,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any? = RdmaComposeHost.rdmaVtableDispatch(vtablePtr, vtableId, args, paramDescs, resultDesc, fallthrough)
"""

// Hotfix dispatch slots (docs/design/runtime_rework.md §6). The kernel compiler plugin
// gives every slotted top-level @RDMA function a static `RdmaSlotCell` and a prologue
// calling `rdmaSlotInvoke`; the runtime keeps the cells by FQN and fills `blockId` when
// a hotfix bundle calls `RDMA.installSlot`. Generated per module package (like the
// vtable dispatch), so a kernel module needs no runtime dependency on Android.
private fun slotsCommonSource(kernelPackage: String) = """package $kernelPackage

/** Hotfix dispatch slot of one @RDMA callable (generated; runtime_rework §6). */
internal class RdmaSlotCell(
    fqn: String,
    @kotlin.jvm.JvmField val paramDescs: String,
    @kotlin.jvm.JvmField val resultDesc: String,
) {
    /** Block id of the JS implementation; 0 while the callable is not hotfixed. */
    @kotlin.concurrent.Volatile
    @kotlin.jvm.JvmField
    var blockId: Long = 0L

    init {
        rdmaSlotRegister(this, fqn)
    }
}

/** Returned by the runtime when the calling thread cannot run the hotfix. */
internal object RdmaSlotFallthrough

internal fun rdmaSlotInvoke(cell: RdmaSlotCell, args: Array<Any?>): Any? =
    rdmaSlotInvokeNative(cell.blockId, args, cell.paramDescs, cell.resultDesc, RdmaSlotFallthrough)

internal fun rdmaSlotHandled(result: Any?): Boolean = result !== RdmaSlotFallthrough

internal expect fun rdmaSlotRegister(cell: RdmaSlotCell, fqn: String)

internal expect fun rdmaSlotInvokeNative(
    blockId: Long,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any?
"""

private fun slotsAndroidActualSource(kernelPackage: String) = """package $kernelPackage

internal actual external fun rdmaSlotRegister(cell: RdmaSlotCell, fqn: String)

internal actual external fun rdmaSlotInvokeNative(
    blockId: Long,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any?
"""

private fun slotsIosActualSource(kernelPackage: String) = """package $kernelPackage

import io.github.dendygrobovshik.kardman.runtime.RdmaSlots

internal actual fun rdmaSlotRegister(cell: RdmaSlotCell, fqn: String) {
    RdmaSlots.register(fqn) { blockId -> cell.blockId = blockId }
}

internal actual fun rdmaSlotInvokeNative(
    blockId: Long,
    args: Array<Any?>,
    paramDescs: String,
    resultDesc: String,
    fallthrough: Any,
): Any? = RdmaSlots.invokeNative(blockId, args, paramDescs, resultDesc, fallthrough)
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
            File(it, "RdmaSlots.kt").writeText(slotsCommonSource(pkg))
        }
        File(target.buildDir, "generated/rdma/androidMain").also {
            it.mkdirs()
            File(it, "RdmaVtable.kt").writeText(vtableAndroidActualSource(pkg))
            File(it, "RdmaSlotsNative.kt").writeText(slotsAndroidActualSource(pkg))
        }
        File(target.buildDir, "generated/rdma/iosMain").also {
            it.mkdirs()
            File(it, "RdmaVtable.kt").writeText(vtableIosActualSource(pkg))
            File(it, "RdmaSlotsNative.kt").writeText(slotsIosActualSource(pkg))
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
