plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.dendygrobovshik.kardman.rdma-kernel-compiler") version "1.0"
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            kotlin.srcDir("build/generated/rdma/kotlin")
            dependencies {
                implementation(project(":rdma-annotation"))
            }
        }
    }
}

tasks.matching { it.name == "compileKotlinJvm" }.configureEach {
    outputs.upToDateWhen { false }
}
