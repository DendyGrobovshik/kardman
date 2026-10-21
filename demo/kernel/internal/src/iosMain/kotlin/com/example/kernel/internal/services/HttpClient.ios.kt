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

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy

internal actual fun httpExecute(
    method: String,
    url: String,
    headers: List<HttpHeader>?,
    body: String?,
    onSuccess: (HttpResponse) -> Unit,
    onError: (String) -> Unit,
) {
    val nsUrl = NSURL(string = url)
    if (nsUrl == null) {
        onError("bad url: $url")
        return
    }
    val request = NSMutableURLRequest.requestWithURL(nsUrl)
    request.setHTTPMethod(method)
    headers?.forEach { h -> request.setValue(h.value, forHTTPHeaderField = h.name) }
    if (body != null) {
        request.setHTTPBody(body.encodeToByteArray().toNSData())
    }

    NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
        if (error != null) {
            onError(error.localizedDescription)
            return@dataTaskWithRequest
        }
        val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        val text = data?.toByteArray()?.decodeToString().orEmpty()
        onSuccess(HttpResponse(status, emptyList(), text))
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
