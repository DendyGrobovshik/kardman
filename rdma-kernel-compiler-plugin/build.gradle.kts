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
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    implementation(project(":rdma-types"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

fun releaseTask(name: String, command: String) = tasks.register<JavaExec>(name) {
    group = "rdma"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dendygrobovshik.kardman.kernel.RdmaReleaseCli")
    args(
        command,
        project.findProperty("analysisJson")?.toString()
            ?: error("pass -PanalysisJson=/path/to/rdma_analysis.json"),
        project.findProperty("versionsDir")?.toString()
            ?: error("pass -PversionsDir=/path/to/versions"),
        project.findProperty("polyfillOutDir")?.toString() ?: "",
        project.findProperty("internalModules")?.toString() ?: "internal",
        project.findProperty("telemetryFile")?.toString() ?: "",
    )
}

releaseTask("staticRelease", "static-release")
releaseTask("dynamicRelease", "dynamic-release")
releaseTask("hotfixRelease", "hotfix-release")

tasks.register<JavaExec>("pluginRelease") {
    group = "rdma"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dendygrobovshik.kardman.kernel.RdmaReleaseCli")
    args(
        "plugin-release",
        project.findProperty("pluginJson")?.toString()
            ?: error("pass -PpluginJson=/path/to/plugin.json"),
        project.findProperty("versionsDir")?.toString()
            ?: error("pass -PversionsDir=/path/to/versions"),
        project.findProperty("bundlePath")?.toString()
            ?: error("pass -PbundlePath=/path/to/plugin.hbc"),
    )
}
