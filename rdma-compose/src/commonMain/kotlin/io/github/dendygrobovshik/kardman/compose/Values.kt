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
 * Density-independent pixels. [Unspecified] is `Int.MAX_VALUE`, matching compose-live.
 * The plugin constructs a value with `Dp(16.0)` and reads the companion `Dp.Unspecified`.
 */
@RDMA
class Dp(val value: Double) {
    override fun equals(other: Any?): Boolean = other is Dp && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = if (this == Unspecified) "Dp.Unspecified" else "Dp($value)"

    companion object {
        val Unspecified: Dp = Dp(Int.MAX_VALUE.toDouble())
    }
}

/** ARGB color packed in a [Long]. [Unspecified] is `Int.MAX_VALUE.toLong()`. */
@RDMA
class Color(val value: Long) {
    override fun equals(other: Any?): Boolean = other is Color && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "Color($value)"

    companion object {
        val Unspecified: Color = Color(Int.MAX_VALUE.toLong())
        val Black: Color = Color(0xFF000000)
        val White: Color = Color(0xFFFFFFFF)
        val Red: Color = Color(0xFFFF0000)
        val Green: Color = Color(0xFF00FF00)
        val Blue: Color = Color(0xFF0000FF)
        val Transparent: Color = Color(0x00000000)
    }
}

/** 2D alignment. Ordinals 0–8 match compose-live `Alignment`. */
@RDMA
class Alignment internal constructor(val ordinal: Int) {
    override fun equals(other: Any?): Boolean = other is Alignment && other.ordinal == ordinal
    override fun hashCode(): Int = ordinal
    override fun toString(): String = "Alignment($ordinal)"

    companion object {
        val TopStart: Alignment = Alignment(0)
        val TopCenter: Alignment = Alignment(1)
        val TopEnd: Alignment = Alignment(2)
        val CenterStart: Alignment = Alignment(3)
        val Center: Alignment = Alignment(4)
        val CenterEnd: Alignment = Alignment(5)
        val BottomStart: Alignment = Alignment(6)
        val BottomCenter: Alignment = Alignment(7)
        val BottomEnd: Alignment = Alignment(8)
    }
}

/** Horizontal alignment. Ordinals 0–2 match compose-live. */
@RDMA
class HorizontalAlignment internal constructor(val ordinal: Int) {
    override fun equals(other: Any?): Boolean = other is HorizontalAlignment && other.ordinal == ordinal
    override fun hashCode(): Int = ordinal
    override fun toString(): String = "HorizontalAlignment($ordinal)"

    companion object {
        val Start: HorizontalAlignment = HorizontalAlignment(0)
        val CenterHorizontally: HorizontalAlignment = HorizontalAlignment(1)
        val End: HorizontalAlignment = HorizontalAlignment(2)
    }
}

/** Vertical alignment. Ordinals 0–2 match compose-live. */
@RDMA
class VerticalAlignment internal constructor(val ordinal: Int) {
    override fun equals(other: Any?): Boolean = other is VerticalAlignment && other.ordinal == ordinal
    override fun hashCode(): Int = ordinal
    override fun toString(): String = "VerticalAlignment($ordinal)"

    companion object {
        val Top: VerticalAlignment = VerticalAlignment(0)
        val CenterVertically: VerticalAlignment = VerticalAlignment(1)
        val Bottom: VerticalAlignment = VerticalAlignment(2)
    }
}

internal const val SHAPE_RECTANGLE: Int = 0
internal const val SHAPE_CIRCLE: Int = 1
internal const val SHAPE_ROUNDED: Int = 2

/**
 * Outline used by background and clip. [Rectangle] and [Circle] are companion vals.
 * A rounded corner is [roundedCornerShape] or [roundedCornerShapeAll].
 */
@RDMA
class Shape internal constructor(
    val kind: Int,
    val topStart: Double,
    val topEnd: Double,
    val bottomEnd: Double,
    val bottomStart: Double,
) {
    override fun equals(other: Any?): Boolean =
        other is Shape &&
            kind == other.kind &&
            topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    companion object {
        val Rectangle: Shape = Shape(SHAPE_RECTANGLE, 0.0, 0.0, 0.0, 0.0)
        val Circle: Shape = Shape(SHAPE_CIRCLE, 0.0, 0.0, 0.0, 0.0)
    }
}

@RDMA
fun roundedCornerShapeAll(corner: Dp): Shape =
    roundedCornerShape(corner, corner, corner, corner)

@RDMA
fun roundedCornerShape(topStart: Dp, topEnd: Dp, bottomEnd: Dp, bottomStart: Dp): Shape =
    Shape(SHAPE_ROUNDED, topStart.value, topEnd.value, bottomEnd.value, bottomStart.value)

@RDMA
class Offset(val x: Float, val y: Float) {
    override fun equals(other: Any?): Boolean = other is Offset && x == other.x && y == other.y
    override fun hashCode(): Int = 31 * x.hashCode() + y.hashCode()
    override fun toString(): String = "Offset($x, $y)"

    companion object {
        val Zero: Offset = Offset(0f, 0f)
    }
}

