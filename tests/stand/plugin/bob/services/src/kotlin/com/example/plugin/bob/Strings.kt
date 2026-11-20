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

import com.example.kernel.internal.services.TextBox
import com.example.kernel.internal.services.describeText
import com.example.kernel.internal.services.echoNullableText
import com.example.kernel.internal.services.echoText

// Strings across the boundary: null both ways (arguments, results, properties), emoji, U+0000.
// On iOS a String with U+0000 fails loudly (a C string would cut it short).
fun stringsProbe(): String {
    val box = TextBox(null)
    val boxNull = box.text == null
    box.text = "set"
    val boxSet = box.text
    box.text = null
    val emoji = "a😀b"
    val nul = try { echoText("a\u0000b").length.toString() } catch (e: Throwable) { "error" }
    val sb = StringBuilder()
    sb.append("x").append(1).append('c')
    val built = buildString { append("y"); append(2) }
    println("STRING_BUILDER: sb=$sb built=$built")
    return "STRINGS: echoNull=${echoNullableText(null) == null} describe=${describeText(null)} " +
        "boxNull=$boxNull boxSet=$boxSet boxReset=${box.text == null} " +
        "emoji=${echoText(emoji) == emoji} nul=$nul"
}
