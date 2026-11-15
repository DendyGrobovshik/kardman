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

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidMultiplatformLibrary)
    id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"
}

kotlin {
    android {
        namespace = "com.example.plugin.bench"
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

val patchGuestClock = tasks.register<PatchGuestClockTask>("patchGuestClock") {
    dependsOn("compileAndroidMain")
    generatedDir.set(layout.buildDirectory.dir("generated/rdma"))
}

tasks.matching { it.name == "compileKotlinJs" || it.name == "jsProductionExecutableCompileSync" }.configureEach {
    dependsOn(patchGuestClock)
}

abstract class PatchGuestClockTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedDir: DirectoryProperty

    @TaskAction
    fun patch() {
        val files = generatedDir.get().asFile.listFiles { f -> f.isFile && f.name.endsWith("_rdma.kt") }
            ?: error("no rewritten plugin sources in ${generatedDir.get().asFile}")
        var clock = false
        var format = false
        for (file in files) {
            var text = file.readText()
            var changed = false
            if (CLOCK in text) {
                text = text.replace(CLOCK, CLOCK_JS)
                clock = true
                changed = true
            } else if ("Date.now()" in text) {
                clock = true
            }
            if (FORMAT in text) {
                text = text.replace(FORMAT, FORMAT_JS)
                format = true
                changed = true
            } else if ("Math.round" in text) {
                format = true
            }
            if (changed) file.writeText(text)
        }
        check(clock) { "guest clock marker missing from rewritten sources" }
        check(format) { "guest format marker missing from rewritten sources" }
    }

    private companion object {
        const val CLOCK = "private fun guestNowMs(): Double = 0.0 // GUEST_CLOCK"
        const val CLOCK_JS =
            "private fun guestNowMs(): Double = js(\"(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now()\") as Double"
        const val FORMAT = "private fun guestFormatMs(t0: Double): String = \"0.0\" // GUEST_FORMAT"
        const val FORMAT_JS =
            "private fun guestFormatMs(t0: Double): String = js(\"(function(){var elapsed=((typeof performance!=='undefined'&&performance.now)?performance.now():Date.now())-t0;var scaled=Math.round(elapsed*10);var whole=Math.trunc(scaled/10);var frac=Math.abs(scaled%10);return ''+whole+'.'+frac;})()\") as String"
    }
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}
