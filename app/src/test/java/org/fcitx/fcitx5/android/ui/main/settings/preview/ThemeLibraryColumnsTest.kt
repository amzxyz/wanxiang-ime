/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.preview

import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeLibraryColumns
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeLibraryPanel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemeLibraryColumnsTest {
    @Test fun eitherListCollapsesBothHeadersAndReleasesTheirHeight() {
        val context = RuntimeEnvironment.getApplication()
        val columns = ThemeLibraryColumns(context)
        val firstHeader = View(context)
        val secondHeader = View(context)
        val firstList = object : View(context) {
            var scrolled = false
            override fun canScrollVertically(direction: Int) = direction < 0 && scrolled
        }
        val secondList = View(context)
        val first = ThemeLibraryPanel(context, firstHeader, firstList)
        val second = ThemeLibraryPanel(context, secondHeader, secondList)
        columns.addView(first, LinearLayout.LayoutParams(0, -1, 2f))
        columns.addView(second, LinearLayout.LayoutParams(0, -1, 1f))
        val full = first.getChildAt(0).layoutParams.height
        val type = ViewCompat.TYPE_TOUCH
        val consumed = IntArray(2)
        fun layout() {
            columns.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            columns.layout(0, 0, 600, 800)
        }
        layout()
        val initialHeight = firstList.height
        assertFalse(columns.onStartNestedScroll(first, firstList, ViewCompat.SCROLL_AXIS_HORIZONTAL, type))
        assertTrue(columns.onStartNestedScroll(first, firstList, ViewCompat.SCROLL_AXIS_VERTICAL, type))
        columns.onNestedScrollAccepted(first, firstList, ViewCompat.SCROLL_AXIS_VERTICAL, type)
        columns.onNestedPreScroll(firstList, 0, full / 2, consumed, type)
        assertEquals(full / 2, consumed[1])
        assertEquals(firstHeader.alpha, secondHeader.alpha, 0f)
        assertTrue(firstHeader.alpha in 0.01f..0.99f)
        layout()
        assertEquals(initialHeight + full / 2, firstList.height)
        assertEquals(firstList.height, secondList.height)

        consumed.fill(0)
        columns.onNestedPreScroll(secondList, 0, full, consumed, type)
        assertEquals(full - full / 2, consumed[1])
        assertEquals(0f, firstHeader.alpha, 0f)
        assertEquals(View.INVISIBLE, secondHeader.visibility)
        layout()
        assertEquals(initialHeight + full, firstList.height)

        // Downward scroll belongs to the active list until its top is reached.
        firstList.scrolled = true
        consumed.fill(0)
        columns.onNestedPreScroll(firstList, 0, -full, consumed, type)
        assertEquals(0, consumed[1])
        assertEquals(0f, firstHeader.alpha, 0f)
        firstList.scrolled = false
        columns.onNestedScroll(firstList, 0, -10, 0, -full, type, consumed)
        assertEquals(-full, consumed[1])
        assertEquals(1f, firstHeader.alpha, 0f)
        assertEquals(1f, secondHeader.alpha, 0f)
        assertEquals(View.VISIBLE, secondHeader.visibility)
        layout()
        assertEquals(initialHeight, firstList.height)
    }
}
