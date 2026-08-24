@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeCompiler)
    id("io.github.dendygrobovshik.kardman.rdma-plugin-compiler") version "1.0"
}

kotlin {
    jvm()

    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        jvmMain {
            kotlin.setSrcDirs(listOf("src/kotlin"))
            dependencies {
                implementation(project(":kernel"))
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

tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    dependsOn(":kernel:compileKotlinJvm")
}

tasks.matching { it.name == "compileKotlinJs" }.configureEach {
    dependsOn("compileKotlinJvm")
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}
