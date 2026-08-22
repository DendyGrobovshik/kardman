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
        create("rdmaKernelCompilerPlugin") {
            id = "io.github.dendygrobovshik.kardman.rdma-kernel-compiler"
            implementationClass = "io.github.dendygrobovshik.kardman.kernel.RdmaKernelGradlePlugin"
        }
    }
}
