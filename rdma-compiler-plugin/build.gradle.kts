plugins {
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

kotlin {
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
        optIn.add("org.jetbrains.kotlin.fir.symbols.SymbolInternals")
        optIn.add("org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess")
    }
}

dependencies {
    compileOnly(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
