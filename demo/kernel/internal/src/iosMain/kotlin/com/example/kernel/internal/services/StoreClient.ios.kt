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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.runtime.RdmaBridge
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.writeToFile
import platform.posix.memcpy
import platform.posix.mkdir

private val json = Json { ignoreUnknownKeys = true }

private var storeBaseUrl = "http://localhost:8080"
private var storeCacheDir = ""
private var storeHostVersion = 1

internal actual fun installStoreImpl(baseUrl: String, cacheDir: String, hostVersion: Int) {
    storeBaseUrl = baseUrl
    storeCacheDir = cacheDir.let { if (it.endsWith("/")) it else "$it/" }
    storeHostVersion = hostVersion
    mkdir(storeCacheDir, 0x1FFu.toUShort())
}

internal actual fun generateExecute(code: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
    val body = buildJsonObject { put("code", code) }.toString()
    httpRequest("POST", "$storeBaseUrl/generate", body) { text, err ->
        if (err != null) {
            onError(err)
            return@httpRequest
        }
        val obj = json.parseToJsonElement(text!!).jsonObject
        val pluginId = obj["pluginId"]!!.jsonPrimitive.content
        val version = obj["version"]!!.jsonPrimitive.int
        resyncExecute(pluginId, (version - 1).coerceAtLeast(0), onDone, onError)
    }
}

internal actual fun resyncExecute(pluginId: String, version: Int, onDone: (String) -> Unit, onError: (String) -> Unit) {
    val body = buildJsonObject {
        put("pluginId", pluginId)
        put("version", version)
        put("builtAgainstKernel", storeHostVersion)
        put("hostVersion", storeHostVersion)
    }.toString()
    httpRequest("POST", "$storeBaseUrl/resync", body) { text, err ->
        if (err != null) {
            onError(err)
            return@httpRequest
        }
        val obj = json.parseToJsonElement(text!!).jsonObject
        val status = obj["status"]!!.jsonPrimitive.content
        when (status) {
            "UPDATE" -> {
                obj["bundleHash"]?.jsonPrimitive?.content?.let { hash -> downloadAndEval(hash) }
                obj["polyfills"]?.jsonArray?.forEach { key -> downloadAndEvalBytes(key.jsonPrimitive.content) }
                onDone("updated $pluginId -> v${obj["targetVersion"]}")
            }
            "CURRENT" -> onDone("current $pluginId v${obj["targetVersion"]}")
            "UNAVAILABLE" -> onDone("unavailable $pluginId")
            "REFUSED" -> onDone("refused $pluginId")
            else -> onDone("$status $pluginId")
        }
    }
}

private fun downloadAndEval(hash: String) {
    val file = "$storeCacheDir$hash.hbc"
    downloadRaw("$storeBaseUrl/bundle/$hash") { bytes ->
        if (bytes != null) {
            bytes.toNSData().writeToFile(file, atomically = true)
            RdmaBridge.nativeEvalFile(file)
        }
    }
}

private fun downloadAndEvalBytes(key: String) {
    downloadRaw("$storeBaseUrl/bundle/$key") { bytes ->
        if (bytes != null) RdmaBridge.nativeEvalBytes(bytes)
    }
}

private fun downloadRaw(url: String, onDone: (ByteArray?) -> Unit) {
    val nsUrl = NSURL(string = url)
    if (nsUrl == null) {
        onDone(null)
        return
    }
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.setHTTPMethod("GET")
    NSURLSession.sharedSession.dataTaskWithRequest(request) { data, _, error ->
        if (error != null) onDone(null) else onDone(data?.toByteArray())
    }.resume()
}

private fun httpRequest(method: String, url: String, body: String?, onDone: (String?, String?) -> Unit) {
    val nsUrl = NSURL(string = url)
    if (nsUrl == null) {
        onDone(null, "bad url: $url")
        return
    }
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.setHTTPMethod(method)
    request.setValue("application/json", forHTTPHeaderField = "Content-Type")
    if (body != null) {
        request.setHTTPBody(body.encodeToByteArray().toNSData())
    }
    NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
        if (error != null) {
            onDone(null, error.localizedDescription)
            return@dataTaskWithRequest
        }
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val text = data?.toByteArray()?.decodeToString().orEmpty()
        if (status in 200..299) onDone(text, null) else onDone(null, "HTTP $status: $text")
    }.resume()
}

private fun ByteArray.toNSData(): NSData =
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    return ByteArray(size).also { arr ->
        if (size > 0) {
            arr.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
        }
    }
}
