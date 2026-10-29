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

import com.example.kernel.internal.services.appendToList
import com.example.kernel.internal.services.echoIntList
import com.example.kernel.internal.services.echoIntListList
import com.example.kernel.internal.services.httpGet
import com.example.kernel.internal.services.mutateInPlace
import com.example.kernel.internal.services.platformInfo
import com.example.kernel.internal.services.sumList

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

    runListMutationDemo()
    runListNestingDemo()
    runListCrossRuntimeDemo()
}

fun runListMutationDemo() {
    val list = mutableListOf(1, 2, 3)
    val echoed = echoIntList(list)
    list.add(4)
    list.set(0, 10)
    list.removeAt(1)
    println("LIST_MUT: size=${list.size} first=${list.get(0)} echoedSize=${echoed.size}")
    list.clear()
    println("LIST_MUT_CLEAR: size=${list.size}")
}

fun runListNestingDemo() {
    val nested = listOf(listOf(1, 2), listOf(3, 4))
    val echoed = echoIntListList(nested)
    val firstInner = echoed.get(0)
    println("LIST_NEST: outer=${echoed.size} inner0size=${firstInner.size} inner00=${firstInner.get(0)}")
}

fun runListCrossRuntimeDemo() {
    val list = mutableListOf(1, 2, 3)
    echoIntList(list)
    list.add(4)
    val sum = sumList(list)
    println("LIST_XRT_READ: sum=$sum")

    val list2 = mutableListOf(10, 20)
    echoIntList(list2)
    val appended = appendToList(list2, 30)
    println("LIST_XRT_WRITE: size=${appended.size} last=${appended.get(2)}")

    val list3 = mutableListOf(7)
    mutateInPlace(list3)
    println("LIST_XRT_K2J: size=${list3.size} last=${list3.get(list3.size - 1)}")
}
