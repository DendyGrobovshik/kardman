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
package org.example.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.dendygrobovshik.kardman.kernel.RdmaContentHasher
import io.github.dendygrobovshik.kardman.kernel.RdmaPluginReleases
import io.github.dendygrobovshik.kardman.kernel.RdmaToolchain
import io.github.dendygrobovshik.kardman.store.RdmaStore
import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import io.github.dendygrobovshik.kardman.types.RdmaToml
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import io.github.dendygrobovshik.kardman.types.ToolchainPins
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

/**
 * The demo's development server: serves the re-sync store surface (`/health`, `/resync`,
 * `/bundle`, `/reload`) and adds `/generate`, which compiles submitted plugin source into a
 * Hermes bundle, releases it into `versions/` and reloads the store — all against the demo's
 * own `:plugin:generated` module.
 *
 * Env:
 *   PORT         HTTP port (default 8080)
 *   DEMO_DIR     demo/ directory (default: current working directory)
 *   VERSIONS_DIR versioning directory (default `$DEMO_DIR/versions`)
 *   HERMESC      path to hermesc (default `$ROOT/third_party/bin/hermesc`)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val demoDir = System.getenv("DEMO_DIR")?.let(::File) ?: File(".").absoluteFile
    val rootDir = demoDir.parentFile
    val versionsDir = System.getenv("VERSIONS_DIR")?.let(::File) ?: File(demoDir, "versions")
    val rdmaToml = File(demoDir, "rdma.toml")
    val hermesc = System.getenv("HERMESC") ?: File(rootDir, "third_party/bin/hermesc").absolutePath
    val generatedSrc = File(demoDir, "plugin/generated/src/kotlin/com/example/plugin/generated/Generated.kt")
    val pluginJson = File(demoDir, "plugin/generated/build/generated/rdma/plugin.json")

    val store = RdmaStore()
    store.versionsDir = versionsDir
    store.reload()

    ensureGeneration(versionsDir, rdmaToml)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/health") { respondText(it, 200, "ok") }
    server.createContext("/reload") {
        store.reload()
        respondText(it, 200, "ok")
    }
    server.createContext("/bundle") { exchange ->
        val key = exchange.requestURI.path.removePrefix("/bundle/")
        val bytes = if (key.isBlank()) null else store.bundle(key)
        if (bytes == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
    server.createContext("/resync") { exchange ->
        try {
            val request = json.decodeFromString(ResyncRequest.serializer(), exchange.requestBody.readBytes().decodeToString())
            val response = store.resync(request)
            respondJson(exchange, 200, json.encodeToString(ResyncResponse.serializer(), response))
        } catch (e: Exception) {
            respondJson(exchange, 400, "{\"error\":\"${e.message}\"}")
        }
    }
    server.createContext("/generate") { exchange ->
        try {
            val body = exchange.requestBody.readBytes().decodeToString()
            val code = json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content
                ?: error("missing 'code'")
            val result = generate(store, versionsDir, demoDir, rootDir, hermesc, generatedSrc, pluginJson, code)
            respondJson(exchange, 200, "{\"pluginId\":\"${result.pluginId}\",\"version\":${result.version}}")
        } catch (e: Exception) {
            respondJson(exchange, 400, "{\"error\":\"${e.message}\"}")
        }
    }
    server.start()
    println("demo dev server listening on :$port (versionsDir=${versionsDir.path})")
}

data class GenerateResponse(val pluginId: String, val version: Int)

private val json = Json { ignoreUnknownKeys = true }

/** Writes source, compiles it, releases the bundle, seeds the store and returns the new version. */
private fun generate(
    store: RdmaStore,
    versionsDir: File,
    demoDir: File,
    rootDir: File,
    hermesc: String,
    generatedSrc: File,
    pluginJson: File,
    code: String,
): GenerateResponse {
    generatedSrc.writeText(code)

    runProcess(
        rootDir,
        File(rootDir, "gradlew").absolutePath,
        "-p", demoDir.absolutePath,
        ":plugin:generated:jsProductionExecutableCompileSync",
        "--console=plain",
    )

    val js = findPluginJs(File(demoDir, "plugin/generated/build/compileSync"))
    val hbc = File.createTempFile("generated", ".hbc")
    try {
        runProcess(rootDir, hermesc, "-O", "-emit-binary", "-out", hbc.absolutePath, js.absolutePath)

        val contract = json.decodeFromString(RdmaPluginContract.serializer(), pluginJson.readText())
        val version = RdmaPluginReleases.nextVersion(RdmaPluginReleases.read(versionsDir, contract.pluginId))
        val hash = RdmaContentHasher.sha256Bytes(hbc.readBytes())
        RdmaPluginReleases.append(versionsDir, PluginRelease(contract.copy(version = version, builtAgainstToolchain = "g1"), hash))

        val bundlesDir = File(versionsDir, "bundles")
        bundlesDir.mkdirs()
        File(bundlesDir, hash).writeBytes(hbc.readBytes())
        store.putBundle(hash, hbc.readBytes())
        store.reload()

        println("released ${contract.pluginId} v$version ($hash)")
        return GenerateResponse(contract.pluginId, version)
    } finally {
        hbc.delete()
    }
}

/** Ensures a `g1` toolchain generation + FIFO floor exist so plugin releases have a target. */
private fun ensureGeneration(versionsDir: File, rdmaToml: File) {
    val toolchain = RdmaToolchain.read(versionsDir)
    if (toolchain.generations.isEmpty()) {
        RdmaToolchain.write(versionsDir, RdmaToolchain.append(toolchain, "g1", "", ToolchainPins()))
        val config = RdmaToml.load(rdmaToml)
        if (config.toolchain.oldestActiveGeneration.isEmpty()) {
            RdmaToml.writeOldestActiveGeneration(rdmaToml, "g1")
        }
        println("initialized toolchain generation g1")
    }
}

/** The plugin's own JS module: the only non-shared-dependency module under `compileSync`. */
private fun findPluginJs(compileSync: File): File {
    val sharedPrefixes = listOf("kotlin-", "androidx-", "compose-", "kotlinx-", "kotlin_")
    return compileSync.walkTopDown()
        .filter { it.isFile && it.extension == "js" }
        .firstOrNull { f -> sharedPrefixes.none { f.name.startsWith(it) } }
        ?: error("no plugin JS produced in $compileSync")
}

private fun runProcess(workDir: File?, vararg cmd: String) {
    val process = ProcessBuilder(*cmd).directory(workDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) {
        throw IllegalStateException("command failed: ${cmd.joinToString(" ")}\n$output")
    }
}

private fun respondJson(exchange: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun respondText(exchange: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray()
    exchange.sendResponseHeaders(code, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
