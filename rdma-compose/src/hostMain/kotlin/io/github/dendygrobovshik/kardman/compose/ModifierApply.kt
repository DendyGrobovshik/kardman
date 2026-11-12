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

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowColumnScope
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush as ComposeBrush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor as ComposeSolidColor
import androidx.compose.ui.graphics.TileMode as ComposeTileMode
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp as ComposeDp
import androidx.compose.foundation.layout.IntrinsicSize as ComposeIntrinsicSize
import androidx.compose.ui.Alignment as ComposeAlignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape as ComposeRectangleShape
import androidx.compose.ui.graphics.Shape as ComposeShape
import androidx.compose.foundation.layout.PaddingValues as ComposePaddingValues
import androidx.compose.ui.geometry.Offset as ComposeOffset
import io.github.dendygrobovshik.kardman.compose.Modifier as Chain

/**
 * Receives [Clickable.actionId] when the applied modifier is clicked.
 * The id is the value the guest put on the chain (compose-live does the same
 * and routes it through a click-action holder).
 */
fun interface ModifierClickHandler {
    fun onClick(actionId: Int)
}

val LocalModifierClickHandler = compositionLocalOf<ModifierClickHandler> {
    ModifierClickHandler { }
}

private val LocalActiveModifierScope = compositionLocalOf<ActiveModifierScope?> { null }

private sealed interface ActiveModifierScope {
    val kind: ModifierScope

    class InColumn(val scope: ColumnScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.Column
    }

    class InRow(val scope: RowScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.Row
    }

    class InBox(val scope: BoxScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.Box
    }

    class InAsyncBox(val scope: BoxScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.AsyncBox
    }

    class InFlowColumn(val scope: FlowColumnScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.FlowColumn
    }

    class InFlowRow(val scope: FlowRowScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.FlowRow
    }

    class InLazyGrid(val scope: LazyGridItemScope) : ActiveModifierScope {
        override val kind: ModifierScope get() = ModifierScope.LazyGridItem
    }
}

@Composable
internal fun ColumnScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InColumn(this), content)
}

@Composable
internal fun RowScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InRow(this), content)
}

@Composable
internal fun BoxScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InBox(this), content)
}

@Composable
internal fun BoxScope.provideAsyncBoxModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InAsyncBox(this), content)
}

@Composable
internal fun FlowColumnScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InFlowColumn(this), content)
}

@Composable
internal fun FlowRowScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InFlowRow(this), content)
}

@Composable
internal fun LazyGridItemScope.provideModifierScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActiveModifierScope provides ActiveModifierScope.InLazyGrid(this), content)
}

/**
 * Applies [this] chain to a Compose modifier, outer element first.
 * Scope-only elements are skipped unless the matching [provideModifierScope]
 * is active. [Shimmer] and [Reuse] stay on the chain for the guest but have
 * no Compose effect here (shimmer needs a library this module does not ship;
 * reuse is a host marker). [StickyHeader] is likewise a marker for a lazy grid.
 */
@Composable
internal fun Chain.toComposeUi(): Modifier {
    val scope = LocalActiveModifierScope.current
    var acc: Modifier = Modifier
    for (element in elementsFor(scope?.kind)) {
        acc = applyElement(acc, element, scope)
    }
    return acc
}

