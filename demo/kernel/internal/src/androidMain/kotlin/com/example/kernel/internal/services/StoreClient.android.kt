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
package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val json = Json { ignoreUnknownKeys = true }
private val storeExecutor = Executors.newCachedThreadPool()

private var storeBaseUrl = "http://10.0.2.2:8080"
private var storeCacheDir: File? = null
private var storeHostVersion = 1

@Serializable
private data class GenerateResponse(val pluginId: String, val version: Int)

/**
 * Installs the demo store endpoint + bundle cache location + the host's native kernel
 * version (`h`). Called once by the host before any [generate]/[resync].
 */
internal actual fun installStoreImpl(baseUrl: String, cacheDir: String, hostVersion: Int) {
    storeBaseUrl = baseUrl
    storeCacheDir = File(cacheDir).apply { mkdirs() }
    storeHostVersion = hostVersion
}

internal actual fun generateExecute(code: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
    storeExecutor.submit {
        try {
            val body = "{\"code\":${json.encodeToString(code)}}"
            val text = http("POST", "$storeBaseUrl/generate", body)
            val resp = json.decodeFromString(GenerateResponse.serializer(), text)
            val prev = (resp.version - 1).coerceAtLeast(0)
            resyncExecute(resp.pluginId, prev, onDone, onError)
        } catch (e: Exception) {
            onError(e.message ?: "generate failed")
        }
    }
}

internal actual fun resyncExecute(pluginId: String, version: Int, onDone: (String) -> Unit, onError: (String) -> Unit) {
    storeExecutor.submit {
        try {
            val request = ResyncRequest(pluginId, version, storeHostVersion, storeHostVersion)
            val body = json.encodeToString(ResyncRequest.serializer(), request)
            val resp = json.decodeFromString(ResyncResponse.serializer(), http("POST", "$storeBaseUrl/resync", body))
            when (resp.status) {
                ResyncStatus.UPDATE -> {
                    resp.bundleHash?.let { hash -> downloadAndEval(hash) }
                    resp.polyfills.forEach { key -> downloadAndEvalBytes(key) }
                    onDone("updated $pluginId -> v${resp.targetVersion}")
                }
                ResyncStatus.CURRENT -> onDone("current $pluginId v${resp.targetVersion}")
                ResyncStatus.UNAVAILABLE -> onDone("unavailable $pluginId")
                ResyncStatus.REFUSED -> onDone("refused $pluginId")
            }
        } catch (e: Exception) {
            onError(e.message ?: "resync failed")
        }
    }
}

/** Downloads a plugin bundle, caches it to disk and mmap-evaluates it. */
private fun downloadAndEval(hash: String) {
    val dir = storeCacheDir ?: return
    val file = File(dir, "$hash.hbc")
    if (!file.isFile) {
        val bytes = download("$storeBaseUrl/bundle/$hash") ?: return
        file.writeBytes(bytes)
    }
    RdmaBridge.nativeEvalFile(file.absolutePath)
}

/** Downloads a polyfill bundle and evaluates it in memory. */
private fun downloadAndEvalBytes(key: String) {
    download("$storeBaseUrl/bundle/$key")?.let { RdmaBridge.nativeEvalBytes(it) }
}

private fun download(url: String): ByteArray? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 10_000
    connection.requestMethod = "GET"
    val code = connection.responseCode
    val bytes = if (code == 200) connection.inputStream.use { it.readBytes() } else null
    connection.disconnect()
    return bytes
}

private fun http(method: String, url: String, body: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 10_000
    connection.requestMethod = method
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val code = connection.responseCode
    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    connection.disconnect()
    if (code !in 200..299) throw IllegalStateException("HTTP $code: $text")
    return text
}
