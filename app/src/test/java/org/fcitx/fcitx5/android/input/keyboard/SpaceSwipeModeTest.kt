/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.SwipeAxis
import org.junit.Assert.assertTrue
import org.junit.Test

/** Horizontal cursor movement and vertical layout macros are independent. */
class SpaceSwipeModeTest {

    private val action = MacroAction(
        listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("Left"))))
    )

    @Test
    fun cursorModeAndMacroCanCoexist() {
        assertTrue(
            "光标和布局宏可以同时启用",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = true, swipeAction = action)
        )
    }

    @Test
    fun swipeActionTakesOverWhenCursorModeDisabled() {
        assertTrue(
            "开关关闭且配了划动动作时，划动动作必须生效",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = false, swipeAction = action)
        )
    }

    @Test
    fun cursorModeAloneStillEnablesSwipe() {
        assertTrue(
            "开关开启但没有划动动作时光标手势仍要生效",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = true, swipeAction = null)
        )
    }

    @Test
    fun neitherModeLeavesSwipeDisabled() {
        assertFalse(
            "开关关闭且没有划动动作时空格键不参与手势，避免滑动取消长按（按住说话）",
            resolveSpaceSwipeEnabled(cursorSwipeEnabled = false, swipeAction = null)
        )
    }

    @Test fun axisUsesPhysicalDisplacementAndStaysLocked() {
        assertNull(resolveSpaceSwipeAxis(null, 1f, 2f, 0, 0))
        assertEquals(SwipeAxis.Y, resolveSpaceSwipeAxis(null, 12f, 30f, 2, 0))
        assertEquals(SwipeAxis.X, resolveSpaceSwipeAxis(null, 30f, 12f, 3, 1))
        assertEquals(SwipeAxis.X, resolveSpaceSwipeAxis(SwipeAxis.X, 1f, 50f, 0, 2))
        assertEquals(SwipeAxis.Y, resolveSpaceSwipeAxis(SwipeAxis.Y, 50f, 1f, 5, 0))
    }

    @Test fun onlyAnUnconsumedVerticalReleaseCanDispatchTheMacro() {
        val up = Event(GestureType.Up, false, 0f, 30f, 0, 0, 0, 1)
        assertTrue(shouldRunSpaceSwipeMacro(SwipeAxis.Y, action, up))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.X, action, up))
        assertFalse(shouldRunSpaceSwipeMacro(null, action, up))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.Y, null, up))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.Y, action, up.copy(totalY = 0)))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.Y, action, up.copy(consumed = true)))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.Y, action, up.copy(cancelled = true)))
        assertFalse(shouldRunSpaceSwipeMacro(SwipeAxis.Y, action, up.copy(longPressTriggered = true)))
    }
}
