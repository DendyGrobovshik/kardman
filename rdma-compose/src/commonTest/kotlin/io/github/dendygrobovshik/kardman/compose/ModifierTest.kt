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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierTest {

    @Test
    fun `empty constructor and companion are the same chain`() {
        assertTrue(Modifier().elements().isEmpty())
        assertTrue(Modifier.Empty.elements().isEmpty())
        assertEquals(Modifier(), Modifier.Empty)
        assertEquals(Modifier.Empty, Modifier.Empty.then(Modifier()))
    }

    @Test
    fun `then keeps outer to inner order`() {
        val chain = Modifier.Empty
            .paddingAll(Dp(1.0))
            .background(Color.Red, Shape.Rectangle)
            .testTag("title")
        assertEquals(
            listOf(ModifierIds.PADDING, ModifierIds.BACKGROUND, ModifierIds.TEST_TAG),
            chain.elements().map { it.id },
        )
        val padding = chain.elements()[0] as Padding
        assertEquals(PaddingValues(Dp(1.0)), padding.values)
        assertEquals("title", (chain.elements()[2] as TestTag).tag)
    }

    @Test
    fun `padding methods match compose-live edges`() {
        assertEquals(
            PaddingValues(Dp(16.0)),
            (Modifier.Empty.paddingAll(Dp(16.0)).elements().single() as Padding).values,
        )
        assertEquals(
            PaddingValues(horizontal = Dp(8.0), vertical = Dp(4.0)),
            (Modifier.Empty.paddingSymmetric(Dp(8.0), Dp(4.0)).elements().single() as Padding).values,
        )
        assertEquals(
            PaddingValues(start = Dp(1.0), top = Dp(2.0), end = Dp(3.0), bottom = Dp(4.0)),
            (Modifier.Empty.padding(Dp(1.0), Dp(3.0), Dp(2.0), Dp(4.0)).elements().single() as Padding).values,
        )
    }

    @Test
    fun `size helpers expand to width and height`() {
        assertEquals(
            listOf(ModifierIds.WIDTH, ModifierIds.HEIGHT),
            Modifier.Empty.size(Dp(12.0), Dp(12.0)).elements().map { it.id },
        )
        assertEquals(
            listOf(ModifierIds.FILL_MAX_WIDTH, ModifierIds.FILL_MAX_HEIGHT),
            Modifier.Empty.fillMaxSize().elements().map { it.id },
        )
        assertEquals(
            listOf(ModifierIds.INTRINSIC_WIDTH, ModifierIds.INTRINSIC_HEIGHT),
            Modifier.Empty.intrinsicSize(IntrinsicSize.Min, IntrinsicSize.Min).elements().map { it.id },
        )
    }

    @Test
    fun `catalog tags match compose-live modifiers`() {
        val chain = Modifier.Empty
            .paddingAll(Dp(1.0))
            .alignHorizontally(HorizontalAlignment.CenterHorizontally)
            .alignVertically(VerticalAlignment.Bottom)
            .align(Alignment.Center)
            .width(Dp(2.0))
            .height(Dp(3.0))
            .weight(1.0)
            .fillMaxWidth()
            .fillMaxHeight()
            .aspectRatio(1.5)
            .background(Color.Blue, Shape.Circle)
            .clip(roundedCornerShapeAll(Dp(4.0)))
            .stickyHeader()
            .shimmer()
            .alpha(0.5)
            .defaultMinSize(Dp(8.0), Dp.Unspecified)
            .wrapContentHeight(VerticalAlignment.Top, true)
            .layoutId("slot")
            .horizontalScroll(12)
            .brushBackground(solidColor(Color.Red), Shape.Rectangle)
            .clickable(7, false, Indication.None)
            .intrinsicHeight(IntrinsicSize.Max)
            .intrinsicWidth(IntrinsicSize.Min)
            .statusBarPadding()
            .offset(Dp(5.0), Dp(6.0))
            .testTag("node")
            .reuse()

        assertEquals(
            listOf(
                ModifierIds.PADDING,
                ModifierIds.ALIGN_HORIZONTALLY,
                ModifierIds.ALIGN_VERTICALLY,
                ModifierIds.ALIGN,
                ModifierIds.WIDTH,
                ModifierIds.HEIGHT,
                ModifierIds.WEIGHT,
                ModifierIds.FILL_MAX_WIDTH,
                ModifierIds.FILL_MAX_HEIGHT,
                ModifierIds.ASPECT_RATIO,
                ModifierIds.BACKGROUND,
                ModifierIds.CLIP,
                ModifierIds.STICKY_HEADER,
                ModifierIds.SHIMMER,
                ModifierIds.ALPHA,
                ModifierIds.DEFAULT_MIN_SIZE,
                ModifierIds.WRAP_CONTENT_HEIGHT,
                ModifierIds.LAYOUT_ID,
                ModifierIds.HORIZONTAL_SCROLL,
                ModifierIds.BRUSH_BACKGROUND,
                ModifierIds.CLICKABLE,
                ModifierIds.INTRINSIC_HEIGHT,
                ModifierIds.INTRINSIC_WIDTH,
                ModifierIds.STATUS_BAR_PADDING,
                ModifierIds.OFFSET,
                ModifierIds.TEST_TAG,
                ModifierIds.REUSE,
            ),
            chain.elements().map { it.id },
        )
    }

    @Test
    fun `then copies brush corners and scroll`() {
        val brush = linearGradient(
            colors = listOf(Color.Red, Color.Blue),
            stops = listOf(0f, 1f),
            start = Offset.Zero,
            end = Offset(1f, 1f),
            tileMode = TileMode.Mirror,
        )
        val shape = roundedCornerShape(Dp(1.0), Dp(0.0), Dp(2.0), Dp(0.0))
        val original = Modifier.Empty
            .testTag("a\"b|c")
            .brushBackground(brush, shape)
            .clickable(3, true, Indication.Default)
            .defaultMinSize(Dp.Unspecified, Dp.Unspecified)
            .offset(Dp(0.0), Dp(0.0))
        val copy = Modifier.Empty.then(original)
        assertEquals(original, copy)
        val scroll = Modifier.Empty.horizontalScroll(4).elements().single() as HorizontalScroll
        assertEquals(4, scroll.initial)
        val painted = copy.elements()[1] as BrushBackground
        assertEquals(listOf(Color.Red, Color.Blue), painted.brush.colors)
        assertEquals(TileMode.Mirror, painted.brush.tileMode)
        assertEquals(1.0, painted.shape.topStart)
        assertEquals(2.0, painted.shape.bottomEnd)
    }

    @Test
    fun `scoped elements stay on the chain until the parent matches`() {
        val weight = Modifier.Empty.weight(1.0).elements().single()
        assertTrue(weight.scopes.contains(ModifierScope.Column))
        assertTrue(weight.scopes.contains(ModifierScope.Row))
        assertFalse(ModifierScope.Box in weight.scopes)
        assertEquals(
            listOf(ModifierIds.PADDING),
            Modifier.Empty.paddingAll(Dp(1.0)).weight(1.0).elementsFor(null).map { it.id },
        )
        assertEquals(
            listOf(ModifierIds.PADDING, ModifierIds.WEIGHT),
            Modifier.Empty.paddingAll(Dp(1.0)).weight(1.0).elementsFor(ModifierScope.Column).map { it.id },
        )
        assertEquals(
            emptyList(),
            Modifier.Empty.align(Alignment.Center).elementsFor(ModifierScope.Column).map { it.id },
        )
        assertEquals(
            listOf(ModifierIds.ALIGN),
            Modifier.Empty.align(Alignment.Center).elementsFor(ModifierScope.Box).map { it.id },
        )
        assertEquals(
            listOf(ModifierIds.STICKY_HEADER),
            Modifier.Empty.stickyHeader().elementsFor(ModifierScope.LazyGridItem).map { it.id },
        )
    }

    @Test
    fun `alignment and color constants match compose-live ordinals`() {
        assertEquals(0, Alignment.TopStart.ordinal)
        assertEquals(4, Alignment.Center.ordinal)
        assertEquals(8, Alignment.BottomEnd.ordinal)
        assertEquals(0, HorizontalAlignment.Start.ordinal)
        assertEquals(1, VerticalAlignment.CenterVertically.ordinal)
        assertEquals(0xFFFF0000, Color.Red.value)
        assertEquals(Int.MAX_VALUE.toLong(), Color.Unspecified.value)
        assertEquals(Int.MAX_VALUE.toDouble(), Dp.Unspecified.value)
        assertEquals(0, Indication.None.value)
        assertEquals(1, Indication.Default.value)
    }

    @Test
    fun `numeric elements keep the compose-live field types`() {
        assertEquals(1.5, (Modifier.Empty.weight(1.5).elements().single() as Weight).value)
        assertEquals(1.25, (Modifier.Empty.aspectRatio(1.25).elements().single() as AspectRatio).ratio)
        assertEquals(0.25, (Modifier.Empty.alpha(0.25).elements().single() as Alpha).value)
        assertTrue(Modifier.Empty.fillMaxWidth().elements().single() is FillMaxWidth)
        assertTrue(Modifier.Empty.fillMaxHeight().elements().single() is FillMaxHeight)
        val brush = Modifier.Empty.brushBackground(solidColor(Color.Red), Shape.Circle).elements().single() as BrushBackground
        assertEquals(Shape.Circle, brush.shape)
        assertEquals(Color.Red, brush.brush.color)
    }

}
