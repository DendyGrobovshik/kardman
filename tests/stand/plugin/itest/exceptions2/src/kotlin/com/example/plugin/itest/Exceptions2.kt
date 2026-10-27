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
package com.example.plugin.itest

import androidx.compose.runtime.Composable
import com.example.kernel.internal.Column
import com.example.kernel.internal.Text
import com.example.kernel.internal.runRdmaApp
import com.example.kernel.user.itest.itestFireAndForget
import com.example.kernel.user.itest.itestThrow

// H2: the throwing fire-and-forget lambda is delivered to the host's global
// handler (design §6).
fun runExceptionScenarios() {
    itestFireAndForget { throw IllegalStateException("H2-BOOM") }
}

// Thrown only on the first composition so the host can observe the marker once and
// then render normally on subsequent recompositions.
private var didThrow = false

@Composable
fun Exceptions2Content() {
    Column {
        Text("EXCEPTIONS2")
    }
    if (!didThrow) {
        didThrow = true
        // A real plugin must NOT throw through content: errors are propagated via UI
        // state (Result / MutableState), never by synchronous rethrow. This fixture
        // deliberately throws to exercise two boundary behaviours at once:
        //
        //  1. S1a (JS -> host, sync): the content body throws, the bridge rethrows
        //     RdmaException on the host from nativeInvokeContent, and MainActivity
        //     catches it around a plain (non-@Composable) call.
        //  2. Double crossing (host -> JS -> host): itestThrow() makes the HOST throw;
        //     the bridge turns it into a JS Error carrying the host exception in
        //     __rdmaCause; the catch here rethrows the SAME error object, so the
        //     exception crosses back to the host once more. After the fromJSError fix
        //     the host-side RdmaException must carry a non-null cause chain.
        try {
            itestThrow("host-inner")
        } catch (e: Throwable) {
            throw e
        }
    }
}

fun main() {
    runExceptionScenarios()
    runRdmaApp { Exceptions2Content() }
}
