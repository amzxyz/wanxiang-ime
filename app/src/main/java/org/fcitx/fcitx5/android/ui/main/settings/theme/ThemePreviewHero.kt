/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import splitties.dimensions.dp
import kotlin.math.roundToInt

/**
 * Keep the original hero's height, but give the live keyboard more of its
 * internal space. Measure the heading before reclaiming any spare width.
 */
internal class ThemePreviewHero(
    context: Context,
    private val heading: View,
    private val preview: View,
    private val intrinsicSize: () -> Pair<Int, Int>
) : FrameLayout(context) {
    private var previewScale = 1f

    init {
        setPaddingRelative(dp(20), 0, dp(16), 0)
        addView(heading)
        addView(preview)
    }

    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.AT_MOST)
    private val unrestricted = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width - paddingStart - paddingEnd).coerceAtLeast(0)
        val (rawWidth, rawHeight) = intrinsicSize()
        val nativeWidth = rawWidth.coerceAtLeast(1)
        val nativeHeight = rawHeight.coerceAtLeast(1)

        // Match the old 1 : 1.65 allocation and 14dp vertical padding
        // when calculating the outer height. Enlarging the keyboard must
        // not move the tabs or reduce the list area.
        val oldAvailable = (contentWidth - dp(12)).coerceAtLeast(0)
        val oldHeadingWidth = (oldAvailable / 2.65f).toInt()
        val oldPreviewWidth = oldAvailable - oldHeadingWidth
        heading.measure(exact(oldHeadingWidth), unrestricted)
        val oldHeadingHeight = heading.measuredHeight
        val oldScale = minOf(1f, oldPreviewWidth.toFloat() / nativeWidth,
            dp(144).toFloat() / nativeHeight)
        val oldHeight = maxOf(oldHeadingHeight, (nativeHeight * oldScale).roundToInt()) + dp(14) * 2
        val height = resolveSize(oldHeight, heightMeasureSpec)

        // Reclaim at most 24dp from the heading, plus 4dp of the old gap.
        // Longer translations and large fonts keep the space they need.
        heading.measure(atMost(oldHeadingWidth), unrestricted)
        val desiredHeadingWidth = maxOf(heading.measuredWidth, oldHeadingWidth - dp(24))
        heading.measure(exact(desiredHeadingWidth), unrestricted)
        if (heading.measuredHeight > oldHeadingHeight) {
            heading.measure(exact(oldHeadingWidth), unrestricted)
        }
        val availableWidth = (contentWidth - heading.measuredWidth - dp(8)).coerceAtLeast(0)
        val availableHeight = minOf(dp(160), (height - dp(6) * 2).coerceAtLeast(0))
        previewScale = minOf(1f, availableWidth.toFloat() / nativeWidth,
            availableHeight.toFloat() / nativeHeight)
        preview.measure(exact(nativeWidth), exact(nativeHeight))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val headingX = if (rtl) width - paddingRight - heading.measuredWidth else paddingLeft
        val headingY = (height - heading.measuredHeight) / 2
        heading.layout(headingX, headingY,
            headingX + heading.measuredWidth, headingY + heading.measuredHeight)

        val w = preview.measuredWidth
        val h = preview.measuredHeight
        // Anchor the visible trailing edge; any enlargement grows toward
        // the title, with the same scale on both axes.
        val x = if (rtl) paddingLeft else width - paddingRight - (w * previewScale).roundToInt()
        val y = ((height - h * previewScale) / 2).roundToInt()
        preview.pivotX = 0f
        preview.pivotY = 0f
        preview.scaleX = previewScale
        preview.scaleY = previewScale
        preview.layout(x, y, x + w, y + h)
    }
}
