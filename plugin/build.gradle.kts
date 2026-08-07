@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }

    sourceSets {
        jsMain {
            kotlin.setSrcDirs(listOf("build/generated/ksp/js/jsMain/kotlin"))
        }
    }
}

dependencies {
    add("kspJs", project(":rdma-plugin-ksp"))
}

ksp {
    arg("rdmaClassesJson", "${rootProject.projectDir}/kernel/build/generated/ksp/jvm/jvmMain/resources/cpp/rdma_classes.json")
}

tasks.matching { it.name == "kspKotlinJs" }.configureEach {
    dependsOn(":kernel:kspKotlinJvm")
}

tasks.matching { it.name.startsWith("compile") || it.name.startsWith("js") }.configureEach {
    outputs.upToDateWhen { false }
}
