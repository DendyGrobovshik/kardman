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
package com.example.kernel.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.dendygrobovshik.kardman.RDMA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier as UiModifier

// A small modifier for the stand (the full one is in demo/kernel/internal). The plugin builds
// an immutable chain of elements; a widget applies it with `toComposeUi()` in its own
// composition. Elements are data classes, so equal chains are equal and Compose can skip.
@RDMA
@Immutable
class Modifier {
    internal var elements: List<ModifierElement> = emptyList()
        private set

    fun padding(all: Double): Modifier = with(Padding(all))
    fun size(width: Double, height: Double): Modifier = with(Size(width, height))
    fun fillMaxWidth(): Modifier = with(FillMaxWidth)
    fun background(argb: Long): Modifier = with(Background(argb))
    fun clickable(onClick: () -> Unit): Modifier = with(Clickable(onClick))
    fun weight(weight: Double): Modifier = with(Weight(weight))
    fun testTag(tag: String): Modifier = with(TestTag(tag))
    fun then(other: Modifier): Modifier = Modifier().also { it.elements = elements + other.elements }

    override fun equals(other: Any?): Boolean = other is Modifier && other.elements == elements
    override fun hashCode(): Int = elements.hashCode()

    private fun with(element: ModifierElement): Modifier = Modifier().also { it.elements = elements + element }
}

internal sealed interface ModifierElement
internal data class Padding(val all: Double) : ModifierElement
internal data class Size(val width: Double, val height: Double) : ModifierElement
internal data object FillMaxWidth : ModifierElement
internal data class Background(val argb: Long) : ModifierElement
internal data class Clickable(val onClick: () -> Unit) : ModifierElement
internal data class Weight(val weight: Double) : ModifierElement
internal data class TestTag(val tag: String) : ModifierElement

// The layout scope a container provides to its content, for scope elements (`weight`).
internal sealed interface LayoutScope {
    data class InRow(val scope: RowScope) : LayoutScope
    data class InColumn(val scope: ColumnScope) : LayoutScope
}

internal val LocalLayoutScope = compositionLocalOf<LayoutScope?> { null }

@Composable
internal fun ProvideLayoutScope(scope: LayoutScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutScope provides scope, content)
}

@Composable
internal fun Modifier.toComposeUi(): UiModifier {
    val scope = LocalLayoutScope.current
    var acc: UiModifier = UiModifier
    val applied = ArrayList<String>()
    for (e in elements) {
        acc = when (e) {
            is Padding -> acc.padding(e.all.dp).also { applied += "padding(${e.all})" }
            is Size -> acc.size(e.width.dp, e.height.dp).also { applied += "size(${e.width},${e.height})" }
            FillMaxWidth -> acc.fillMaxWidth().also { applied += "fillMaxWidth" }
            is Background -> acc.background(Color(e.argb.toInt())).also { applied += "background" }
            is Clickable -> acc.clickable(onClick = e.onClick).also { applied += "clickable" }
            is TestTag -> acc.testTag(e.tag).also { applied += "testTag" }
            is Weight -> when (scope) {
                is LayoutScope.InRow -> with(scope.scope) { acc.weight(e.weight.toFloat()) }.also { applied += "weight(${e.weight})@Row" }
                is LayoutScope.InColumn -> with(scope.scope) { acc.weight(e.weight.toFloat()) }.also { applied += "weight(${e.weight})@Column" }
                null -> acc.also { applied += "weight(ignored)" }
            }
        }
    }
    ModifierProbe.record(this, applied)
    return acc
}

// Test probe: what a tagged chain applied, how many times its widget composed, and a click
// on its `clickable` from the main thread (as a real tap would deliver it).
internal object ModifierProbe {
    private val compositions = HashMap<String, Int>()
    private val applied = HashMap<String, String>()
    private val clicks = HashMap<String, () -> Unit>()

    fun record(modifier: Modifier, appliedNames: List<String>) {
        val tag = modifier.elements.filterIsInstance<TestTag>().firstOrNull()?.tag ?: return
        compositions[tag] = (compositions[tag] ?: 0) + 1
        applied[tag] = appliedNames.joinToString(" ")
        modifier.elements.filterIsInstance<Clickable>().firstOrNull()?.let { clicks[tag] = it.onClick }
    }

    fun compositions(tag: String): Int = compositions[tag] ?: 0
    fun applied(tag: String): String = applied[tag] ?: ""
    fun click(tag: String): Boolean {
        val onClick = clicks[tag] ?: return false
        CoroutineScope(Dispatchers.Main).launch { onClick() }
        return true
    }
}

@RDMA
fun modifierCompositions(tag: String): Int = ModifierProbe.compositions(tag)

@RDMA
fun modifierApplied(tag: String): String = ModifierProbe.applied(tag)

@RDMA
fun modifierClick(tag: String): Boolean = ModifierProbe.click(tag)
