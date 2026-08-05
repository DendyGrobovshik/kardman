plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJs) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("cleanGenerated") {
    group = "build"
    description = "Clean all generated/cached outputs to force full rebuild"

    delete(
        "kernel/build/generated",
        "kernel/build/kspCaches",
        "plugin/build/generated",
        "plugin/build/kspCaches",
        "plugin/build/compileSync",
        "plugin/build/kotlin-webpack",
        "plugin/build/dist",
        "rdma-runtime-android/.cxx",
        "rdma-runtime-android/src/main/cpp/generated",
        "androidApp/src/main/assets/kotlin",
    )
}
