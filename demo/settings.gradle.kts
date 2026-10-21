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
// Standalone demo build. Consumes the published framework from mavenLocal (run
// `scripts/publish.sh` first) and keeps only the user-side modules: the demo's own
// kernel (`:kernel:*`) and plugin (`:plugin:*`) modules, the app host (`:androidApp`,
// `:iosApp`).
//
// Run with `./gradlew -p demo ...` (or `scripts/demo.sh`).
rootProject.name = "demo"

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

include(":kernel:internal")
include(":plugin:generated")
include(":shared-ui")
include(":server")
include(":androidApp")
include(":iosApp")

// The shared-runtime module aggregates the common JS dependencies (Kotlin stdlib,
// Compose runtime, coroutines, …) into one full-DCE compilation. Its modules are the
// load-time dependencies of every store-delivered plugin, so they are baked into the
// app assets even though no plugin is baked in.
include(":shared-runtime")
project(":shared-runtime").projectDir = file("../shared-runtime")
