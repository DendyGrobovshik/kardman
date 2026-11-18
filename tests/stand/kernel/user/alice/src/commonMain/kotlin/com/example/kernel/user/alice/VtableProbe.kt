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
package com.example.kernel.user.alice

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import io.github.dendygrobovshik.kardman.RDMA

// Native callers of plugin overrides (the vtable, docs/design/runtime_rework.md §6.4, §2.8):
// a non-`String` result must arrive typed, a throwing override must reach the native caller
// as an exception, from the Hermes thread (`describePerson`, called by the plugin) and from
// the UI thread (`PersonProbe`, a kernel widget).

private fun describe(p: Person): String {
    val risky = try {
        p.risky()
    } catch (e: Throwable) {
        "caught:" + (e.message ?: e::class.simpleName)
    }
    return "power=${p.power()} greet=${p.greet()} risky=$risky"
}

@RDMA
fun describePerson(p: Person): String = describe(p)

@RDMA
@Composable
fun PersonProbe(p: Person) {
    val line = "VT_UI " + describe(p)
    SideEffect { println(line) }
    Text(line)
}