@Composable
private fun applyElement(
    modifier: Modifier,
    element: ModifierElement,
    scope: ActiveModifierScope?,
): Modifier = when (element) {
    is Padding -> modifier.padding(element.values.toPadding())
    is Width -> modifier.width(element.width.toDp())
    is Height -> modifier.height(element.height.toDp())
    is FillMaxWidth -> modifier.fillMaxWidth()
    is FillMaxHeight -> modifier.fillMaxHeight()
    is AspectRatio -> modifier.aspectRatio(element.ratio.toFloat())
    is Background -> modifier.background(color = element.color.toColor(), shape = element.shape.toShape())
    is BrushBackground -> modifier.background(
        brush = element.brush.toBrush(),
        shape = element.shape.toShape(),
    )
    is Clip -> modifier.clip(element.shape.toShape())
    is Alpha -> modifier.alpha(element.value.toFloat())
    is DefaultMinSize -> modifier.defaultMinSize(
        minWidth = element.minWidth.toDp(),
        minHeight = element.minHeight.toDp(),
    )
    is WrapContentHeight -> modifier.wrapContentHeight(
        align = element.align.toVertical(),
        unbounded = element.unbounded,
    )
    is LayoutId -> modifier.layoutId(element.idValue)
    is HorizontalScroll -> modifier.horizontalScroll(rememberScrollState(initial = element.initial))
    is Clickable -> {
        val handler = LocalModifierClickHandler.current
        val interaction = remember { MutableInteractionSource() }
        modifier.clickable(
            enabled = element.enabled,
            interactionSource = interaction,
            indication = if (element.indication == Indication.None) null else LocalIndication.current,
            onClick = { handler.onClick(element.actionId) },
        )
    }
    is IntrinsicWidth -> modifier.width(element.size.toIntrinsic())
    is IntrinsicHeight -> modifier.height(element.size.toIntrinsic())
    is StatusBarPadding -> modifier.statusBarsPadding()
    is OffsetElement -> modifier.offset(x = element.x.toDp(), y = element.y.toDp())
    is TestTag -> modifier.testTag(element.tag)
    is AlignHorizontally -> when (scope) {
        is ActiveModifierScope.InColumn -> with(scope.scope) { modifier.align(element.alignment.toHorizontal()) }
        is ActiveModifierScope.InFlowColumn -> with(scope.scope) { modifier.align(element.alignment.toHorizontal()) }
        else -> modifier
    }
    is AlignVertically -> when (scope) {
        is ActiveModifierScope.InRow -> with(scope.scope) { modifier.align(element.alignment.toVertical()) }
        is ActiveModifierScope.InFlowRow -> with(scope.scope) { modifier.align(element.alignment.toVertical()) }
        else -> modifier
    }
    is Align -> when (scope) {
        is ActiveModifierScope.InBox -> with(scope.scope) { modifier.align(element.alignment.toAlignment()) }
        is ActiveModifierScope.InAsyncBox -> with(scope.scope) { modifier.align(element.alignment.toAlignment()) }
        else -> modifier
    }
    is Weight -> when (scope) {
        is ActiveModifierScope.InColumn -> with(scope.scope) { modifier.weight(element.value.toFloat()) }
        is ActiveModifierScope.InRow -> with(scope.scope) { modifier.weight(element.value.toFloat()) }
        is ActiveModifierScope.InFlowColumn -> with(scope.scope) { modifier.weight(element.value.toFloat()) }
        is ActiveModifierScope.InFlowRow -> with(scope.scope) { modifier.weight(element.value.toFloat()) }
        else -> modifier
    }
    Shimmer, StickyHeader, Reuse -> modifier
}

private fun Dp.toDp(): ComposeDp =
    if (this == Dp.Unspecified) ComposeDp.Unspecified else ComposeDp(value.toFloat())

private fun Color.toColor(): ComposeColor =
    if (this == Color.Unspecified) ComposeColor.Unspecified else ComposeColor(value.toInt())

private fun PaddingValues.toPadding(): ComposePaddingValues = ComposePaddingValues(
    start = start.toDp(),
    top = top.toDp(),
    end = end.toDp(),
    bottom = bottom.toDp(),
)

private fun Alignment.toAlignment(): ComposeAlignment = when (ordinal) {
    Alignment.TopStart.ordinal -> ComposeAlignment.TopStart
    Alignment.TopCenter.ordinal -> ComposeAlignment.TopCenter
    Alignment.TopEnd.ordinal -> ComposeAlignment.TopEnd
    Alignment.CenterStart.ordinal -> ComposeAlignment.CenterStart
    Alignment.Center.ordinal -> ComposeAlignment.Center
    Alignment.CenterEnd.ordinal -> ComposeAlignment.CenterEnd
    Alignment.BottomStart.ordinal -> ComposeAlignment.BottomStart
    Alignment.BottomCenter.ordinal -> ComposeAlignment.BottomCenter
    Alignment.BottomEnd.ordinal -> ComposeAlignment.BottomEnd
    else -> ComposeAlignment.TopStart
}

