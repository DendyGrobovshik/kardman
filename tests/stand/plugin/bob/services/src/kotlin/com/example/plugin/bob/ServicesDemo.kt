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
package com.example.plugin.bob

import com.example.kernel.internal.services.httpGet
import com.example.kernel.internal.services.platformInfo

fun runServicesDemo() {
    val info = platformInfo()
    println("Platform: os=${info.os} model=${info.deviceModel} version=${info.osVersion}")

    httpGet(
        "https://example.com",
        { response ->
            println("HTTP ${response.status}: ${response.body ?: ""}")
        },
        { err -> println("HTTP ERR: $err") },
    )
}
