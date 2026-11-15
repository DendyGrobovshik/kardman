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
import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    id("io.github.dendygrobovshik.kardman.rdma-app") version "1.0"
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)

    implementation(project(":shared-ui"))

    androidTestImplementation(libs.androidx.testExt.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

abstract class CompileBenchHbcTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jsDir: DirectoryProperty

    @get:InputFile
    abstract val hermesc: RegularFileProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun compile() {
        val dir = jsDir.get().asFile
        val js = dir.listFiles { file -> file.extension == "js" && file.name.contains("bench") }?.singleOrNull()
            ?: error("bench plugin js not found in $dir")
        check("Date.now()" in js.readText()) { "guest clock missing from ${js.name}" }
        val hbc = outDir.get().asFile.resolve("bench.hbc")
        hbc.parentFile.mkdirs()
        val proc = ProcessBuilder(
            hermesc.get().asFile.absolutePath,
            "-O",
            "-emit-binary",
            "-out",
            hbc.absolutePath,
            js.absolutePath,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "hermesc failed:\n$output" }
    }
}

abstract class GuestComposeBenchTask : DefaultTask() {
    @get:Input
    abstract val adbPath: Property<String>

    @TaskAction
    fun report() {
        val proc = ProcessBuilder(
            adbPath.get(),
            "logcat",
            "-d",
            "-s",
            "GuestBench:I",
        ).redirectErrorStream(true).start()
        val text = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "logcat failed:\n$text" }
        val lines = text.lineSequence()
            .filter { "GuestBench:" in it }
            .map { it.substringAfter("GuestBench:").trim() }
            .toList()
        check(lines.any { it.startsWith("modifiers composition ") }) { "guest bench report missing:\n$text" }
        check(lines.any { it.startsWith("modifiers recomposition ") }) { "guest bench report missing:\n$text" }
        check(lines.any { it.startsWith("nomodifiers composition ") }) { "guest bench report missing:\n$text" }
        check(lines.any { it.startsWith("nomodifiers recomposition ") }) { "guest bench report missing:\n$text" }
        println(lines.takeLast(5).joinToString("\n"))
    }
}

val benchAssetsDir = layout.buildDirectory.dir("generated/benchAssets")

tasks.register<CompileBenchHbcTask>("compileBenchHbc") {
    group = "rdma"
    description = "AOT-compile the guest modifier benchmark to bench.hbc."
    dependsOn(":plugin:bench:jsProductionExecutableCompileSync", "resolveRdmaHermesc")
    jsDir.set(rootProject.layout.projectDirectory.dir("plugin/bench/build/compileSync/js/main/productionExecutable/kotlin"))
    hermesc.set(layout.buildDirectory.file("rdma/hermesc/hermesc"))
    outDir.set(benchAssetsDir)
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn("compileBenchHbc")
}

abstract class ClearLogcatTask : DefaultTask() {
    @get:Input
    abstract val adbPath: Property<String>

    @TaskAction
    fun clear() {
        val proc = ProcessBuilder(adbPath.get(), "logcat", "-c").start()
        check(proc.waitFor() == 0) { "logcat -c failed" }
    }
}

val clearGuestBenchLog = tasks.register<ClearLogcatTask>("clearGuestBenchLog") {
    adbPath.set(adbBinary().absolutePath)
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(clearGuestBenchLog)
}

tasks.register<GuestComposeBenchTask>("guestComposeBench") {
    group = "verification"
    description = "Run the modifier grid and the same grid with no cell modifiers as guest code."
    dependsOn("connectedDebugAndroidTest")
    adbPath.set(adbBinary().absolutePath)
}

fun adbBinary(): File {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (fromEnv != null) {
        val candidate = File(fromEnv, "platform-tools/adb")
        if (candidate.isFile) return candidate
    }
    val props = Properties()
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        local.inputStream().use { props.load(it) }
        val sdk = props.getProperty("sdk.dir")
        if (sdk != null) {
            val candidate = File(sdk, "platform-tools/adb")
            if (candidate.isFile) return candidate
        }
    }
    error("adb not found. Set ANDROID_HOME or sdk.dir in demo/local.properties.")
}

android {
    namespace = "org.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("androidTest").assets.directories.add(
        layout.buildDirectory.dir("generated/benchAssets").get().asFile.absolutePath,
    )
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}
