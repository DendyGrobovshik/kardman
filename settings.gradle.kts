rootProject.name = "RDMAHermes"

pluginManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")
include(":kernel")
include(":rdma-annotation")
include(":rdma-kernel-compiler-plugin")
include(":rdma-kernel-gradle-plugin")
include(":rdma-runtime-android")
include(":plugin")
include(":rdma-plugin-compiler-plugin")
include(":rdma-plugin-gradle-plugin")
include(":rdma-tests")
