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
package com.example.plugin

import com.example.kernel.services.httpGet
import com.example.kernel.services.platformInfo

fun runServicesDemo() {
    val info = platformInfo()
    println("Platform: os=${info.os} model=${info.deviceModel} version=${info.osVersion}")

    httpGet(
        "https://example.com",
        { body: String -> println("HTTP OK: ${body.take(80)}") },
        { err: String -> println("HTTP ERR: $err") },
    )
}
