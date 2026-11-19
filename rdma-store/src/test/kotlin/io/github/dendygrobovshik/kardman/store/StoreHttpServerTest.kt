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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreHttpServerTest {

    @Test
    fun `serves the release-time gzip copy when the client accepts it`() {
        val versions = kotlin.io.path.createTempDirectory("rdma-store").toFile()
        val bundle = ByteArray(64 * 1024) { (it % 7).toByte() }
        io.github.dendygrobovshik.kardman.kernel.RdmaBundleFiles.publish(versions, "F:internal:2", bundle)
        assertTrue(java.io.File(versions, "bundles/F:internal:2.gz").isFile)
        val store = RdmaStore()
        store.loadBundles(java.io.File(versions, "bundles"))
        // A bundle published without a gzip copy is served as is, never compressed on the fly.
        store.putBundle("plain", bundle)
        val server = StoreHttpServer(store, port = 0)
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val uri = URI("http://localhost:${server.boundPort()}/bundle/F:internal:2")
            val gz = client.send(
                HttpRequest.newBuilder(uri).header("Accept-Encoding", "gzip, deflate").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, gz.statusCode())
            assertEquals("gzip", gz.headers().firstValue("Content-Encoding").orElse(""))
            assertTrue(gz.body().size < bundle.size / 10, "compressed ${gz.body().size} of ${bundle.size}")
            assertContentEquals(bundle, java.util.zip.GZIPInputStream(gz.body().inputStream()).readBytes())

            val plain = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            assertEquals("", plain.headers().firstValue("Content-Encoding").orElse(""))
            assertContentEquals(bundle, plain.body())

            val missing = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/bundle/nope")).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(404, missing.statusCode())

            val noCopy = client.send(
                HttpRequest.newBuilder(URI("http://localhost:${server.boundPort()}/bundle/plain")).header("Accept-Encoding", "gzip").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals("", noCopy.headers().firstValue("Content-Encoding").orElse(""))
            assertContentEquals(bundle, noCopy.body())
        } finally {
            server.stop()
        }
    }

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
