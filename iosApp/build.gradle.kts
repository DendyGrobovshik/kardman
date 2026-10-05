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
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("io.github.dendygrobovshik.kardman.rdma-app") version "1.0"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        iosMain.dependencies {
            implementation(project(":rdma-runtime-ios"))
            implementation(project(":kernel:internal"))
            implementation(project(":kernel:user:alice"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val archDir = if (targetName == "iosArm64") "ios-arm64" else "ios-arm64-simulator"
        val sdk = if (targetName == "iosArm64") "iphoneos" else "iphonesimulator"
        val buildDirFile = layout.buildDirectory.get().asFile
        val hermesFramework = rootProject.file(".hermes-ios/hermes.xcframework/$archDir/hermesvm.framework")
        val cppRoot = rootProject.file("rdma-runtime/src/main/cpp")
        val generatedCapiCpp = File(buildDirFile, "generated/rdma/capi-cpp")
        val userCppBuild = File(buildDirFile, "cpp/$targetName")
        val runtimeCppBuild = rootProject.file("rdma-runtime-ios/build/cpp/$targetName")

        val includeDirs = listOf(
            generatedCapiCpp,
            File(cppRoot, "common"),
            File(cppRoot, "include"),
            File(cppRoot, "ios"),
            File(cppRoot, "ios/generated"),
            File(hermesFramework, "Headers"),
        )

        val buildCpp = tasks.register("buildRdmaUserCAbi_$targetName", Exec::class.java) {
            dependsOn("copyGeneratedCppCAbi")
            inputs.dir(generatedCapiCpp)
            outputs.file(File(userCppBuild, "librdma_user.a"))
            val objDir = userCppBuild
            val srcGlob = generatedCapiCpp.absolutePath + "/*.cpp"
            val incArgs = includeDirs.joinToString(" ") { "-I" + it.absolutePath }
            val script = buildString {
                appendLine("set -e")
                appendLine("OBJ=${objDir.absolutePath}")
                appendLine("SDK=\$(xcrun --sdk $sdk --show-sdk-path)")
                appendLine("INC=\"$incArgs\"")
                appendLine("OBJS=\"\"")
                appendLine("for src in $srcGlob; do")
                appendLine("  obj=\$OBJ/\$(basename \"\$src\" .cpp).o")
                appendLine("  xcrun --sdk $sdk clang++ -std=c++17 -O2 -fPIC \$INC -isysroot \$SDK -c \"\$src\" -o \$obj")
                appendLine("  OBJS=\"\$OBJS \$obj\"")
                appendLine("done")
                appendLine("xcrun --sdk $sdk ar rcs ${userCppBuild.absolutePath}/librdma_user.a \$OBJS")
            }
            doFirst { objDir.mkdirs() }
            commandLine("/bin/bash", "-c", script)
        }

        binaries.framework {
            baseName = "iosApp"
            isStatic = false
            linkerOpts(
                "-L${userCppBuild.absolutePath}",
                "-lrdma_user",
                "-L${runtimeCppBuild.absolutePath}",
                "-lrdma_core",
                "-F${hermesFramework.parentFile.absolutePath}",
                "-framework", "hermesvm",
            )
            linkTaskProvider.configure {
                dependsOn(buildCpp)
                dependsOn(":rdma-runtime-ios:buildRdmaCpp_$targetName")
                inputs.file(File(userCppBuild, "librdma_user.a"))
                inputs.file(File(runtimeCppBuild, "librdma_core.a"))
            }
        }

        compilations.getByName("main").cinterops.create("userbridge") {
            defFile(project.file("src/nativeInterop/cinterop/userbridge.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }
}
