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
            implementation("io.github.dendygrobovshik.kardman:rdma-runtime-ios:1.0")
            implementation(project(":kernel:internal"))
            implementation(project(":shared-ui"))
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
        val nativeSdkRoot = File(buildDirFile, "rdma-native-sdk")
        val hermesFramework = File(nativeSdkRoot, "hermes.xcframework/$archDir/hermesvm.framework")
        val generatedCapiCpp = File(buildDirFile, "generated/rdma/capi-cpp")
        val userCppBuild = File(buildDirFile, "cpp/$targetName")
        val coreLibDir = File(nativeSdkRoot, "lib/$targetName")

        val includeDirs = listOf(
            generatedCapiCpp,
            File(nativeSdkRoot, "cpp/common"),
            File(nativeSdkRoot, "cpp/include"),
            File(nativeSdkRoot, "cpp/ios"),
            File(nativeSdkRoot, "cpp/ios/generated"),
            File(hermesFramework, "Headers"),
        )

        val buildCpp = tasks.register("buildRdmaUserCAbi_$targetName", Exec::class.java) {
            dependsOn("copyGeneratedCppCAbi", "extractNativeSdk")
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
                "-L${coreLibDir.absolutePath}",
                "-lrdma_core",
                "-F${hermesFramework.parentFile.absolutePath}",
                "-framework", "hermesvm",
            )
            linkTaskProvider.configure {
                dependsOn(buildCpp)
                dependsOn("extractNativeSdk")
                inputs.file(File(userCppBuild, "librdma_user.a"))
                inputs.file(File(coreLibDir, "librdma_core.a"))
            }
        }

        compilations.getByName("main").cinterops.create("userbridge") {
            defFile(project.file("src/nativeInterop/cinterop/userbridge.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }
}

// Resolve the published iOS native SDK (C++ core + Hermes) from mavenLocal and
// extract it into `build/rdma-native-sdk` for the bridge compile + final link.
val nativeSdk = configurations.create("rdmaNativeSdk") {
    isTransitive = false
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    add("rdmaNativeSdk", "io.github.dendygrobovshik.kardman:rdma-runtime-ios-native:1.0")
}

val extractNativeSdk by tasks.registering(Exec::class) {
    group = "build"
    description = "Extracts the iOS native SDK (C++ core + Hermes) into build/rdma-native-sdk."
    val out = layout.buildDirectory.dir("rdma-native-sdk").get().asFile
    outputs.dir(out)
    val zipFile = nativeSdk.singleFile
    doFirst { out.mkdirs() }
    commandLine(
        "/bin/bash", "-c",
        "set -e; rm -rf \"${out.absolutePath}\"; mkdir -p \"${out.absolutePath}\"; ditto -x -k \"${zipFile.absolutePath}\" \"${out.absolutePath}\"",
    )
}
