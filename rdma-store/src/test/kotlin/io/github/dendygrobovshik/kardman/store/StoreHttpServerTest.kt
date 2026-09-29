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

import io.github.dendygrobovshik.kardman.types.PluginRelease
import io.github.dendygrobovshik.kardman.types.RdmaPluginContract
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

class StoreHttpServerTest {

    @Test
    fun `serves resync over http`() {
        val store = RdmaStore()
        store.registerRelease(
            PluginRelease(
                RdmaPluginContract(pluginId = "alice:counter", version = 2, minHost = 0),
                "hash-2",
            ),
        )
        val server = StoreHttpServer(store, port = 0)
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val health = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, health.statusCode())
            assertEquals("ok", health.body())

            val body = Json.encodeToString(
                ResyncRequest.serializer(),
                ResyncRequest("alice:counter", 1, 0, hostVersion = 10),
            )
            val resync = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/resync"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resync.statusCode())
            val response = Json.decodeFromString(ResyncResponse.serializer(), resync.body())
            assertEquals(2, response.targetVersion)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `serves bundles by key and 404s unknown keys`() {
        val store = RdmaStore()
        store.putBundle("F:internal:2", "polyfill".toByteArray())
        val server = StoreHttpServer(store, port = 0)
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val hit = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/bundle/F:internal:2")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, hit.statusCode())
            assertEquals("polyfill", hit.body())

            val miss = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/bundle/nope")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(404, miss.statusCode())
        } finally {
            server.stop()
        }
    }
}
