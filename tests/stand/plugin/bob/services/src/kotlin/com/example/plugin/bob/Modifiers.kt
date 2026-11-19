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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.kernel.internal.Button
import com.example.kernel.internal.Column
import com.example.kernel.internal.Modifier
import com.example.kernel.internal.Row
import com.example.kernel.internal.Text
import com.example.kernel.internal.modifierApplied
import com.example.kernel.internal.modifierClick
import com.example.kernel.internal.modifierCompositions
import kotlinx.coroutines.delay

// Modifiers as an @RDMA class: a chain applies to the kernel widget, scope elements apply only
// in their container, an equal chain (with the same memoized click lambda) lets the widget
// skip, a changed chain recomposes it, and a click reaches the plugin lambda.
@Composable
fun ModifiersDemo() {
    var tick by remember { mutableStateOf(0) }
    var clicks by remember { mutableStateOf(0) }
    Text("no scope", modifier = Modifier().weight(1.0).testTag("mod-noscope"))
    Column(modifier = Modifier().padding(4.0).background(0xFFEEEEEE).testTag("mod-column")) {
        Row(modifier = Modifier().fillMaxWidth()) {
            Text("stable", modifier = Modifier().weight(1.0).clickable { clicks++ }.testTag("mod-stable"))
            Text("tick=$tick", modifier = Modifier().padding(tick.toDouble()).testTag("mod-changing"))
        }
        Text("colstable", modifier = Modifier().clickable { clicks++ }.testTag("mod-colstable"))
        Text("clicks=$clicks", modifier = Modifier().size(120.0, 24.0).testTag("mod-clicks"))
        // onClick omitted: the widget's default applies.
        Button("plain", modifier = Modifier().padding(2.0).testTag("mod-button"))
    }
    LaunchedEffect(Unit) {
        // Counts are taken relative to this point: the stand host may have recreated this
        // component (key(contentVersion)) while plugins were still registering, and that also
        // restarts this effect.
        delay(300)
        val stable0 = modifierCompositions("mod-stable")
        val changing0 = modifierCompositions("mod-changing")
        val colStable0 = modifierCompositions("mod-colstable")
        repeat(3) {
            tick++
            delay(200)
        }
        val clicked = modifierClick("mod-stable")
        delay(300)
        val stable = modifierCompositions("mod-stable") - stable0
        val changing = modifierCompositions("mod-changing") - changing0
        val colStable = modifierCompositions("mod-colstable") - colStable0
        println("MOD_APPLIED: column=[${modifierApplied("mod-column")}] stable=[${modifierApplied("mod-stable")}] noscope=[${modifierApplied("mod-noscope")}] button=[${modifierApplied("mod-button")}]")
        println("MOD_SKIP: stable=+$stable colstable=+$colStable changing=+$changing")
        println("MOD_CLICK: clicked=$clicked clicks=$clicks")
    }
}
