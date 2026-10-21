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
package com.example.kernel.internal.services

import io.github.dendygrobovshik.kardman.RDMA

/**
 * Store-facing services for the demo. Both are `@RDMA` so they are reachable from a
 * plugin over the bridge as well as callable directly from the host. The network +
 * bundle evaluation lives in platform `actual`s (see `StoreClient.android.kt` /
 * `StoreClient.ios.kt`), so the host/plugin never touches HTTP or the mmap cache.
 */
@RDMA
fun installStore(baseUrl: String, cacheDir: String, hostVersion: Int) {
    installStoreImpl(baseUrl, cacheDir, hostVersion)
}

@RDMA
fun generate(code: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
    generateExecute(code, onDone, onError)
}

@RDMA
fun resync(pluginId: String, version: Int, onDone: (String) -> Unit, onError: (String) -> Unit) {
    resyncExecute(pluginId, version, onDone, onError)
}

internal expect fun installStoreImpl(baseUrl: String, cacheDir: String, hostVersion: Int)

internal expect fun generateExecute(code: String, onDone: (String) -> Unit, onError: (String) -> Unit)

internal expect fun resyncExecute(pluginId: String, version: Int, onDone: (String) -> Unit, onError: (String) -> Unit)
