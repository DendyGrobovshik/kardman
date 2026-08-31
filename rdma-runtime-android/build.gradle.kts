plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeCompiler)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

android {
    namespace = "io.github.dendygrobovshik.kardman.runtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildFeatures {
        prefab = true
        prefabPublishing = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    // Export the generic runtime as a prefab so the user's generated bridge
    // (librdma_user.so) can link against it and include its public headers.
    prefab {
        create("rdma_runtime") {
            headers = "src/main/cpp/include"
        }
    }
}

dependencies {
    implementation(libs.hermes.android)
    implementation(libs.soloader)
    implementation(libs.compose.runtime)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.github.dendygrobovshik.kardman"
                artifactId = "rdma-runtime-android"
                version = "1.0"
            }
        }
    }
}
