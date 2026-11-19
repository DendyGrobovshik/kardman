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

import com.sun.net.httpserver.HttpExchange

/**
 * Bundle delivery (compatibility.md §8.1). A bundle published with its release-time gzip copy
 * ([io.github.dendygrobovshik.kardman.kernel.RdmaBundleFiles]) travels compressed, with
 * `Content-Encoding: gzip`, to a client that accepts it; the platform HTTP clients
 * (`HttpURLConnection`, `NSURLSession`) decompress transparently. Nothing is compressed here.
 */
object RdmaBundleCompression {
    /** Serves `GET /bundle/<key>`: gzip when the client accepts it, 404 for an unknown key. */
    fun serve(exchange: HttpExchange, store: RdmaStore) {
        val key = exchange.requestURI.path.removePrefix("/bundle/")
        val acceptsGzip = exchange.requestHeaders["Accept-Encoding"].orEmpty()
            .any { header -> header.split(',').any { it.trim().substringBefore(';') == "gzip" } }
        val gz = if (acceptsGzip && key.isNotBlank()) store.gzippedBundle(key) else null
        val body = gz ?: if (key.isBlank()) null else store.bundle(key)
        if (body == null) {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
            return
        }
        println("served bundle $key (${body.size} bytes${if (gz != null) ", gzip" else ""})")
        exchange.responseHeaders.set("Content-Type", "application/octet-stream")
        if (gz != null) exchange.responseHeaders.set("Content-Encoding", "gzip")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
