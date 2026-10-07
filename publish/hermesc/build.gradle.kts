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
import org.gradle.api.publish.maven.MavenPublication

// Publishes the host `hermesc` compiler as a maven artifact so consumer
// projects (e.g. wb2) can resolve it instead of vendoring the binary.
//
// Run after `scripts/setup-hermes.sh` has built the compiler:
//   ./gradlew -p publish/hermesc publishToMavenLocal \
//       -PhermescPath=tools/hermesc -PhermescClassifier=macos-aarch64
//
// The artifact is `io.github.dendygrobovshik.kardman:hermesc:1.0:<classifier>`
// (a zip containing the `hermesc` binary), e.g. `...:hermesc:1.0:macos-aarch64`.

plugins {
    `maven-publish`
}

group = "io.github.dendygrobovshik.kardman"
version = "1.0"

val hermescClassifier = (project.findProperty("hermescClassifier") as? String)
    ?: error("hermescClassifier property is required (e.g. macos-aarch64)")
val hermescPath = (project.findProperty("hermescPath") as? String)
    ?: error("hermescPath property is required")

val hermescFile = file(hermescPath)
if (!hermescFile.isFile) {
    error("hermescPath does not point to a file: $hermescPath")
}

val hermescZip by tasks.registering(Zip::class) {
    from(hermescFile)
    archiveFileName.set("hermesc.zip")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
}

publishing {
    repositories {
        mavenLocal()
    }
    publications {
        create<MavenPublication>("hermesc") {
            artifactId = "hermesc"
            artifact(hermescZip) {
                classifier = hermescClassifier
            }
        }
    }
}