private fun HorizontalAlignment.toHorizontal(): ComposeAlignment.Horizontal = when (ordinal) {
    HorizontalAlignment.Start.ordinal -> ComposeAlignment.Start
    HorizontalAlignment.CenterHorizontally.ordinal -> ComposeAlignment.CenterHorizontally
    HorizontalAlignment.End.ordinal -> ComposeAlignment.End
    else -> ComposeAlignment.Start
}

private fun VerticalAlignment.toVertical(): ComposeAlignment.Vertical = when (ordinal) {
    VerticalAlignment.Top.ordinal -> ComposeAlignment.Top
    VerticalAlignment.CenterVertically.ordinal -> ComposeAlignment.CenterVertically
    VerticalAlignment.Bottom.ordinal -> ComposeAlignment.Bottom
    else -> ComposeAlignment.Top
}

private fun Shape.toShape(): ComposeShape = when (kind) {
    SHAPE_ROUNDED -> RoundedCornerShape(
        topStart = ComposeDp(topStart.toFloat()),
        topEnd = ComposeDp(topEnd.toFloat()),
        bottomEnd = ComposeDp(bottomEnd.toFloat()),
        bottomStart = ComposeDp(bottomStart.toFloat()),
    )
    SHAPE_CIRCLE -> CircleShape
    else -> ComposeRectangleShape
}

private fun IntrinsicSize.toIntrinsic(): ComposeIntrinsicSize = when (ordinal) {
    IntrinsicSize.Max.ordinal -> ComposeIntrinsicSize.Max
    else -> ComposeIntrinsicSize.Min
}

private fun TileMode.toTileMode(): ComposeTileMode = when (ordinal) {
    TileMode.Repeated.ordinal -> ComposeTileMode.Repeated
    TileMode.Mirror.ordinal -> ComposeTileMode.Mirror
    TileMode.Decal.ordinal -> ComposeTileMode.Decal
    else -> ComposeTileMode.Clamp
}

private fun Offset.toOffset(): ComposeOffset = ComposeOffset(x, y)

private fun Offset.toRelativeOffset(size: Size): ComposeOffset {
    val point = toOffset()
    return ComposeOffset(x = size.width * point.x, y = size.height * point.y)
}

private fun Brush.toBrush(): ComposeBrush = when (kind) {
    BRUSH_SOLID -> ComposeSolidColor((color ?: Color.Unspecified).toColor())
    BRUSH_LINEAR -> if (stops.isNotEmpty()) {
        ComposeBrush.linearGradient(
            colorStops = stops.mapIndexed { index, stop -> stop to colors[index].toColor() }.toTypedArray(),
            start = start.toOffset(),
            end = end.toOffset(),
            tileMode = tileMode.toTileMode(),
        )
    } else {
        ComposeBrush.linearGradient(
            colors = colors.map { it.toColor() },
            start = start.toOffset(),
            end = end.toOffset(),
            tileMode = tileMode.toTileMode(),
        )
    }
    BRUSH_RADIAL -> if (stops.isNotEmpty()) {
        ComposeBrush.radialGradient(
            colorStops = stops.mapIndexed { index, stop -> stop to colors[index].toColor() }.toTypedArray(),
            center = center.toOffset(),
            radius = radius,
            tileMode = tileMode.toTileMode(),
        )
    } else {
        ComposeBrush.radialGradient(
            colors = colors.map { it.toColor() },
            center = center.toOffset(),
            radius = radius,
            tileMode = tileMode.toTileMode(),
        )
    }
    BRUSH_RELATIVE_LINEAR -> object : ShaderBrush() {
        override fun createShader(size: Size): Shader = LinearGradientShader(
            colors = colors.map { it.toColor() },
            colorStops = stops.takeIf { it.isNotEmpty() },
            from = start.toRelativeOffset(size),
            to = end.toRelativeOffset(size),
            tileMode = tileMode.toTileMode(),
        )
    }
    else -> object : ShaderBrush() {
        override fun createShader(size: Size): Shader = RadialGradientShader(
            colors = colors.map { it.toColor() },
            colorStops = stops.takeIf { it.isNotEmpty() },
            center = center.toRelativeOffset(size),
            radius = (size.minDimension * 0.5f) * radius,
            tileMode = tileMode.toTileMode(),
        )
    }
}
