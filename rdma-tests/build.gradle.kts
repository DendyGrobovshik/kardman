plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(project(":rdma-kernel-ksp"))
    implementation(project(":rdma-plugin-ksp"))
    implementation(libs.ksp.symbol.processing.api)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}
