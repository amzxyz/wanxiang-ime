/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor

/**
 * Both independent lists share the same collapsible heading space. Consume
 * vertical nested scroll only for the headings; all remaining pixels belong
 * to the list that the user is actually scrolling.
 */
internal class ThemeLibraryColumns(context: Context) : LinearLayout(context), NestedScrollingParent3 {
    private val nestedHelper = NestedScrollingParentHelper(this)
    private val headerHeight = dp(48)
    private var offset = 0
    private var direction = 0
    private var settle: ValueAnimator? = null

    private val dividerPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.settingsColor(com.google.android.material.R.attr.colorOutlineVariant)
            alpha = (alpha * 0.65f).toInt()
            strokeWidth = context.resources.displayMetrics.density * 0.8f
        }
    }

    init {
        orientation = HORIZONTAL
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount != 2) return
        // The separator lives in the real gap, not in either scrolling list.
        // Sort by physical position so the same drawing works in RTL.
        val first = getChildAt(0)
        val second = getChildAt(1)
        val left = if (first.left < second.left) first else second
        val right = if (left === first) second else first
        if (right.left <= left.right) return
        val x = (left.right + right.left) * 0.5f
        val end = height - paddingBottom - dp(8).toFloat()
        var y = paddingTop + dp(4).toFloat()
        val dash = dp(4).toFloat()
        val gap = dp(4).toFloat()
        while (y < end) {
            canvas.drawLine(x, y, x, minOf(y + dash, end), dividerPaint)
            y += dash + gap
        }
    }

    private fun moveHeaders(dy: Int): Int {
        val next = (offset + dy).coerceIn(0, headerHeight)
        val consumed = next - offset
        setOffset(next)
        return consumed
    }

    private fun setOffset(value: Int) {
        if (offset == value) return
        offset = value
        for (i in 0 until childCount) {
            (getChildAt(i) as? ThemeLibraryPanel)?.setHeaderOffset(offset)
        }
    }

    private fun settleTo(value: Int) {
        settle?.cancel()
        if (value == offset) return
        settle = ValueAnimator.ofInt(offset, value).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener { setOffset(it.animatedValue as Int) }
            start()
        }
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean =
        axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        nestedHelper.onNestedScrollAccepted(child, target, axes, type)
        settle?.cancel()
        direction = 0
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (dy == 0) return
        direction = dy
        if (dy > 0 || !target.canScrollVertically(-1)) {
            consumed[1] += moveHeaders(dy)
        }
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray
    ) {
        // A downward fling can reach the first item during this dispatch.
        // Let its unused distance restore the headings without another gesture.
        if (dyUnconsumed < 0 && !target.canScrollVertically(-1)) {
            direction = dyUnconsumed
            consumed[1] += moveHeaders(dyUnconsumed)
        }
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        nestedHelper.onStopNestedScroll(target, type)
        if (direction < 0 && !target.canScrollVertically(-1)) {
            settleTo(0)
        } else if (direction > 0 && offset in 1 until headerHeight) {
            settleTo(headerHeight)
        }
    }

    override fun getNestedScrollAxes(): Int = nestedHelper.nestedScrollAxes

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int
    ) = onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, IntArray(2))

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) =
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) =
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int
    ) = onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH)

    override fun onStopNestedScroll(target: View) = onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean) = false

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float) = false

    override fun onDetachedFromWindow() {
        settle?.cancel()
        settle = null
        super.onDetachedFromWindow()
    }
}
