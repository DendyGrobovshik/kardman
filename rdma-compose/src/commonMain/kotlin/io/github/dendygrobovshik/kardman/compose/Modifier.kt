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

import io.github.dendygrobovshik.kardman.RDMA

/**
 * Compose modifier chain owned by the kernel.
 *
 * The plugin builds one the same way it builds any other `@RDMA` object:
 * `Modifier()` or `Modifier.Empty`, then a method per element (`padding`,
 * `width`, `testTag`, …). The object stays in kernel memory; the plugin holds
 * a handle. The host reads [elements] and applies them with `toComposeUi()`.
 *
 * Method names are unique and every parameter is required. That matches the
 * boundary: overloaded methods, extension functions, and default arguments
 * are not bridgeable. `padding`'s parameter order is start, end, top, bottom,
 * the same order as the compose-live DSL.
 *
 * Tag ids match compose-live `modifiers.kt`.
 */
@RDMA
class Modifier {
    private var parent: Modifier? = null
    private var element: ModifierElement? = null

    fun padding(start: Dp, end: Dp, top: Dp, bottom: Dp): Modifier =
        with(Padding(PaddingValues(start = start, top = top, end = end, bottom = bottom)))

    fun paddingAll(all: Dp): Modifier = padding(all, all, all, all)

    fun paddingSymmetric(horizontal: Dp, vertical: Dp): Modifier =
        padding(horizontal, horizontal, vertical, vertical)

    fun width(width: Dp): Modifier = with(Width(width))

    fun height(height: Dp): Modifier = with(Height(height))

    fun size(width: Dp, height: Dp): Modifier = width(width).height(height)

    fun intrinsicWidth(size: IntrinsicSize): Modifier = with(IntrinsicWidth(size))

    fun intrinsicHeight(size: IntrinsicSize): Modifier = with(IntrinsicHeight(size))

    fun intrinsicSize(width: IntrinsicSize, height: IntrinsicSize): Modifier =
        intrinsicWidth(width).intrinsicHeight(height)

    fun fillMaxWidth(): Modifier = with(FillMaxWidth)

    fun fillMaxHeight(): Modifier = with(FillMaxHeight)

    fun fillMaxSize(): Modifier = fillMaxWidth().fillMaxHeight()

    fun weight(weight: Double): Modifier = with(Weight(weight))

    fun align(alignment: Alignment): Modifier = with(Align(alignment))

    fun alignHorizontally(alignment: HorizontalAlignment): Modifier = with(AlignHorizontally(alignment))

    fun alignVertically(alignment: VerticalAlignment): Modifier = with(AlignVertically(alignment))

    fun aspectRatio(ratio: Double): Modifier = with(AspectRatio(ratio))

    fun background(color: Color, shape: Shape): Modifier = with(Background(color, shape))

    fun brushBackground(brush: Brush, shape: Shape): Modifier = with(BrushBackground(brush, shape))

    fun clip(shape: Shape): Modifier = with(Clip(shape))

    fun alpha(alpha: Double): Modifier = with(Alpha(alpha))

    fun defaultMinSize(minWidth: Dp, minHeight: Dp): Modifier = with(DefaultMinSize(minWidth, minHeight))

    fun wrapContentHeight(align: VerticalAlignment, unbounded: Boolean): Modifier =
        with(WrapContentHeight(align, unbounded))

    fun layoutId(id: String): Modifier = with(LayoutId(id))

    fun horizontalScroll(initial: Int, useNativeScroll: Boolean): Modifier =
        with(HorizontalScroll(initial, useNativeScroll))

    fun clickable(actionId: Int, enabled: Boolean, indication: Indication): Modifier =
        with(Clickable(actionId, enabled, indication))

    fun statusBarPadding(): Modifier = with(StatusBarPadding)

    fun offset(x: Dp, y: Dp): Modifier = with(OffsetElement(x, y))

    fun testTag(tag: String): Modifier = with(TestTag(tag))

    fun shimmer(): Modifier = with(Shimmer)

    fun stickyHeader(): Modifier = with(StickyHeader)

    fun reuse(): Modifier = with(Reuse)

    /** Appends [other]'s elements after this chain. An empty argument returns this chain. */
    fun then(other: Modifier): Modifier {
        var acc = this
        for (element in other.elements()) acc = acc.with(element)
        return acc
    }

    override fun equals(other: Any?): Boolean = other is Modifier && elements() == other.elements()

    override fun hashCode(): Int = elements().hashCode()

    override fun toString(): String = "Modifier(${elements().joinToString()})"

    internal fun elements(): List<ModifierElement> {
        val out = ArrayList<ModifierElement>()
        var current: Modifier? = this
        while (current != null) {
            val found = current.element
            if (found != null) out.add(found)
            current = current.parent
        }
        out.reverse()
        return out
    }

    internal fun elementsFor(scope: ModifierScope?): List<ModifierElement> =
        elements().filter { it.scopes.isEmpty() || (scope != null && scope in it.scopes) }

    private fun with(element: ModifierElement): Modifier {
        val created = Modifier()
        created.parent = this
        created.element = element
        return created
    }

    companion object {
        val Empty: Modifier = Modifier()
    }
}

