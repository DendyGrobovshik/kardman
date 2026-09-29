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
@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"
}

kotlin {
    android {
        namespace = "com.example.plugin.bob"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    js {
        browser()
        binaries.executable()
        compilerOptions {
            freeCompilerArgs.add("-Xir-minimized-member-names=false")
        }
    }

    sourceSets {
        androidMain {
            kotlin.setSrcDirs(listOf("src/kotlin"))
            dependencies {
                implementation(project(":kernel:internal"))
                implementation(libs.compose.runtime)
            }
        }
        jsMain {
            kotlin.setSrcDirs(listOf("build/generated/rdma"))
            dependencies {
                implementation(libs.compose.runtime)
            }
        }
    }
}

tasks.matching { it.name == "compileAndroidMain" }.configureEach {
    dependsOn(":kernel:internal:compileAndroidMain")
}

tasks.matching { it.name == "compileKotlinJs" }.configureEach {
    dependsOn("compileAndroidMain")
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}
