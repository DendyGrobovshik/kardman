@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.dendygrobovshik.kardman.rdma-compiler") version "1.0"
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
            }
        }
        jsMain {
            kotlin.setSrcDirs(listOf("build/generated/rdma"))
        }
    }
}

tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    dependsOn(":kernel:kspKotlinJvm")
}

tasks.matching { it.name == "compileKotlinJs" }.configureEach {
    dependsOn("compileKotlinJvm")
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}
