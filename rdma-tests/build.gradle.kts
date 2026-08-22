plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":rdma-kernel-compiler-plugin"))
    implementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}
