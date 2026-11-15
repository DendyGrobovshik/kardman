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
package io.github.dendygrobovshik.kardman.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Applier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Host composition cost of a 32×4 grid. Modifiers applies eight modifier
 * elements per cell with [toComposeUi]. NoModifiers is the same grid of
 * empty boxes. [Composition.setContent] is the first composition. The
 * following sample writes state and calls `recompose`/`applyChanges`,
 * which is the recomposition. Neither pass measures or draws.
 */
class ModifierCompositionBenchTest {
    @Test
    fun `modifier grid composition and recomposition`() {
        measure(withModifiers = true, "modifiers")
    }

    @Test
    fun `no modifier grid composition and recomposition`() {
        measure(withModifiers = false, "nomodifiers")
    }

    private fun measure(withModifiers: Boolean, name: String) {
        val warmup = 2
        val runs = 5
        val samples = List(warmup + runs) { sample(withModifiers) }
        val measured = samples.drop(warmup)
        val composeMs = measured.map { it.composeNs }.medianNanosToMs()
        val recomposeMs = measured.map { it.recomposeNs }.medianNanosToMs()
        println(
            "$name grid ${Rows}x$Cols " +
                "(${Rows * Cols} cells): " +
                "composition median $composeMs ms, recomposition median $recomposeMs ms " +
                "(warmup $warmup, runs $runs)",
        )
        measured.forEach { assertEquals(Rows * Cols, it.cells) }
    }

    private fun sample(withModifiers: Boolean): Sample {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val root = newLayoutNode()
        val composition = Composition(newUiApplier(root), recomposer)
        val tick = mutableStateOf(0)
        var passes = 0
        try {
            val composeNs = measureNanoTime {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalViewConfiguration provides BenchViewConfiguration,
                    ) {
                        Grid(tick, withModifiers) { passes++ }
                    }
                }
            }
            assertEquals(1, passes)
            tick.value = 1
            Snapshot.sendApplyNotifications()
            // The recomposer apply observer is what normally forwards this write.
            // The benchmark calls recordWriteOf itself so the grid's scope is invalidated.
            recordWrite(composition, tick)
            val recomposeNs = measureNanoTime {
                assertTrue(recomposeAndApply(composition), observationDump(composition))
            }
            assertEquals(2, passes)
            return Sample(composeNs, recomposeNs, countLeaves(root))
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }
}

private const val Rows = 32
private const val Cols = 4

private data class Sample(val composeNs: Long, val recomposeNs: Long, val cells: Int)

@Composable
private fun Grid(tick: State<Int>, withModifiers: Boolean, onPass: () -> Unit) {
    onPass()
    val value = tick.value
    Column {
        repeat(Rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                if (withModifiers) {
                    provideModifierScope {
                        repeat(Cols) { col ->
                            Box(modifier = cellChain(row, col, value).toComposeUi())
                        }
                    }
                } else {
                    repeat(Cols) {
                        Box(modifier = Modifier)
                    }
                }
            }
        }
    }
}

private fun cellChain(row: Int, col: Int, tick: Int): io.github.dendygrobovshik.kardman.compose.Modifier {
    val band = (row + col + tick) % 2
    return io.github.dendygrobovshik.kardman.compose.Modifier()
        .weight(1.0)
        .paddingAll(Dp(4.0))
        .background(
            if (band == 0) Color.Red else Color.Blue,
            if (col % 2 == 0) Shape.Rectangle else Shape.Circle,
        )
        .paddingSymmetric(Dp(6.0), Dp(2.0))
        .alpha(if (tick % 2 == 0) 1.0 else 0.7)
        .offset(Dp(0.0), Dp((row % 3).toDouble()))
        .alignVertically(VerticalAlignment.CenterVertically)
        .testTag("c${row}x$col")
}

// LayoutNode and DefaultUiApplier are internal. Composition still needs a real
// UI applier, because Column and Box cast the current one.
private fun newLayoutNode(): Any {
    val ctor = Class.forName("androidx.compose.ui.node.LayoutNode").getDeclaredConstructor()
    ctor.isAccessible = true
    return ctor.newInstance()
}

@Suppress("UNCHECKED_CAST")
private fun newUiApplier(root: Any): Applier<Any> {
    val ctor = Class.forName("androidx.compose.ui.platform.DefaultUiApplier").declaredConstructors.single()
    ctor.isAccessible = true
    return ctor.newInstance(root) as Applier<Any>
}

private fun countLeaves(node: Any): Int {
    val children = node.javaClass.getMethod("getChildren\$ui").invoke(node) as List<*>
    if (children.isEmpty()) return 1
    var total = 0
    for (child in children) total += countLeaves(child!!)
    return total
}

private fun recomposeAndApply(composition: Composition): Boolean {
    val recompose = composition.javaClass.getMethod("recompose")
    val apply = composition.javaClass.getMethod("applyChanges")
    val changed = recompose.invoke(composition) as Boolean
    if (changed) apply.invoke(composition)
    return changed
}

private fun recordWrite(composition: Composition, state: Any) {
    composition.javaClass.getMethod("recordWriteOf", Any::class.java).invoke(composition, state)
}

private fun observationDump(composition: Composition): String {
    val field = composition.javaClass.getDeclaredField("observations")
    field.isAccessible = true
    return field.get(composition).toString()
}

private fun List<Long>.medianNanosToMs(): String {
    val ordered = sorted()
    val mid = ordered[ordered.size / 2]
    return "%.1f".format(Locale.US, mid / 1_000_000.0)
}

private object BenchViewConfiguration : ViewConfiguration {
    override val longPressTimeoutMillis: Long = 500
    override val doubleTapTimeoutMillis: Long = 300
    override val doubleTapMinTimeMillis: Long = 40
    override val touchSlop: Float = 8f
}
