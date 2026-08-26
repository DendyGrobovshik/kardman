plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

dependencies {
    implementation(libs.kotlinx.serialization.json)
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
