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
package com.example.plugin.alice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.kernel.internal.services.HttpMethod
import com.example.kernel.internal.services.LongBox
import com.example.kernel.internal.services.echoLong
import com.example.kernel.internal.services.echoLongList
import com.example.kernel.internal.services.echoLongSuspend
import com.example.kernel.internal.services.echoMethodList
import com.example.kernel.user.alice.Person

// Round-trip probe for the typed state channels (docs/design/type_descriptors.md):
// every value goes through the generated `rdmaMutableStateOf` baked with the call
// site's type descriptor, and prints markers that state-behavior.sh (Android) and
// ios-state-behavior.sh (iOS) assert.
//
// It covers the full boundary type set on state channels (primitives, `@RDMA`
// enums and objects, lists/maps, snapshot collections), the Long matrix P1-P7
// (full-fidelity `Long(low, high)` crossings), and the loud-failure negatives: a
// raw JS number in a Long channel, a foreign type, `null` in a non-null channel,
// and an unknown enum name must all raise `RdmaException` — each captured with a
// `STATE_NEG_<case>_CAUGHT` marker (and a `_NO_THROW` marker printed when they
// wrongly succeed).
//
// Stand invariant: the demo host renders the LAST content registered via
// `runRdmaApp`, and the load manifest (`assets/kotlin/rdma-modules.json`) evaluates
// plugin bundles in dependency order (bob-services -> alice-counter ->
// itest-exceptions -> itest-exceptions2). The exception crash-test plugins are
// opted out of the bake (rdmaBakeInApp=false) by the state e2e scripts, so this
// module's Counter is the content that composes there.

/** Erased re-typing for the negative probes: hands the runtime value through with
 *  no conversion, so a raw JS number can reach a typed channel untouched. */
@Suppress("UNCHECKED_CAST")
private fun <A, B> retype(value: A): B = value as B

private inline fun safeSuite(suite: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        println("STATE_${suite}_ERROR: ${e.message ?: e::class.simpleName}")
    }
}

@Composable
fun StateTypesProbe() {
    // Each exercise runs exactly once per composition position: recomposition must
    // not repeat the mutations (a `+ 1L` write in composition would loop forever).
    remember {
        safeSuite("SCALARS") { runScalarSuite() }
        safeSuite("LONG") { runLongMatrixSuite() }
        safeSuite("ENUM") { runEnumMatrixSuite() }
        safeSuite("OBJECT") { runStateObjectSuite() }
        safeSuite("NEGATIVES") { runNegativeSuite() }
        Unit
    }
    // Suspend crossings resume on the Hermes-thread dispatcher after composition.
    LaunchedEffect(Unit) { runLongSuspendSuite() }
}

private fun runScalarSuite() {
    val d = mutableStateOf(19.9)
    // 2^53+1 is beyond the exact JS-integer range: with the full-fidelity Long
    // crossing (docs/design/type_descriptors.md#long-policy) it round-trips as the
    // plugin's `Long(low, high)` object and supports ordinary Long arithmetic.
    val big = mutableStateOf(9007199254740993L)
    val nullable = mutableStateOf<String?>(null)
    println("STATE_DOUBLE: ${d.value}")
    println("STATE_LONG: ${big.value}")
    big.value = big.value + 1L
    println("STATE_LONG_SET: ${big.value}")
    big.value = 9223372036854775807L
    println("STATE_LONG_MAX: ${big.value}")
    nullable.value = "set"
    println("STATE_NULL_AFTER: ${nullable.value}")
    d.value = 20.5
    println("STATE_DOUBLE_SET: ${d.value}")
    // Snapshot collections: mutations through the proxy run the kernel
    // SnapshotStateList's mutators (docs/design/type_descriptors.md#state-semantics).
    // The factory itself is not composable — capture its failure with `runCatching`
    // so a loud unsupported channel does not kill the remaining probes.
    val slist = runCatching { mutableStateListOf(1, 2) }
    try {
        val list = slist.getOrThrow()
        list.add(3)
        println("STATE_SLIST: size=${list.size} first=${list.get(0)}")
    } catch (e: Throwable) {
        println("STATE_SLIST_UNSUPPORTED: ${e.message ?: e::class.simpleName}")
    }
}

// Long matrix (docs/design/type_descriptors.md#long-policy): P1 literal -> arg ->
// echo, P2 Long.MAX_VALUE round-trip, P3 kernel return -> `+1L` arithmetic, P5
// state value, P6 List<Long> element (kernel list channel + snapshot list), P7
// `Long` field of an `@RDMA` class. P4 (suspend result) is in
// `runLongSuspendSuite`; N (raw JS number) is in `runNegativeSuite`.
private fun runLongMatrixSuite() {
    // Long values must be interpolated through Long-TYPED locals: a `dynamic`
    // string template coerces the plugin's `Long(low, high)` object through JS
    // `valueOf` and prints the lossy double shape. The crossing itself is exact —
    // the typed locals below are the probe's proof of it.
    val p1: Long = echoLong(2L)
    println("STATE_LONG_P1: $p1")
    val p2: Long = echoLong(9223372036854775807L)
    println("STATE_LONG_P2: $p2")
    val r: Long = echoLong(9007199254740993L)
    println("STATE_LONG_P3: ${r + 1L}")

    val p5 = mutableStateOf(7L)
    p5.value = p5.value + 1L
    println("STATE_LONG_P5: ${p5.value}")

    val echoed = echoLongList(listOf(9007199254740993L))
    val first: Long = echoed.get(0)
    println("STATE_LONG_P6: $first")
    val slist = runCatching { mutableStateListOf(9007199254740993L) }
    try {
        val firstState: Long = slist.getOrThrow().get(0)
        println("STATE_LONG_P6_STATE: $firstState")
    } catch (e: Throwable) {
        println("STATE_LONG_P6_STATE_UNSUPPORTED: ${e.message ?: e::class.simpleName}")
    }

    // The class is built with its defaulted `value` param (the plugin's constructor
    // bridge takes literals); the crossing under test is the Long field get/set.
    val box = LongBox()
    box.value = 100L
    val v7: Long = box.value
    println("STATE_LONG_P7: $v7")
    box.value = v7 + 1L
    val v7set: Long = box.value
    println("STATE_LONG_P7_SET: $v7set")
}

