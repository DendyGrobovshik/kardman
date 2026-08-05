plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rdma-runtime-android"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "org.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
            pickFirsts.add("**/libhermesvm.so")
            pickFirsts.add("**/libc++_shared.so")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

afterEvaluate {
    tasks.named("mergeDebugAssets") {
        dependsOn(":plugin:jsBrowserDevelopmentExecutableDistribution")
    }
    tasks.named("mergeReleaseAssets") {
        dependsOn(":plugin:jsBrowserProductionExecutableDistribution")
    }
}

val copyPluginJs = tasks.register<Copy>("copyPluginJs") {
    dependsOn(":plugin:jsBrowserDevelopmentExecutableDistribution")
    from("${rootProject.projectDir}/plugin/build/compileSync/js/main/developmentExecutable/kotlin")
    into("${projectDir}/src/main/assets/kotlin")
}

tasks.named("preBuild") {
    dependsOn(copyPluginJs)
}
