plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":rdma-annotation"))
        }
        commonMain {
            kotlin.exclude("**/Person.kt", "**/Person2.kt")
        }
    }
}

dependencies {
    add("kspJvm", project(":rdma-kernel-ksp"))
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    outputs.upToDateWhen { false }
}
