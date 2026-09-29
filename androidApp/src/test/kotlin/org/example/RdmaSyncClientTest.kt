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

import com.sun.net.httpserver.HttpServer
import io.github.dendygrobovshik.kardman.types.PluginState
import io.github.dendygrobovshik.kardman.types.ResyncRequest
import io.github.dendygrobovshik.kardman.types.ResyncResponse
import io.github.dendygrobovshik.kardman.types.ResyncStatus
import io.github.dendygrobovshik.kardman.types.UpdateSeverity
import kotlinx.serialization.json.Json
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RdmaSyncClientTest {

    private fun serve(response: ResyncResponse, knownKeys: Set<String>): HttpServer {
        val json = Json
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/resync") { ex ->
            val bytes = json.encodeToString(ResyncResponse.serializer(), response).toByteArray()
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/bundle") { ex ->
            val key = ex.requestURI.path.removePrefix("/bundle/")
            if (key !in knownKeys) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val bytes = "bundle-content-$key".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
        return server
    }

    @Test
    fun `resync parses an update and download fetches bundles by key`() {
        val server = serve(
            ResyncResponse(
                pluginId = "alice:counter",
                state = PluginState.CURRENT,
                severity = UpdateSeverity.NONE,
                status = ResyncStatus.UPDATE,
                currentVersion = 1,
                targetVersion = 2,
                bundleHash = "bundle-key",
                polyfills = listOf("F:internal:2"),
            ),
            knownKeys = setOf("bundle-key", "F:internal:2"),
        )
        try {
            val client = RdmaSyncClient("http://localhost:${server.address.port}")
            val resp = client.resync(ResyncRequest("alice:counter", 1, 0, 2))
            assertEquals(ResyncStatus.UPDATE, resp.status)
            assertEquals("bundle-key", resp.bundleHash)
            assertEquals("bundle-content-bundle-key", client.download("bundle-key")!!.decodeToString())
            assertEquals("bundle-content-F:internal:2", client.download("F:internal:2")!!.decodeToString())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `download returns null for a missing bundle`() {
        val server = serve(
            ResyncResponse("p", PluginState.CURRENT, UpdateSeverity.NONE, ResyncStatus.CURRENT, 1, 1),
            knownKeys = emptySet(),
        )
        try {
            val client = RdmaSyncClient("http://localhost:${server.address.port}")
            assertNull(client.download("missing-key"))
        } finally {
            server.stop(0)
        }
    }
}
