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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.dendygrobovshik.kardman.RDMA
import androidx.compose.ui.Modifier as UiModifier

/*
 * Compose modifiers for plugins, as an ordinary @RDMA class. This is a complete example meant
 * to be copied into your own kernel and extended: the framework has no modifier type of its own.
 *
 * - The plugin builds an immutable chain: `Modifier().padding(8.0).clickable { … }`. Every
 *   call returns a new chain; elements are data classes, so equal chains are equal and a
 *   widget receiving an equal chain is skipped (a memoized plugin lambda compares equal too).
 * - A widget applies the chain with `modifier.toComposeUi()` inside its own composition, so
 *   elements with state (`horizontalScroll`, the click interaction) keep it across
 *   recompositions.
 * - Scope elements (`weight`, `align…`) apply only inside the container they belong to
 *   (`Row`/`Column`/`Box` provide their scope); elsewhere they are ignored.
 * - Values are primitives and enums: dp as `Double`, colors as ARGB `Long` (0xAARRGGBB). An
 *   @RDMA value class (`Dp`, `Color`) would cost one more crossing per value.
 * - Method names are unique: @RDMA members cannot be overloaded.
 */
@RDMA
@Immutable
class Modifier {
    internal var elements: List<ModifierElement> = emptyList()
        private set

    fun padding(all: Double): Modifier = with(Padding(all, all, all, all))
    fun paddingSymmetric(horizontal: Double = 0.0, vertical: Double = 0.0): Modifier =
        with(Padding(horizontal, vertical, horizontal, vertical))
    fun paddingEach(start: Double = 0.0, top: Double = 0.0, end: Double = 0.0, bottom: Double = 0.0): Modifier =
        with(Padding(start, top, end, bottom))

    fun width(width: Double): Modifier = with(Width(width))
    fun height(height: Double): Modifier = with(Height(height))
    fun size(width: Double, height: Double): Modifier = with(Size(width, height))
    fun fillMaxWidth(fraction: Double = 1.0): Modifier = with(FillMaxWidth(fraction))
    fun fillMaxHeight(fraction: Double = 1.0): Modifier = with(FillMaxHeight(fraction))
    fun fillMaxSize(fraction: Double = 1.0): Modifier = with(FillMaxSize(fraction))
    fun aspectRatio(ratio: Double): Modifier = with(AspectRatio(ratio))
    fun offset(x: Double, y: Double): Modifier = with(Offset(x, y))

    fun background(argb: Long, cornerRadius: Double = 0.0): Modifier = with(Background(argb, cornerRadius))
    fun border(width: Double, argb: Long, cornerRadius: Double = 0.0): Modifier = with(Border(width, argb, cornerRadius))
    fun clip(cornerRadius: Double): Modifier = with(Clip(cornerRadius))
    fun clipCircle(): Modifier = with(ClipCircle)
    fun alpha(alpha: Double): Modifier = with(Alpha(alpha))

    fun clickable(onClick: () -> Unit): Modifier = with(Clickable(onClick))
    fun horizontalScroll(): Modifier = with(HorizontalScroll)
    fun verticalScroll(): Modifier = with(VerticalScroll)

    /** In a `Row` or `Column`: share the remaining space by [weight]. */
    fun weight(weight: Double, fill: Boolean = true): Modifier = with(Weight(weight, fill))
    /** In a `Box`: position inside the box. */
    fun align(alignment: BoxAlignment): Modifier = with(Align(alignment))
    /** In a `Column`: horizontal position of this child. */
    fun alignHorizontally(alignment: HorizontalAlignment): Modifier = with(AlignHorizontally(alignment))
    /** In a `Row`: vertical position of this child. */
    fun alignVertically(alignment: VerticalAlignment): Modifier = with(AlignVertically(alignment))

    fun testTag(tag: String): Modifier = with(TestTag(tag))

    /** This chain followed by [other]. */
    fun then(other: Modifier): Modifier = Modifier().also { it.elements = elements + other.elements }

    override fun equals(other: Any?): Boolean = other is Modifier && other.elements == elements
    override fun hashCode(): Int = elements.hashCode()

    private fun with(element: ModifierElement): Modifier = Modifier().also { it.elements = elements + element }
}

@RDMA
enum class BoxAlignment { TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd }

@RDMA
enum class HorizontalAlignment { Start, CenterHorizontally, End }

@RDMA
enum class VerticalAlignment { Top, CenterVertically, Bottom }

internal sealed interface ModifierElement
internal data class Padding(val start: Double, val top: Double, val end: Double, val bottom: Double) : ModifierElement
internal data class Width(val width: Double) : ModifierElement
internal data class Height(val height: Double) : ModifierElement
internal data class Size(val width: Double, val height: Double) : ModifierElement
internal data class FillMaxWidth(val fraction: Double) : ModifierElement
internal data class FillMaxHeight(val fraction: Double) : ModifierElement
internal data class FillMaxSize(val fraction: Double) : ModifierElement
internal data class AspectRatio(val ratio: Double) : ModifierElement
internal data class Offset(val x: Double, val y: Double) : ModifierElement
internal data class Background(val argb: Long, val cornerRadius: Double) : ModifierElement
internal data class Border(val width: Double, val argb: Long, val cornerRadius: Double) : ModifierElement
internal data class Clip(val cornerRadius: Double) : ModifierElement
internal data object ClipCircle : ModifierElement
internal data class Alpha(val alpha: Double) : ModifierElement
internal data class Clickable(val onClick: () -> Unit) : ModifierElement
internal data object HorizontalScroll : ModifierElement
internal data object VerticalScroll : ModifierElement
internal data class Weight(val weight: Double, val fill: Boolean) : ModifierElement
internal data class Align(val alignment: BoxAlignment) : ModifierElement
internal data class AlignHorizontally(val alignment: HorizontalAlignment) : ModifierElement
internal data class AlignVertically(val alignment: VerticalAlignment) : ModifierElement
internal data class TestTag(val tag: String) : ModifierElement

