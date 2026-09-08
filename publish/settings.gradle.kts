/*
 * Copyright 2026 DendyGrobovshik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Minimal build used by scripts/publish.sh to publish only the framework modules
// to mavenLocal. Keeping the app/kernel/plugin (user) modules out of this build
// avoids the chicken-and-egg where the demo app consumes the just-published
// `rdma-app` Gradle plugin.
//
// Run with `./gradlew -p publish ...`.
rootProject.name = "rdma-framework"

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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
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

fun includeModule(name: String) {
    include(":$name")
    project(":$name").projectDir = file("../$name")
}

includeModule("rdma-annotation")
includeModule("rdma-types")
includeModule("rdma-kernel-compiler-plugin")
includeModule("rdma-kernel-gradle-plugin")
includeModule("rdma-plugin-compiler-plugin")
includeModule("rdma-plugin-gradle-plugin")
includeModule("rdma-runtime-android")
includeModule("rdma-app-gradle-plugin")
