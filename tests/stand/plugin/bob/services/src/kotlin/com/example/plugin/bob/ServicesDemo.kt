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
import com.example.kernel.internal.services.computeSum
import com.example.kernel.internal.services.describeInfoSuspend
import com.example.kernel.internal.services.echoIntList
import com.example.kernel.internal.services.echoIntListList
import com.example.kernel.internal.services.echoIntListMap
import com.example.kernel.internal.services.echoIntListSuspend
import com.example.kernel.internal.services.echoIntMapSuspend
import com.example.kernel.internal.services.echoStringMap
import com.example.kernel.internal.services.failNestedSuspend
import com.example.kernel.internal.services.failSuspend
import com.example.kernel.internal.services.fetchPlatformInfoSuspend
import com.example.kernel.internal.services.httpGet
import com.example.kernel.internal.services.mapValue
import com.example.kernel.internal.services.maybeNullSuspend
import com.example.kernel.internal.services.mutateInPlace
import com.example.kernel.internal.services.platformInfo
import com.example.kernel.internal.services.putMapValue
import com.example.kernel.internal.services.sumList
import com.example.kernel.internal.services.touchSuspend
import com.example.kernel.internal.services.SuspendService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    runListIterationDemo()
    runMapMutationDemo()
    runMapNestingDemo()
    runMapCrossRuntimeDemo()

    CoroutineScope(Dispatchers.Main).launch { runSuspendDemo() }
}

suspend fun runSuspendDemo() {
    val sum = computeSum(2, 3)
    println("SUSPEND_FN: sum=$sum")

    val list = echoIntListSuspend(listOf(1, 2, 3))
    println("SUSPEND_LIST: size=${list.size} first=${list.get(0)}")

    val service = SuspendService()
    val v = service.fetch(7)
    println("SUSPEND_METHOD: v=$v")

    val map = echoIntMapSuspend(mapOf("a" to 1, "b" to 2))
    println("SUSPEND_MAP: size=${map.size} b=${map.get("b")}")

    touchSuspend()
    println("SUSPEND_UNIT: ok")

    val nullVal = maybeNullSuspend(false)
    val nonNull = maybeNullSuspend(true)
    println("SUSPEND_NULL: null=${nullVal ?: "null"} v=$nonNull")

    val info = platformInfo()
    val desc = describeInfoSuspend(info)
    println("SUSPEND_ARG: $desc")

    try {
        failSuspend()
        println("SUSPEND_ERR: NOT_THROWN")
    } catch (e: Throwable) {
        println("SUSPEND_ERR: caught=${e.message}")
    }

    try {
        failNestedSuspend()
        println("SUSPEND_CAUSE: NOT_THROWN")
    } catch (e: Throwable) {
        println("SUSPEND_CAUSE: msg=${e.message} cause=${e.cause?.message} inner=${e.cause?.cause?.message}")
    }

    val pinfo = fetchPlatformInfoSuspend()
    println("SUSPEND_RDMA: os=${pinfo.os} model=${pinfo.deviceModel} version=${pinfo.osVersion}")

    // Three suspend calls fired concurrently; each completion must be routed back
    // to its own continuation.
    CoroutineScope(Dispatchers.Main).launch {
        val a = computeSum(1, 1)
        println("SUSPEND_CONCURRENT_1: $a")
    }
    CoroutineScope(Dispatchers.Main).launch {
        val b = computeSum(2, 2)
        println("SUSPEND_CONCURRENT_2: $b")
    }
    CoroutineScope(Dispatchers.Main).launch {
        val c = computeSum(3, 3)
        println("SUSPEND_CONCURRENT_3: $c")
    }

    runTimerDemo()
}

suspend fun runTimerDemo() {
    // delay(1000) must not complete within 100ms: the timeout fires first, proving
    // the timer actually waits rather than resuming immediately.
    val timedOut = withTimeoutOrNull(100) { delay(1000); "done" }
    println("SUSPEND_TIMEOUT_OK: result=${timedOut ?: "null"}")

    // delay(100) must complete within 500ms: proving the timer does fire and resume.
    val completed = withTimeoutOrNull(500) { delay(100); "done" }
    println("SUSPEND_TIMEOUT_DONE: result=${completed ?: "null"}")

    // Cancelling a pending delay must not crash and must not block the scope.
    val job = CoroutineScope(Dispatchers.Main).launch { delay(10000) }
    delay(20)
    job.cancel()
    delay(30)
    println("SUSPEND_CANCEL_OK")
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

fun runListIterationDemo() {
    val list = mutableListOf(1, 2, 3)
    echoIntList(list)
    list.add(4)
    val doubled = list.map { it * 2 }
    println("LIST_ITER: sum=${doubled.sum()} joined=${list.joinToString(",")}")
    println("LIST_STR: ${list.toString()}")
    println("LIST_KSTR: ${echoIntList(listOf(5, 6)).toString()}")
}

fun runMapMutationDemo() {
    val map = mutableMapOf("a" to "1", "b" to "2")
    echoStringMap(map)
    map["c"] = "3"
    map.remove("a")
    println("MAP_MUT: size=${map.size} b=${map["b"]}")
}

fun runMapNestingDemo() {
    val map = mapOf(1 to listOf(1, 2), 2 to listOf(3, 4))
    val echoed = echoIntListMap(map)
    val first = echoed.get(1)!!
    println("MAP_NEST: size=${echoed.size} inner0size=${first.size} inner00=${first.get(0)}")
}

fun runMapCrossRuntimeDemo() {
    val map = mutableMapOf("a" to "1")
    echoStringMap(map)
    map["b"] = "2"
    val v = mapValue(map, "b")
    println("MAP_XRT_READ: v=$v")

    val map2 = mutableMapOf("x" to "10")
    echoStringMap(map2)
    putMapValue(map2, "y", "20")
    println("MAP_XRT_WRITE: size=${map2.size} y=${map2["y"]}")

    val map3 = mutableMapOf("k" to "v")
    putMapValue(map3, "k2", "v2")
    println("MAP_XRT_K2J: size=${map3.size} k2=${map3["k2"]}")
}
