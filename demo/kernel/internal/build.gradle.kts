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
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("io.github.dendygrobovshik.kardman.rdma-kernel-compiler") version "1.0"
}

kotlin {
    android {
        namespace = "com.example.kernel.internal"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/rdma/kotlin")
            dependencies {
                implementation("io.github.dendygrobovshik.kardman:rdma-annotation:1.0")
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.coil.compose)
            }
        }
        androidMain {
            kotlin.srcDir("build/generated/rdma/androidMain")
            dependencies {
                implementation("io.github.dendygrobovshik.kardman:rdma-runtime:1.0")
                implementation("io.github.dendygrobovshik.kardman:rdma-types:1.0")
                implementation(libs.kotlinx.serialization.json)
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            }
        }
        iosMain {
            kotlin.srcDir("build/generated/rdma/iosMain")
            kotlin.srcDir("build/generated/rdma/capi/kotlin")
            dependencies {
                implementation("io.github.dendygrobovshik.kardman:rdma-runtime-ios:1.0")
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("KotlinAndroid") }.configureEach {
    outputs.upToDateWhen { false }
}
