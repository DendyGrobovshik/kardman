plugins {
    alias(libs.plugins.androidLibrary)
}

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
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

// Clean old generated C++ files before copying new ones
val cleanGeneratedCpp = tasks.register<Delete>("cleanGeneratedCpp") {
    delete(fileTree("src/main/cpp/generated").matching {
        include("*.h", "*.cpp")
    })
}

val copyGeneratedCpp = tasks.register<Copy>("copyGeneratedCpp") {
    dependsOn(":kernel:kspKotlinJvm", cleanGeneratedCpp)
    from("${rootProject.projectDir}/kernel/build/generated/ksp/jvm/jvmMain/resources/cpp")
    into("src/main/cpp/generated")
    include("*.h", "*.cpp")
}

// Always re-run CMake when generated C++ files change
val invalidateCmake = tasks.register<Delete>("invalidateCmake") {
    dependsOn(copyGeneratedCpp)
    delete(".cxx")
}

tasks.whenTaskAdded {
    if (name.startsWith("configureCMake")) {
        dependsOn(invalidateCmake)
    }
}

dependencies {
    implementation(libs.hermes.android)
    implementation(libs.soloader)
    implementation(project(":rdma-annotation"))
}
