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
package org.example

import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal re-sync client for the store (§8.2). Pure JDK networking (no dependencies) so it can be
 * unit-tested on the JVM; the [MainActivity] wires it to `RdmaBridge.nativeEvalBytes`.
 */
class RdmaSyncClient(private val baseUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun resync(request: ResyncRequest): ResyncResponse {
        val body = json.encodeToString(ResyncRequest.serializer(), request)
        val conn = open("$baseUrl/resync", "POST", body)
        val (code, text) = conn.read()
        if (code !in 200..299) throw IOException("resync failed ($code): $text")
        return json.decodeFromString(ResyncResponse.serializer(), text)
    }

    /** Downloads a bundle/polyfill by key, or null when the store has no such bundle (404). */
    fun download(key: String): ByteArray? {
        val conn = open("$baseUrl/bundle/$key", "GET", null)
        val (code, bytes) = conn.readBytes()
        return if (code == 200) bytes else null
    }

    private fun open(url: String, method: String, body: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

    private fun HttpURLConnection.read(): Pair<Int, String> {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.readBytes()?.decodeToString().orEmpty()
        disconnect()
        return code to text
    }

    private fun HttpURLConnection.readBytes(): Pair<Int, ByteArray> {
        val code = responseCode
        val bytes = if (code in 200..299) inputStream.use { it.readBytes() } else ByteArray(0)
        disconnect()
        return code to bytes
    }
}
