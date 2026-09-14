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
package com.example.kernel.services

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val httpExecutor = Executors.newCachedThreadPool()

internal actual fun httpExecute(
    method: String,
    url: String,
    headers: List<HttpHeader>?,
    body: String?,
    onSuccess: (HttpResponse) -> Unit,
    onError: (String) -> Unit,
) {
    httpExecutor.submit {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = method

            headers?.forEach { h -> connection.setRequestProperty(h.name, h.value) }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.bufferedWriter().use { it.write(body) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() } ?: ""

            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .flatMap { (k, values) -> values.map { HttpHeader(k!!, it) } }

            onSuccess(HttpResponse(code, responseHeaders, responseBody))
        } catch (e: Exception) {
            onError(e.message ?: "unknown error")
        } finally {
            connection?.disconnect()
        }
    }
}
