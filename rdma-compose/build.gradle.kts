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
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

kotlin {
    jvm()
    js(IR) {
        browser()
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":rdma-annotation"))
            implementation(libs.compose.runtime)
        }
        // Host apply (androidx.compose.ui.Modifier). The JS guest only needs the model.
        jvmMain {
            kotlin.srcDir("src/hostMain/kotlin")
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
            }
        }
        iosMain {
            kotlin.srcDir("src/hostMain/kotlin")
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.ui)
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.junit)
            implementation("org.jetbrains.compose.ui:ui-test-junit4:${libs.versions.composeMultiplatform.get()}")
            // ui-test renders through Skiko. The native binary is a per-OS artifact the
            // Compose Gradle plugin would add; this module does not apply that plugin.
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            val skikoTarget = when {
                os.startsWith("Mac", ignoreCase = true) && arch == "aarch64" -> "macos-arm64"
                os.startsWith("Mac", ignoreCase = true) -> "macos-x64"
                os.startsWith("Windows", ignoreCase = true) && arch == "aarch64" -> "windows-arm64"
                os.startsWith("Windows", ignoreCase = true) -> "windows-x64"
                arch == "aarch64" -> "linux-arm64"
                else -> "linux-x64"
            }
            implementation("org.jetbrains.skiko:skiko-awt-runtime-$skikoTarget:0.144.6")
        }
    }
}
