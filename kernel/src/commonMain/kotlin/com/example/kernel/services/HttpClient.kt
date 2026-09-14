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

import io.github.dendygrobovshik.kardman.RDMA

/**
 * Offloads HTTP to the kernel's IO thread and delivers a typed [HttpResponse] back
 * to the plugin via [onSuccess]/[onError]. The plugin is single-threaded and has no
 * sockets, so network access must live in the kernel.
 */
@RDMA
class HttpHeader(val name: String, val value: String)

@RDMA
class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<HttpHeader>?,
    val body: String?,
)

@RDMA
class HttpResponse(
    val status: Int,
    val headers: List<HttpHeader>?,
    val body: String?,
)

@RDMA
fun httpSend(request: HttpRequest, onSuccess: (HttpResponse) -> Unit, onError: (String) -> Unit) {
    httpExecute(request.method, request.url, request.headers, request.body, onSuccess, onError)
}

@RDMA
fun httpGet(url: String, onSuccess: (HttpResponse) -> Unit, onError: (String) -> Unit) {
    httpExecute("GET", url, null, null, onSuccess, onError)
}

@RDMA
fun httpPost(url: String, body: String?, onSuccess: (HttpResponse) -> Unit, onError: (String) -> Unit) {
    httpExecute("POST", url, null, body, onSuccess, onError)
}

internal expect fun httpExecute(
    method: String,
    url: String,
    headers: List<HttpHeader>?,
    body: String?,
    onSuccess: (HttpResponse) -> Unit,
    onError: (String) -> Unit,
)
