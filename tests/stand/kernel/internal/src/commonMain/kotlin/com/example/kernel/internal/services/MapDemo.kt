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
 * Map bridge exercises: mutation (set/remove), nested values (map of lists), and
 * cross-runtime read/write. Mirrors ListDemo.
 */
@RDMA
fun echoStringMap(map: Map<String, String>): Map<String, String> = map

@RDMA
fun echoIntListMap(map: Map<Int, List<Int>>): Map<Int, List<Int>> = map

@RDMA
fun mapValue(map: Map<String, String>, key: String): String? = map[key]

@RDMA
fun putMapValue(map: Map<String, String>, key: String, value: String) {
    (map as MutableMap<String, String>)[key] = value
}