// The layout scope a container gives its content. Data classes: the scope objects Compose
// passes are stable, so an equal value does not invalidate the readers of the local.
internal sealed interface LayoutScope {
    data class InRow(val scope: RowScope) : LayoutScope
    data class InColumn(val scope: ColumnScope) : LayoutScope
    data class InBox(val scope: BoxScope) : LayoutScope
}

internal val LocalLayoutScope = compositionLocalOf<LayoutScope?> { null }

/** Gives [content] the container's scope, for the scope elements of its children. */
@Composable
internal fun ProvideLayoutScope(scope: LayoutScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutScope provides scope, content)
}

/** The Compose modifier for this chain; call it inside the widget that receives the chain. */
@Composable
internal fun Modifier.toComposeUi(): UiModifier {
    val scope = LocalLayoutScope.current
    var acc: UiModifier = UiModifier
    val seen = HashMap<ModifierElement, Int>()
    for (element in elements) {
        val occurrence = (seen[element] ?: 0) + 1
        seen[element] = occurrence
        // Keyed by value, not by index: an element inserted in front does not move the
        // state of the elements after it (scroll position, click interaction) to another one.
        key(element, occurrence) {
            acc = acc.apply(element, scope)
        }
    }
    return acc
}

@Composable
private fun UiModifier.apply(e: ModifierElement, scope: LayoutScope?): UiModifier = when (e) {
    is Padding -> padding(start = e.start.dp, top = e.top.dp, end = e.end.dp, bottom = e.bottom.dp)
    is Width -> width(e.width.dp)
    is Height -> height(e.height.dp)
    is Size -> size(e.width.dp, e.height.dp)
    is FillMaxWidth -> fillMaxWidth(e.fraction.toFloat())
    is FillMaxHeight -> fillMaxHeight(e.fraction.toFloat())
    is FillMaxSize -> fillMaxSize(e.fraction.toFloat())
    is AspectRatio -> aspectRatio(e.ratio.toFloat())
    is Offset -> offset(e.x.dp, e.y.dp)
    is Background -> background(Color(e.argb.toInt()), shapeOf(e.cornerRadius))
    is Border -> border(e.width.dp, Color(e.argb.toInt()), shapeOf(e.cornerRadius))
    is Clip -> clip(shapeOf(e.cornerRadius))
    ClipCircle -> clip(CircleShape)
    is Alpha -> alpha(e.alpha.toFloat())
    is Clickable -> clickable(onClick = e.onClick)
    HorizontalScroll -> horizontalScroll(rememberScrollState())
    VerticalScroll -> verticalScroll(rememberScrollState())
    is Weight -> when (scope) {
        is LayoutScope.InRow -> with(scope.scope) { weight(e.weight.toFloat(), e.fill) }
        is LayoutScope.InColumn -> with(scope.scope) { weight(e.weight.toFloat(), e.fill) }
        else -> this
    }
    is Align -> if (scope is LayoutScope.InBox) with(scope.scope) { align(e.alignment.toCompose()) } else this
    is AlignHorizontally -> if (scope is LayoutScope.InColumn) with(scope.scope) { align(e.alignment.toCompose()) } else this
    is AlignVertically -> if (scope is LayoutScope.InRow) with(scope.scope) { align(e.alignment.toCompose()) } else this
    is TestTag -> testTag(e.tag)
}

private fun shapeOf(cornerRadius: Double): Shape =
    if (cornerRadius == 0.0) RectangleShape else RoundedCornerShape(cornerRadius.dp)

private fun BoxAlignment.toCompose(): Alignment = when (this) {
    BoxAlignment.TopStart -> Alignment.TopStart
    BoxAlignment.TopCenter -> Alignment.TopCenter
    BoxAlignment.TopEnd -> Alignment.TopEnd
    BoxAlignment.CenterStart -> Alignment.CenterStart
    BoxAlignment.Center -> Alignment.Center
    BoxAlignment.CenterEnd -> Alignment.CenterEnd
    BoxAlignment.BottomStart -> Alignment.BottomStart
    BoxAlignment.BottomCenter -> Alignment.BottomCenter
    BoxAlignment.BottomEnd -> Alignment.BottomEnd
}

private fun HorizontalAlignment.toCompose(): Alignment.Horizontal = when (this) {
    HorizontalAlignment.Start -> Alignment.Start
    HorizontalAlignment.CenterHorizontally -> Alignment.CenterHorizontally
    HorizontalAlignment.End -> Alignment.End
}

private fun VerticalAlignment.toCompose(): Alignment.Vertical = when (this) {
    VerticalAlignment.Top -> Alignment.Top
    VerticalAlignment.CenterVertically -> Alignment.CenterVertically
    VerticalAlignment.Bottom -> Alignment.Bottom
}
