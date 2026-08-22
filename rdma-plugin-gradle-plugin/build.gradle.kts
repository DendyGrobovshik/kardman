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
        create("rdmaPluginCompilerPlugin") {
            id = "io.github.dendygrobovshik.kardman.rdma-plugin-compiler"
            implementationClass = "io.github.dendygrobovshik.kardman.plugin.RdmaPluginGradlePlugin"
        }
    }
}