/** Gradient tile mode. Ordinals match Compose `TileMode`. */
@RDMA
class TileMode internal constructor(val ordinal: Int) {
    override fun equals(other: Any?): Boolean = other is TileMode && ordinal == other.ordinal
    override fun hashCode(): Int = ordinal

    companion object {
        val Clamp: TileMode = TileMode(0)
        val Repeated: TileMode = TileMode(1)
        val Mirror: TileMode = TileMode(2)
        val Decal: TileMode = TileMode(3)
    }
}

internal const val BRUSH_SOLID: Int = 0
internal const val BRUSH_LINEAR: Int = 1
internal const val BRUSH_RADIAL: Int = 2
internal const val BRUSH_RELATIVE_LINEAR: Int = 3
internal const val BRUSH_RELATIVE_RADIAL: Int = 4

/**
 * Paint for [Modifier.brushBackground]. Built with [solidColor], [linearGradient],
 * [radialGradient], [relativeLinearGradient], or [relativeRadialGradient].
 * An empty [stops] list means the colors are spaced evenly.
 */
@RDMA
class Brush internal constructor(
    internal val kind: Int,
    internal val color: Color?,
    internal val colors: List<Color>,
    internal val stops: List<Float>,
    internal val start: Offset,
    internal val end: Offset,
    internal val center: Offset,
    internal val radius: Float,
    internal val tileMode: TileMode,
) {
    override fun equals(other: Any?): Boolean =
        other is Brush &&
            kind == other.kind &&
            color == other.color &&
            colors == other.colors &&
            stops == other.stops &&
            start == other.start &&
            end == other.end &&
            center == other.center &&
            radius == other.radius &&
            tileMode == other.tileMode

    override fun hashCode(): Int {
        var result = kind
        result = 31 * result + (color?.hashCode() ?: 0)
        result = 31 * result + colors.hashCode()
        result = 31 * result + stops.hashCode()
        result = 31 * result + start.hashCode()
        result = 31 * result + end.hashCode()
        result = 31 * result + center.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + tileMode.hashCode()
        return result
    }
}

private fun brush(
    kind: Int,
    color: Color? = null,
    colors: List<Color> = emptyList(),
    stops: List<Float> = emptyList(),
    start: Offset = Offset.Zero,
    end: Offset = Offset.Zero,
    center: Offset = Offset.Zero,
    radius: Float = 0f,
    tileMode: TileMode = TileMode.Clamp,
): Brush = Brush(kind, color, colors, stops, start, end, center, radius, tileMode)

@RDMA
fun solidColor(color: Color): Brush = brush(BRUSH_SOLID, color = color)

@RDMA
fun linearGradient(
    colors: List<Color>,
    stops: List<Float>,
    start: Offset,
    end: Offset,
    tileMode: TileMode,
): Brush = brush(
    BRUSH_LINEAR,
    colors = colors,
    stops = stops,
    start = start,
    end = end,
    tileMode = tileMode,
)

@RDMA
fun radialGradient(
    colors: List<Color>,
    stops: List<Float>,
    center: Offset,
    radius: Float,
    tileMode: TileMode,
): Brush = brush(
    BRUSH_RADIAL,
    colors = colors,
    stops = stops,
    center = center,
    radius = radius,
    tileMode = tileMode,
)

@RDMA
fun relativeLinearGradient(
    colors: List<Color>,
    stops: List<Float>,
    start: Offset,
    end: Offset,
    tileMode: TileMode,
): Brush = brush(
    BRUSH_RELATIVE_LINEAR,
    colors = colors,
    stops = stops,
    start = start,
    end = end,
    tileMode = tileMode,
)

@RDMA
fun relativeRadialGradient(
    colors: List<Color>,
    stops: List<Float>,
    center: Offset,
    radius: Float,
    tileMode: TileMode,
): Brush = brush(
    BRUSH_RELATIVE_RADIAL,
    colors = colors,
    stops = stops,
    center = center,
    radius = radius,
    tileMode = tileMode,
)

/** Click indication. `0` is none, `1` is the host default. */
@RDMA
class Indication internal constructor(val value: Int) {
    override fun equals(other: Any?): Boolean = other is Indication && value == other.value
    override fun hashCode(): Int = value

    companion object {
        val None: Indication = Indication(0)
        val Default: Indication = Indication(1)
    }
}

/** Min/Max for [Modifier.intrinsicWidth] and [Modifier.intrinsicHeight]. */
@RDMA
class IntrinsicSize internal constructor(val ordinal: Int) {
    override fun equals(other: Any?): Boolean = other is IntrinsicSize && ordinal == other.ordinal
    override fun hashCode(): Int = ordinal

    companion object {
        val Min: IntrinsicSize = IntrinsicSize(0)
        val Max: IntrinsicSize = IntrinsicSize(1)
    }
}

internal data class PaddingValues(val start: Dp, val top: Dp, val end: Dp, val bottom: Dp) {
    constructor(all: Dp) : this(all, all, all, all)
    constructor(horizontal: Dp, vertical: Dp) : this(horizontal, vertical, horizontal, vertical)
}
