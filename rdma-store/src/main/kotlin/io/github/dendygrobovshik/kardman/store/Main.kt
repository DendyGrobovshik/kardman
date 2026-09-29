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
package io.github.dendygrobovshik.kardman.store

import java.io.File

/**
 * Boots the store HTTP server. State (changelog + releases + bundles) is loaded by CI before
 * serving: a `VERSIONS_DIR` (default `versions`) directory with `changelog.json`, the `plugins/`
 * directory and the `bundles/` directory is loaded into the store on startup.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val versionsDir = System.getenv("VERSIONS_DIR")?.let(::File) ?: File("versions")

    val store = RdmaStore()
    if (versionsDir.isDirectory) {
        store.versionsDir = versionsDir
        store.reload()
    }

    val server = StoreHttpServer(store, port)
    server.start()
    println("rdma-store listening on :$port (versionsDir=${versionsDir.path})")
}