private suspend fun runLongSuspendSuite() {
    val v: Long = echoLongSuspend(42L)
    println("STATE_LONG_P4: $v")
}

// Enum matrix: entries cross as their name strings (docs/design/types.md); writes
// are materialized kernel-side via `valueOf`. Covers state, `List` elements (the
// snapshot list and a plain kernel List channel), map keys and values, a nested
// list-of-list, and a nullable enum state.
private fun runEnumMatrixSuite() {
    val es = mutableStateOf(HttpMethod.GET)
    println("STATE_ENUM_STATE: ${es.value}")
    es.value = HttpMethod.POST
    println("STATE_ENUM_STATE_SET: ${es.value}")

    val esl = mutableStateListOf(HttpMethod.GET)
    esl.add(HttpMethod.POST)
    println("STATE_ENUM_SLIST: first=${esl.get(0)} last=${esl.get(esl.size - 1)}")

    val echoed = echoMethodList(listOf(HttpMethod.GET, HttpMethod.POST))
    println("STATE_ENUM_LIST: first=${echoed.get(0)} last=${echoed.get(echoed.size - 1)}")

    val keyMap = mutableStateMapOf(HttpMethod.GET to "a")
    keyMap[HttpMethod.POST] = "b"
    println("STATE_ENUM_MAP_KEY: ${keyMap[HttpMethod.POST]}")

    val valueMap = mutableStateMapOf("a" to HttpMethod.GET)
    valueMap["a"] = HttpMethod.POST
    println("STATE_ENUM_MAP_VALUE: ${valueMap["a"]}")

    val nested = mutableStateListOf(listOf(HttpMethod.GET))
    println("STATE_ENUM_NESTED: ${nested.get(0).get(0)}")

    val flag = false
    val ens = mutableStateOf(if (flag) HttpMethod.GET else null)
    println("STATE_ENUM_NULLABLE: ${ens.value}")
    ens.value = HttpMethod.GET
    println("STATE_ENUM_NULL_SET: ${ens.value}")
}

// `@RDMA` object values on a state channel: reads and writes cross as owned
// handles (docs/design/type_descriptors.md).
private fun runStateObjectSuite() {
    val os = mutableStateOf(Person("ada", 3))
    println("STATE_OBJ: name=${os.value.name} age=${os.value.age}")
    os.value = Person("bob", 4)
    println("STATE_OBJ_SET: name=${os.value.name} age=${os.value.age}")
}

// Loud-failure negatives: every wrongly-typed write must raise `RdmaException`
// (`STATE_NEG_<case>_CAUGHT`); a silent accept prints `STATE_NEG_<case>_NO_THROW`,
// which the e2e scripts treat as failure.
private fun runNegativeSuite() {
    val raw: Long = retype(42.0)

    val fnState = try {
        echoLong(raw)
        "NO_THROW"
    } catch (e: Throwable) {
        "CAUGHT: ${e.message ?: e::class.simpleName}"
    }
    println("STATE_NEG_RAW_NUMBER_IN_LONG_FN_$fnState")

    val ls = mutableStateOf(1L)
    val anyLs: MutableState<Any?> = retype(ls)
    try {
        anyLs.value = raw
        println("STATE_NEG_RAW_NUMBER_IN_LONG_STATE_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_RAW_NUMBER_IN_LONG_STATE_CAUGHT: ${e.message ?: e::class.simpleName}")
    }

    val ll = mutableStateListOf(1L)
    try {
        ll.set(0, raw)
        println("STATE_NEG_RAW_NUMBER_IN_LONG_LIST_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_RAW_NUMBER_IN_LONG_LIST_CAUGHT: ${e.message ?: e::class.simpleName}")
    }

    val lm = mutableStateMapOf("a" to 1L)
    try {
        lm["a"] = raw
        println("STATE_NEG_RAW_NUMBER_IN_LONG_MAP_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_RAW_NUMBER_IN_LONG_MAP_CAUGHT: ${e.message ?: e::class.simpleName}")
    }

    val ns = mutableStateOf(0)
    val anyNs: MutableState<Any?> = retype(ns)
    try {
        anyNs.value = "boom"
        println("STATE_NEG_FOREIGN_TYPE_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_FOREIGN_TYPE_CAUGHT: ${e.message ?: e::class.simpleName}")
    }
    try {
        anyNs.value = null
        println("STATE_NEG_NULL_IN_NONNULL_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_NULL_IN_NONNULL_CAUGHT: ${e.message ?: e::class.simpleName}")
    }

    val es = mutableStateOf(HttpMethod.GET)
    val anyEs: MutableState<Any?> = retype(es)
    try {
        anyEs.value = "BOGUS"
        println("STATE_NEG_ENUM_BAD_NAME_NO_THROW")
    } catch (e: Throwable) {
        println("STATE_NEG_ENUM_BAD_NAME_CAUGHT: ${e.message ?: e::class.simpleName}")
    }
}
