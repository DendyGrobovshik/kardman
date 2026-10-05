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

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

val cppRoot = rootProject.layout.projectDirectory.dir("rdma-runtime/src/main/cpp")

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // The C-ABI shim crosses into the per-target cinterop (`io.github...ios.cinterop`),
        // which is not visible to the shared `iosMain` metadata compilation. So the sources
        // live in a shared physical directory (`src/iosShared/kotlin`) that is wired into both
        // per-target source sets, leaving `iosMain` empty (metadata compiles trivially).
        val iosArm64Main by getting {
            kotlin.srcDir("src/iosShared/kotlin")
        }
        val iosSimulatorArm64Main by getting {
            kotlin.srcDir("src/iosShared/kotlin")
        }
        iosMain.dependencies {
            implementation(libs.compose.runtime)
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val archDir = if (targetName == "iosArm64") "ios-arm64" else "ios-arm64-simulator"
        val sdk = if (targetName == "iosArm64") "iphoneos" else "iphonesimulator"
        val hermesFramework = rootProject.layout.projectDirectory
            .dir(".hermes-ios/hermes.xcframework/$archDir/hermesvm.framework")
        val cppBuildDir = layout.buildDirectory.dir("cpp/$targetName")

        val srcs = listOf(
            "common/RdmaRuntime.cpp",
            "common/RdmaRendezvous.cpp",
            "ios/RdmaComposeCAbi.cpp",
            "ios/generated/RdmaComposerProxy.cpp",
        )
        val includeDirs = listOf(
            cppRoot.dir("common"),
            cppRoot.dir("include"),
            cppRoot.dir("ios"),
            cppRoot.dir("ios/generated"),
            hermesFramework.dir("Headers"),
        )

        val buildCpp = tasks.register("buildRdmaCpp_$targetName", Exec::class.java) {
            inputs.files(srcs.map { cppRoot.file(it) })
            outputs.file(cppBuildDir.map { it.file("librdma_core.a") })
            val objDir = cppBuildDir.get().asFile
            val script = buildString {
                appendLine("set -e")
                appendLine("OBJ=${objDir.absolutePath}")
                appendLine("SDK=\$(xcrun --sdk $sdk --show-sdk-path)")
                for (src in srcs) {
                    val obj = "\$OBJ/" + src.substringAfterLast('/').removeSuffix(".cpp") + ".o"
                    val incs = includeDirs.joinToString(" ") { "-I${it.asFile.absolutePath}" }
                    appendLine("xcrun --sdk $sdk clang++ -std=c++17 -O2 -fPIC $incs -isysroot \$SDK -c ${cppRoot.file(src).asFile.absolutePath} -o $obj")
                }
                appendLine("xcrun --sdk $sdk ar rcs ${cppBuildDir.get().asFile.absolutePath}/librdma_core.a \$OBJ/*.o")
            }
            doFirst { objDir.mkdirs() }
            commandLine("/bin/bash", "-c", script)
        }

        binaries.framework {
            baseName = "rdmaRuntime"
            isStatic = true
            linkerOpts(
                "-L${cppBuildDir.get().asFile.absolutePath}",
                "-lrdma_core",
                "-F${hermesFramework.asFile.parentFile.absolutePath}",
                "-framework", "hermesvm",
            )
            linkTaskProvider.configure { dependsOn(buildCpp) }
        }

        compilations.getByName("main").cinterops.create("rdma") {
            defFile(project.file("src/nativeInterop/cinterop/rdma.def"))
            includeDirs(cppRoot.dir("ios").asFile)
        }
    }
}

// ---------------------------------------------------------------------------
// Native SDK artifact: bundles the C++ core (headers + per-arch librdma_core.a)
// and the Hermes xcframework so a consumer repo (e.g. wb2) can build the iOS
// per-app bridge and link the final framework without vendoring sources.
// ---------------------------------------------------------------------------
val nativeSdkBuildDir = layout.buildDirectory.dir("native-sdk")
val hermesXcframework = rootProject.layout.projectDirectory.dir(".hermes-ios/hermes.xcframework")

val packageNativeSdk by tasks.registering(Exec::class) {
    group = "publishing"
    description = "Packages iOS C++ core + Hermes framework for mavenLocal distribution."
    dependsOn("buildRdmaCpp_iosArm64", "buildRdmaCpp_iosSimulatorArm64")

    val out = nativeSdkBuildDir.get().asFile
    val zipFile = File(out, "rdma-runtime-ios-native-${project.version}.zip")
    outputs.file(zipFile)

    val arm64Lib = layout.buildDirectory.dir("cpp/iosArm64").get().asFile
    val simLib = layout.buildDirectory.dir("cpp/iosSimulatorArm64").get().asFile

    val script = buildString {
        appendLine("set -e")
        appendLine("OUT=${out.absolutePath}")
        appendLine("STAGE=\$OUT/stage")
        appendLine("rm -rf \"\$STAGE\" && mkdir -p \"\$STAGE/cpp\" \"\$STAGE/lib/iosArm64\" \"\$STAGE/lib/iosSimulatorArm64\"")
        appendLine("cp -R ${cppRoot.dir("common").asFile.absolutePath} \"\$STAGE/cpp/common\"")
        appendLine("cp -R ${cppRoot.dir("include").asFile.absolutePath} \"\$STAGE/cpp/include\"")
        appendLine("cp -R ${cppRoot.dir("ios").asFile.absolutePath} \"\$STAGE/cpp/ios\"")
        appendLine("cp -R ${cppRoot.dir("ios/generated").asFile.absolutePath} \"\$STAGE/cpp/ios/generated\"")
        appendLine("cp ${arm64Lib.absolutePath}/librdma_core.a \"\$STAGE/lib/iosArm64/\"")
        appendLine("cp ${simLib.absolutePath}/librdma_core.a \"\$STAGE/lib/iosSimulatorArm64/\"")
        appendLine("cp -R ${hermesXcframework.asFile.absolutePath} \"\$STAGE/hermes.xcframework\"")
        appendLine("cd \"\$STAGE\" && zip -r -q -y ${zipFile.absolutePath} .")
    }
    doFirst { out.mkdirs() }
    commandLine("/bin/bash", "-c", script)
}

publishing {
    publications {
        create<MavenPublication>("nativeSdk") {
            groupId = "io.github.dendygrobovshik.kardman"
            artifactId = "rdma-runtime-ios-native"
            version = "1.0"
            artifact(packageNativeSdk)
        }
    }
}
