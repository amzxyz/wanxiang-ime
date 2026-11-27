/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyFaceEdgeTest {
    private fun render(drawable: Drawable, scale: Int = 1): Bitmap =
        Bitmap.createBitmap(80 * scale, 60 * scale, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, 80, 60)
            val canvas = Canvas(it)
            canvas.scale(scale.toFloat(), scale.toFloat())
            drawable.draw(canvas)
        }

    private fun originalBackground(face: Int, shadow: Int, radius: Float): Bitmap =
        render(LayerDrawable(arrayOf(radiusDrawable(radius, shadow), radiusDrawable(radius, face))).apply {
            setLayerInset(0, 5, 6, 5, 2)
            setLayerInset(1, 5, 6, 5, 6)
        })

    private fun assertOriginalBottom(actual: Bitmap, original: Bitmap, startY: Int) {
        for (y in startY until actual.height) for (x in 0 until actual.width) {
            assertEquals("Original shadow/face pixel at ($x, $y)", original.getPixel(x, y), actual.getPixel(x, y))
        }
    }

    @Test fun hairlineInheritsShadowWithoutChangingFaceGeometry() {
        val shadow = Color.rgb(100, 120, 140)
        val drawable = shadowedKeyBackgroundDrawable(
            Color.WHITE, shadow, 8f, 4, 5, 6
        ) as LayerDrawable
        assertEquals(2, drawable.numberOfLayers)
        assertEquals(5, drawable.getLayerInsetLeft(1))
        assertEquals(5, drawable.getLayerInsetRight(1))
        assertEquals(6, drawable.getLayerInsetTop(1))
        assertEquals(6, drawable.getLayerInsetBottom(1))
        val bitmap = render(drawable)
        val left = bitmap.getPixel(5, 30)
        val right = bitmap.getPixel(74, 30)
        assertTrue(kotlin.math.abs(Color.red(left) - Color.red(right)) <= 2)
        assertTrue("Subpixel edge must stay lighter than an opaque outline", Color.red(left) in 101..254)
        assertTrue(Color.red(left) < Color.green(left))
        assertTrue(Color.green(left) < Color.blue(left))
        val top = Color.red(bitmap.getPixel(40, 6))
        assertTrue(top in 101..254)
        assertTrue("Straight sides should be more defined than the top", Color.red(left) < top)
        assertEquals(Color.WHITE, bitmap.getPixel(40, 30))
        assertEquals(shadow, bitmap.getPixel(40, 55))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(4, 30))
        val original = originalBackground(Color.WHITE, shadow, 8f)
        assertOriginalBottom(bitmap, original, 53)
    }

    @Test fun bothUpperArcsHaveAContinuousFineContourAtTypicalDensities() {
        val shadow = Color.rgb(137, 138, 141)
        // One to four physical pixels are typical rounded 1dp shadow widths.
        // Check the arcs themselves, not just the horizontal/vertical edges.
        for (shadowWidth in 1..4) {
            val radius = 12f
            val original = render(LayerDrawable(arrayOf(
                radiusDrawable(radius, shadow), radiusDrawable(radius, Color.WHITE)
            )).apply {
                setLayerInset(0, 5, 6, 5, 6 - shadowWidth)
                setLayerInset(1, 5, 6, 5, 6)
            })
            val bitmap = render(shadowedKeyBackgroundDrawable(
                Color.WHITE, shadow, radius, shadowWidth, 5, 6
            ))
            for (left in listOf(true, false)) {
                // Three disjoint bands along the upper quarter circle, excluding
                // the straight top and side. Both curved corners must be visible.
                val arcBands = listOf(7..9, 10..12, 13..15)
                for (band in arcBands) {
                    assertTrue("Upper arc missing at shadow width $shadowWidth, left=$left, band=$band",
                        band.any { y -> (6..15).any { x ->
                            val px = if (left) x else bitmap.width - 1 - x
                            Color.red(bitmap.getPixel(px, y)) < Color.red(original.getPixel(px, y))
                        } })
                }
            }
            assertEquals(Color.WHITE, bitmap.getPixel(40, 30))
            assertOriginalBottom(bitmap, original, 53)
        }
    }

    @Test fun sideWidthEasesInAfterTheUpperCorner() {
        val shadow = Color.rgb(137, 138, 141)
        fun background() = shadowedKeyBackgroundDrawable(
            Color.WHITE, shadow, 12f, 4, 5, 6
        )
        // Keep logical bounds and shadow width unchanged. Supersample the
        // existing subpixel geometry rather than requiring distinct 8-bit
        // coverage values at three individual device pixels.
        val scale = 16
        val bitmap = render(background(), scale)
        val contrast = 255f - Color.red(shadow) * 2 / 3
        fun widthAt(y: Int, right: Boolean): Float {
            val row = y * scale + scale / 2
            val deficit = (5 * scale until 7 * scale).sumOf { x ->
                val px = if (right) bitmap.width - 1 - x else x
                255 - Color.red(bitmap.getPixel(px, row))
            }
            return deficit / (contrast * scale)
        }
        for (right in listOf(false, true)) {
            val atTangent = widthAt(18, right)
            val inTransition = widthAt(22, right)
            val onSide = widthAt(26, right)
            assertEquals("Upper arc nominal width", 0.4f, atTangent, 0.08f)
            assertEquals("Straight side nominal width", 0.72f, onSide, 0.08f)
            assertTrue("Side grows smoothly: $atTangent, $inTransition, $onSide",
                atTangent < inTransition && inTransition < onSide)
        }
        assertEquals("Symmetric side transitions", widthAt(22, false), widthAt(22, true), 0.04f)
        // Also retain the unscaled pixel-for-pixel shadow comparison.
        assertOriginalBottom(render(background()), originalBackground(Color.WHITE, shadow, 12f), 53)
    }

    @Test fun lowerSideFadesIntoTheOriginalShadowWithoutAnAbruptEnd() {
        val shadow = Color.rgb(100, 120, 140)
        val bitmap = render(shadowedKeyBackgroundDrawable(
            Color.WHITE, shadow, 0f, 4, 5, 6
        ))
        val upper = Color.red(bitmap.getPixel(5, 40))
        val middle = Color.red(bitmap.getPixel(5, 48))
        val nearBottom = Color.red(bitmap.getPixel(5, 52))
        assertTrue("The contour should fade progressively",
            upper < middle && middle < nearBottom && nearBottom < 255)
        assertTrue(kotlin.math.abs(Color.red(bitmap.getPixel(5, 48)) - Color.red(bitmap.getPixel(74, 48))) <= 2)
        assertOriginalBottom(bitmap, originalBackground(Color.WHITE, shadow, 0f), 54)
    }

    @Test fun lowerTangentRetainsSideWidthBeforeJoiningShadow() {
        val shadow = Color.rgb(137, 138, 141)
        val bitmap = render(shadowedKeyBackgroundDrawable(
            Color.WHITE, shadow, 12f, 4, 5, 6
        ))
        // With a face bottom of 54 and radius 12, the tangent is y=42.
        // The full-width vertical line must not narrow just before it.
        val side = Color.red(bitmap.getPixel(5, 34))
        val nearTangent = Color.red(bitmap.getPixel(5, 40))
        assertTrue("The lower join must retain the straight-side definition",
            kotlin.math.abs(side - nearTangent) <= 3)
        assertTrue(kotlin.math.abs(nearTangent - Color.red(bitmap.getPixel(74, 40))) <= 3)
        val original = originalBackground(Color.WHITE, shadow, 12f)
        for (ys in listOf(43..46, 47..50)) {
            for (left in listOf(true, false)) {
                assertTrue("The lower corner must continue into the shadow on both sides: $ys",
                    ys.any { y -> (5..17).any { x ->
                        val px = if (left) x else bitmap.width - 1 - x
                        Color.red(bitmap.getPixel(px, y)) < Color.red(original.getPixel(px, y))
                    } })
            }
        }
        assertOriginalBottom(bitmap, original, 53)
    }

    @Test fun roundedLowerCornersContinuePastTheSideTangent() {
        val shadow = Color.rgb(100, 120, 140)
        val bitmap = render(shadowedKeyBackgroundDrawable(
            Color.WHITE, shadow, 8f, 4, 5, 6
        ))
        val original = originalBackground(Color.WHITE, shadow, 8f)
        assertTrue("The lower arc must not lose its contour at the side tangent",
            (47..50).any { y -> (5..12).any { x -> bitmap.getPixel(x, y) != original.getPixel(x, y) } })
        assertOriginalBottom(bitmap, original, 54)
    }

    @Test fun transparentShadowDoesNotIntroduceAContour() {
        val bitmap = render(shadowedKeyBackgroundDrawable(
            Color.WHITE, Color.TRANSPARENT, 8f, 4, 5, 6
        ))
        assertEquals(Color.WHITE, bitmap.getPixel(5, 30))
        assertEquals(Color.WHITE, bitmap.getPixel(74, 30))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(40, 55))
    }

    @Test fun pillUsesSameHairlineAndPreservesOriginalBottom() {
        val shadow = Color.rgb(100, 120, 140)
        val bitmap = render(shadowedPillKeyBackgroundDrawable(
            Color.WHITE, shadow, 4, 5, 6
        ))
        assertTrue(Color.red(bitmap.getPixel(5, 30)) in 101..254)
        assertTrue(Color.red(bitmap.getPixel(40, 6)) in 101..254)
        assertEquals(shadow, bitmap.getPixel(40, 55))
        assertOriginalBottom(bitmap, originalBackground(Color.WHITE, shadow, Float.MAX_VALUE), 53)
    }
}
