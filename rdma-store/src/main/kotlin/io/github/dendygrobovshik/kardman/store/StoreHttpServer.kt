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

import com.sun.net.httpserver.HttpServer
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress

/**
 * A minimal zero-dependency HTTP transport for the store, exposing the re-sync endpoint (§8.2):
 *
 *   POST /resync   body: [ResyncRequest] JSON  →  [ResyncResponse] JSON
 *   GET  /health   →  "ok"
 */
class StoreHttpServer(
    private val store: RdmaStore,
    private val port: Int = 8080,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var server: HttpServer? = null

    fun start() {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext("/health") { exchange ->
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/reload") { exchange ->
            store.reload()
            val body = "ok".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        s.createContext("/bundle") { exchange ->
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
        s.createContext("/resync") { exchange ->
            try {
                val request = json.decodeFromString(
                    ResyncRequest.serializer(),
                    exchange.requestBody.readBytes().decodeToString(),
                )
                val response = store.resync(request)
                respond(exchange, 200, json.encodeToString(ResyncResponse.serializer(), response))
            } catch (e: Exception) {
                respond(exchange, 400, "{\"error\":\"${e.message}\"}")
            }
        }
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** The bound port (useful when started on port 0). */
    fun boundPort(): Int = server?.address?.port ?: port

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
