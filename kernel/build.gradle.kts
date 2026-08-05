plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ksp)
}

kotlin {
    jvm()
    js(IR) {
        browser()
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":rdma-annotation"))
        }
    }
}

dependencies {
    add("kspJvm", project(":rdma-kernel-ksp"))
}

tasks.matching { it.name.startsWith("ksp") }.configureEach {
    outputs.upToDateWhen { false }
}