plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

dependencies {
    compileOnly(libs.kotlin.gradle.plugin.api)
}

gradlePlugin {
    plugins {
        create("rdmaCompilerPlugin") {
            id = "io.github.dendygrobovshik.kardman.rdma-compiler"
            implementationClass = "io.github.dendygrobovshik.kardman.ir.RdmaIrGradlePlugin"
        }
    }
}
