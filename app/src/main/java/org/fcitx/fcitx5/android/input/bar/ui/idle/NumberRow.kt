/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.annotation.SuppressLint
import android.content.Context
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.theme.Theme
import android.view.MotionEvent
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import timber.log.Timber
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class NumberRow private constructor(
    ctx: Context,
    theme: Theme,
    private val rowState: RowState
) : BaseKeyboard(ctx, theme, { rowState.layout }, { null }) {

    constructor(ctx: Context, theme: Theme) : this(ctx, theme, RowState())

    // BaseKeyboard builds its first rows during construction, so keep the provider state
    // outside the subclass's initialization order.
    private class RowState {
        var source: List<KeyDef> = emptyList()
        var layout: List<List<KeyDef>> = Layout
    }

    fun setSourceRow(source: List<KeyDef>) {
        if (rowState.source == source) return
        rowState.source = source
        rowState.layout = layoutFrom(source)
        refreshStyle()
    }

    override fun currentLayoutSignature(): String = "NumberRow"

    private var gestureStartEvent: MotionEvent? = null
    private var collapseGestureTriggered: Boolean = false

    var onCollapseListener: (() -> Unit)? = null

    private fun checkGesture(ev: MotionEvent): Boolean {
        val startEvent = gestureStartEvent ?: return false
        val firstPointerId = startEvent.getPointerId(startEvent.actionIndex)
        if (ev.getPointerId(ev.actionIndex) == firstPointerId) {
            val dir = if (context.resources.configuration.layoutDirection == LAYOUT_DIRECTION_LTR) 1 else -1
            val sx = startEvent.x * dir
            val cx = ev.getX(ev.actionIndex) * dir
            val shouldCollapse = cx > sx && abs(cx - sx) > KawaiiBarComponent.resolveHeightPx(context)
            if (shouldCollapse) {
                Timber.d("NumberRow: intercepted gesture from child keyboard to handle swipe")
                resetState()
                collapseGestureTriggered = true
                return true
            }
        }
        return false
    }

    private fun resetState() {
        gestureStartEvent?.recycle()
        gestureStartEvent = null
        collapseGestureTriggered = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureStartEvent = MotionEvent.obtain(ev)
            MotionEvent.ACTION_MOVE -> {
                if (checkGesture(ev)) return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> resetState()
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureStartEvent = MotionEvent.obtain(event)
            MotionEvent.ACTION_MOVE -> checkGesture(event)
            MotionEvent.ACTION_UP -> {
                if (collapseGestureTriggered) {
                    resetState()
                    onCollapseListener?.invoke()
                    handled = true
                }
            }
            MotionEvent.ACTION_CANCEL -> resetState()
        }
        return super.onTouchEvent(event) || handled
    }

    companion object {
        val Layout = layoutFrom(emptyList())

        // Copy appearance only: a password digit must never inherit a letter's action,
        // macro, alternate label, icon, ID or popup. Keep ten equal-width digit targets.
        internal fun layoutFrom(source: List<KeyDef>): List<List<KeyDef>> {
            val styles = source.map { it.appearance }.filterIsInstance<KeyDef.Appearance.Text>()
                .filterNot { it.staticDisplay || it.transparentBackground }
            val commonStyle = styles.firstOrNull { it.variant == KeyDef.Appearance.Variant.Normal }
                ?: styles.firstOrNull()
            return listOf(
                "1234567890".mapIndexed { index, char ->
                    val digit = char.toString()
                    val style = if (styles.size == 10) styles[index] else commonStyle
                    KeyDef(
                        KeyDef.Appearance.ToolbarText(
                            displayText = digit,
                            textSize = style?.textSize ?: 21f,
                            variant = style?.variant ?: KeyDef.Appearance.Variant.Normal,
                            border = style?.border ?: KeyDef.Appearance.Border.Off,
                            margin = style?.margin ?: false,
                            textStyle = style?.textStyle ?: android.graphics.Typeface.NORMAL,
                            textColor = style?.textColor,
                            textColorMonet = style?.textColorMonet,
                            backgroundColor = style?.backgroundColor,
                            backgroundColorMonet = style?.backgroundColorMonet,
                            shadowColor = style?.shadowColor,
                            shadowColorMonet = style?.shadowColorMonet
                        ),
                        setOf(
                            KeyDef.Behavior.Press(KeyAction.SymAction(KeySym(digit.codePointAt(0))))
                        ),
                        arrayOf(KeyDef.Popup.Preview(digit))
                    )
                }
            )
        }
    }
}
