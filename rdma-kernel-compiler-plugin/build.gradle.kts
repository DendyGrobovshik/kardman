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

// The Kotlin/Native compiler runs the plugin in its own process and does not
// resolve the plugin's transitive Maven dependencies, so `rdma-types` and
// kotlinx.serialization must be embedded into the plugin jar. kotlin-stdlib is
// excluded (the compiler provides it).
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .filterNot { it.name.startsWith("kotlin-stdlib") }
            .map { zipTree(it) }
    })
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
        "--rdmaConfig",
        rdmaPolicyFile(),
    )
}

releaseTask("staticRelease", "static-release")
releaseTask("dynamicRelease", "dynamic-release")
releaseTask("hotfixRelease", "hotfix-release")

// The release CLI reads the support policy (floors) from this file. It is a *separate* property
// from the framework config (`rdmaConfigFile`), so a test that points the policy at a scratch file
// does not leak that path into the gradle plugins' framework-config reads.
fun rdmaPolicyFile(): String {
    val configured = project.findProperty("rdmaPolicyFile")?.toString()
    val file = if (configured != null) {
        val f = File(configured)
        if (f.isAbsolute) f else File(rootProject.projectDir, configured)
    } else {
        File(rootProject.projectDir, "rdma.toml")
    }
    return file.absolutePath
}

tasks.register<JavaExec>("pluginRelease") {
    group = "rdma"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dendygrobovshik.kardman.kernel.RdmaReleaseCli")
    val argList = mutableListOf("plugin-release")
    if (project.findProperty("noBump")?.toString() == "true") argList += "--no-bump"
    argList += project.findProperty("versionsDir")?.toString()
        ?: error("pass -PversionsDir=/path/to/versions")
    argList += project.findProperty("pluginJson")?.toString()
        ?: error("pass -PpluginJson=/path/to/plugin.json")
    // -PgenerationBundles="g1=/path/b1.hbc,g2=/path/b2.hbc" → "<g> <bundlePath>" pairs
    (project.findProperty("generationBundles")?.toString() ?: "")
        .split(',')
        .filter { it.isNotBlank() }
        .forEach { pair ->
            val idx = pair.indexOf('=')
            require(idx > 0) { "generationBundles must be <generation>=<bundlePath> pairs, got '$pair'" }
            argList += pair.substring(0, idx)
            argList += pair.substring(idx + 1)
        }
    argList += "--rdmaConfig"
    argList += rdmaPolicyFile()
    args(argList)
}

tasks.register<JavaExec>("generationRelease") {
    group = "rdma"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dendygrobovshik.kardman.kernel.RdmaReleaseCli")
    args(
        "generation-release",
        project.findProperty("generation")?.toString()
            ?: error("pass -Pgeneration=<g>"),
        project.findProperty("versionsDir")?.toString()
            ?: error("pass -PversionsDir=/path/to/versions"),
        project.findProperty("alias")?.toString() ?: "",
        "--rdmaConfig",
        rdmaPolicyFile(),
    )
}

tasks.register<JavaExec>("retireGeneration") {
    group = "rdma"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.dendygrobovshik.kardman.kernel.RdmaReleaseCli")
    args(
        "retire-generation",
        project.findProperty("generation")?.toString()
            ?: error("pass -Pgeneration=<g>"),
        project.findProperty("versionsDir")?.toString()
            ?: error("pass -PversionsDir=/path/to/versions"),
        "--rdmaConfig",
        rdmaPolicyFile(),
    )
}
