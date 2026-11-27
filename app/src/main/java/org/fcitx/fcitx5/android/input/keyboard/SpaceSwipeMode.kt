/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import kotlin.math.abs
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.Event
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.SwipeAxis

/** Cursor movement and layout macros can coexist on different axes. */
internal fun resolveSpaceSwipeEnabled(
    cursorSwipeEnabled: Boolean,
    swipeAction: MacroAction?
): Boolean = cursorSwipeEnabled || swipeAction != null

/** Lock using actual displacement, independent of the per-axis step thresholds. */
internal fun resolveSpaceSwipeAxis(
    locked: SwipeAxis?,
    deltaX: Float,
    deltaY: Float,
    countX: Int,
    countY: Int
): SwipeAxis? = locked ?: if (countX != 0 || countY != 0) {
    if (abs(deltaX) >= abs(deltaY)) SwipeAxis.X else SwipeAxis.Y
} else null

/** A vertical layout macro is dispatched once at release, never after a cancelled or held press. */
internal fun shouldRunSpaceSwipeMacro(
    axis: SwipeAxis?,
    swipeAction: MacroAction?,
    event: Event
): Boolean = axis == SwipeAxis.Y && swipeAction != null && event.totalY != 0 &&
    !event.consumed && !event.cancelled && !event.longPressTriggered
