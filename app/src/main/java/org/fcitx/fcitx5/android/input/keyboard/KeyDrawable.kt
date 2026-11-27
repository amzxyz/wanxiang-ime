/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt

fun radiusDrawable(
    r: Float, @ColorInt
    color: Int = Color.WHITE
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

fun insetRadiusDrawable(
    hInset: Int,
    vInset: Int,
    r: Float = 0f,
    @ColorInt color: Int = Color.WHITE
): Drawable = InsetDrawable(
    radiusDrawable(r, color),
    hInset, vInset, hInset, vInset
)

fun pillDrawable(
    @ColorInt color: Int = Color.WHITE
): Drawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = Float.MAX_VALUE
}

fun insetPillDrawable(
    hInset: Int,
    vInset: Int,
    @ColorInt color: Int = Color.WHITE
): Drawable = InsetDrawable(pillDrawable(color), hInset, vInset, hInset, vInset)

/**
 * A hairline around the top and sides, derived from the original bottom shadow.
 * The contour is inside the original face bounds, preserving the key gaps.
 */
private class KeyFaceDrawable(
    @ColorInt faceColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int
) : GradientDrawable() {
    private val sideWidth = shadowWidth.coerceAtLeast(0) * 0.18f
    private val cornerWidth = shadowWidth.coerceAtLeast(0) * 0.10f
    private val edgeAlpha = if (Color.alpha(faceColor) == 0) 0 else Color.alpha(shadowColor)
    // A subpixel contour in the shadow's hue, not the full border mode.
    private val edgeColor = Color.rgb(
        Color.red(shadowColor) * 2 / 3,
        Color.green(shadowColor) * 2 / 3,
        Color.blue(shadowColor) * 2 / 3
    )
    private val shadowHue = shadowColor or 0xff000000.toInt()
    private val fadeLength = shadowWidth.coerceAtLeast(0) * 2f
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edgeColor
        style = Paint.Style.FILL
    }
    private val edgePath = Path()
    private val edgeBounds = Rect()

    init {
        setColor(faceColor)
        cornerRadius = radius
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (sideWidth <= 0f || edgeAlpha == 0 || alpha == 0) return
        if (edgeBounds != bounds) {
            edgeBounds.set(bounds)
            rebuildEdgePath()
        }
        edgePaint.alpha = edgeAlpha * alpha / 255
        canvas.drawPath(edgePath, edgePaint)
    }

    private fun rebuildEdgePath() {
        edgePath.rewind()
        edgePath.fillType = Path.FillType.EVEN_ODD
        edgePaint.shader = null
        val outer = RectF(edgeBounds)
        if (outer.width() <= 2f * sideWidth || outer.height() <= maxOf(2f * sideWidth, 2f)) return

        // A single filled ring: keep the native face outline and vary only
        // its inner contour. The corners and top stay at 0.1 of the shadow
        // width, the straight sides reach 0.18. No overlapping strokes.
        val radius = cornerRadius.coerceIn(0f, minOf(outer.width(), outer.height()) * 0.5f)
        edgePath.addRoundRect(outer, radius, radius, Path.Direction.CW)
        addInnerContour(outer, radius)

        // Keep the hairline alive through the lower tangent. The original
        // shadow starts showing alongside the curved face there; blend into
        // its exact hue before reducing opacity, rather than opening a pale
        // gap between two independently faded edges.
        val fadeEnd = outer.bottom - maxOf(1f, sideWidth)
        val fadeStart = (outer.bottom - maxOf(radius, fadeLength))
            .coerceIn(outer.top, fadeEnd - 0.01f)
        edgePaint.shader = LinearGradient(
            0f, fadeStart, 0f, fadeEnd,
            intArrayOf(
                edgeColor,
                edgeColor,
                shadowHue,
                (shadowHue and 0x00ffffff) or (0xb8 shl 24),
                shadowHue and 0x00ffffff
            ),
            floatArrayOf(0f, 0.18f, 0.52f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private fun addInnerContour(outer: RectF, radius: Float) {
        val inner = RectF(outer).apply { inset(cornerWidth, cornerWidth) }
        val r = (radius - cornerWidth).coerceAtLeast(0f)
        val top = inner.top + r
        val bottom = inner.bottom - r
        val transition = minOf(fadeLength, (bottom - top) * 0.5f)
        if (transition <= 0f) {
            // A horizontal pill or a very short key has no straight side to widen.
            edgePath.addRoundRect(inner, r, r, Path.Direction.CW)
            return
        }
        val left = outer.left + sideWidth
        val right = outer.right - sideWidth
        val d = r * 2f
        val third = transition / 3f
        // Lower inner arcs use an elliptical inset: their horizontal radius
        // matches the full side width, while the vertical radius keeps the
        // finer corner width. Both share the face's original corner center
        // and join the side tangentially, without thinning before the turn.
        val lowerRx = (radius - sideWidth).coerceAtLeast(0f)
        val lowerRy = r
        val hasLowerArc = lowerRx > 0f && lowerRy > 0f

        // The upper circular corners still ease into the straight sides
        // through cubic transitions with matching vertical tangents.
        edgePath.moveTo(inner.left + r, inner.top)
        edgePath.lineTo(inner.right - r, inner.top)
        if (r > 0f) edgePath.arcTo(inner.right - d, inner.top, inner.right, inner.top + d, -90f, 90f, false)
        edgePath.cubicTo(inner.right, top + third, right, top + 2f * third, right, top + transition)
        if (hasLowerArc) {
            edgePath.lineTo(right, bottom)
            edgePath.arcTo(right - 2f * lowerRx, inner.bottom - 2f * lowerRy,
                right, inner.bottom, 0f, 90f, false)
            edgePath.lineTo(left + lowerRx, inner.bottom)
            edgePath.arcTo(left, inner.bottom - 2f * lowerRy,
                left + 2f * lowerRx, inner.bottom, 90f, 90f, false)
        } else {
            // Square or subpixel-radius corners have no meaningful arc.
            edgePath.lineTo(right, bottom)
            edgePath.lineTo(right, inner.bottom)
            edgePath.lineTo(left, inner.bottom)
            edgePath.lineTo(left, bottom)
        }
        edgePath.lineTo(left, top + transition)
        edgePath.cubicTo(left, top + 2f * third, inner.left, top + third, inner.left, top)
        if (r > 0f) edgePath.arcTo(inner.left, inner.top, inner.left + d, inner.top + d, 180f, 90f, false)
        edgePath.close()
    }
}

fun shadowedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        radiusDrawable(radius, shadowColor),
        KeyFaceDrawable(bkgColor, shadowColor, radius, shadowWidth),
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, shadowColor)
        }
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun shadowedPillKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        pillDrawable(shadowColor),
        KeyFaceDrawable(bkgColor, shadowColor, Float.MAX_VALUE, shadowWidth),
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun borderedPillKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = Float.MAX_VALUE
            setColor(bkgColor)
            setStroke(strokeWidth, shadowColor)
        }
    )
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}