internal object ModifierIds {
    const val PADDING: Int = 1
    const val ALIGN_HORIZONTALLY: Int = 2
    const val ALIGN_VERTICALLY: Int = 3
    const val ALIGN: Int = 4
    const val WIDTH: Int = 5
    const val HEIGHT: Int = 6
    const val WEIGHT: Int = 7
    const val FILL_MAX_WIDTH: Int = 8
    const val FILL_MAX_HEIGHT: Int = 9
    const val ASPECT_RATIO: Int = 10
    const val BACKGROUND: Int = 11
    const val CLIP: Int = 12
    const val STICKY_HEADER: Int = 13
    const val SHIMMER: Int = 14
    const val ALPHA: Int = 15
    const val DEFAULT_MIN_SIZE: Int = 16
    const val WRAP_CONTENT_HEIGHT: Int = 17
    const val LAYOUT_ID: Int = 18
    const val HORIZONTAL_SCROLL: Int = 19
    const val BRUSH_BACKGROUND: Int = 20
    const val CLICKABLE: Int = 21
    const val INTRINSIC_HEIGHT: Int = 22
    const val INTRINSIC_WIDTH: Int = 23
    const val STATUS_BAR_PADDING: Int = 24
    const val OFFSET: Int = 25
    const val TEST_TAG: Int = 26
    const val REUSE: Int = -4543827
}

internal enum class ModifierScope {
    Column,
    Row,
    Box,
    AsyncBox,
    FlowColumn,
    FlowRow,
    LazyGridItem,
}

internal sealed interface ModifierElement {
    val id: Int
    val scopes: Set<ModifierScope>
}

internal data class Padding(val values: PaddingValues) : ModifierElement {
    override val id: Int get() = ModifierIds.PADDING
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class AlignHorizontally(val alignment: HorizontalAlignment) : ModifierElement {
    override val id: Int get() = ModifierIds.ALIGN_HORIZONTALLY
    override val scopes: Set<ModifierScope> get() = setOf(ModifierScope.Column, ModifierScope.FlowColumn)
}

internal data class AlignVertically(val alignment: VerticalAlignment) : ModifierElement {
    override val id: Int get() = ModifierIds.ALIGN_VERTICALLY
    override val scopes: Set<ModifierScope> get() = setOf(ModifierScope.Row, ModifierScope.FlowRow)
}

internal data class Align(val alignment: Alignment) : ModifierElement {
    override val id: Int get() = ModifierIds.ALIGN
    override val scopes: Set<ModifierScope> get() = setOf(ModifierScope.Box, ModifierScope.AsyncBox)
}

internal data class Width(val width: Dp) : ModifierElement {
    override val id: Int get() = ModifierIds.WIDTH
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Height(val height: Dp) : ModifierElement {
    override val id: Int get() = ModifierIds.HEIGHT
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Weight(val value: Double) : ModifierElement {
    override val id: Int get() = ModifierIds.WEIGHT
    override val scopes: Set<ModifierScope> get() = setOf(
        ModifierScope.Row,
        ModifierScope.FlowRow,
        ModifierScope.Column,
        ModifierScope.FlowColumn,
    )
}

internal data object FillMaxWidth : ModifierElement {
    override val id: Int get() = ModifierIds.FILL_MAX_WIDTH
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data object FillMaxHeight : ModifierElement {
    override val id: Int get() = ModifierIds.FILL_MAX_HEIGHT
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class AspectRatio(val ratio: Double) : ModifierElement {
    override val id: Int get() = ModifierIds.ASPECT_RATIO
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Background(val color: Color, val shape: Shape) : ModifierElement {
    override val id: Int get() = ModifierIds.BACKGROUND
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Clip(val shape: Shape) : ModifierElement {
    override val id: Int get() = ModifierIds.CLIP
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data object StickyHeader : ModifierElement {
    override val id: Int get() = ModifierIds.STICKY_HEADER
    override val scopes: Set<ModifierScope> get() = setOf(ModifierScope.LazyGridItem)
}

internal data object Shimmer : ModifierElement {
    override val id: Int get() = ModifierIds.SHIMMER
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Alpha(val value: Double) : ModifierElement {
    override val id: Int get() = ModifierIds.ALPHA
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class DefaultMinSize(val minWidth: Dp, val minHeight: Dp) : ModifierElement {
    override val id: Int get() = ModifierIds.DEFAULT_MIN_SIZE
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class WrapContentHeight(val align: VerticalAlignment, val unbounded: Boolean) : ModifierElement {
    override val id: Int get() = ModifierIds.WRAP_CONTENT_HEIGHT
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class LayoutId(val idValue: String) : ModifierElement {
    override val id: Int get() = ModifierIds.LAYOUT_ID
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class HorizontalScroll(val initial: Int, val useNativeScroll: Boolean) : ModifierElement {
    override val id: Int get() = ModifierIds.HORIZONTAL_SCROLL
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class BrushBackground(val brush: Brush, val shape: Shape) : ModifierElement {
    override val id: Int get() = ModifierIds.BRUSH_BACKGROUND
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class Clickable(val actionId: Int, val enabled: Boolean, val indication: Indication) : ModifierElement {
    override val id: Int get() = ModifierIds.CLICKABLE
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class IntrinsicHeight(val size: IntrinsicSize) : ModifierElement {
    override val id: Int get() = ModifierIds.INTRINSIC_HEIGHT
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class IntrinsicWidth(val size: IntrinsicSize) : ModifierElement {
    override val id: Int get() = ModifierIds.INTRINSIC_WIDTH
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data object StatusBarPadding : ModifierElement {
    override val id: Int get() = ModifierIds.STATUS_BAR_PADDING
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class OffsetElement(val x: Dp, val y: Dp) : ModifierElement {
    override val id: Int get() = ModifierIds.OFFSET
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data class TestTag(val tag: String) : ModifierElement {
    override val id: Int get() = ModifierIds.TEST_TAG
    override val scopes: Set<ModifierScope> get() = emptySet()
}

internal data object Reuse : ModifierElement {
    override val id: Int get() = ModifierIds.REUSE
    override val scopes: Set<ModifierScope> get() = emptySet()
}
