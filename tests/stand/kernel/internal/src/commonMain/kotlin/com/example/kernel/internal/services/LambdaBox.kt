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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Lambdas as values (docs/internals/blocks_and_lambdas.md): plugin lambdas with a result
// called by the kernel (synchronously, from the plugin's thread and from a background
// thread), kernel lambdas handed to the plugin, and lambda-typed properties.

@RDMA
class LambdaBox(var format: (Int) -> String) {
    var onEvent: ((String) -> Unit)? = null

    /** Calls the plugin lambda and uses its result. */
    fun render(x: Int): String = "[" + format(x) + "]"

    /** A kernel lambda handed to the plugin. */
    fun adder(n: Int): (Int) -> Int = { it + n }

    /** Calls the plugin lambda from a background thread; the result goes back by callback. */
    @OptIn(DelicateCoroutinesApi::class)
    fun renderInBackground(x: Int, onDone: (String) -> Unit) {
        GlobalScope.launch(Dispatchers.Default) {
            val text = try { format(x) } catch (e: Throwable) { "error: " + e.message }
            onDone(text)
        }
    }

    fun fire(message: String) {
        onEvent?.invoke(message)
    }
}

/** A kernel lambda returned by a top-level function. */
@RDMA
fun makeGreeter(prefix: String): (String) -> String = { "$prefix, $it" }

/** A plugin lambda with a result passed to a top-level function. */
@RDMA
fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))
