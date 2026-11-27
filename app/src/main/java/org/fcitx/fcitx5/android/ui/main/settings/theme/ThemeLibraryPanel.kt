/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.dimensions.dp

/** A collapsible section header and its independently scrolling content. */
internal fun themeLibraryPanel(
    context: Context, title: Int, addDescription: Int, content: View, onAdd: () -> Unit
): ThemeLibraryPanel = with(context) {
    val primary = settingsColor(androidx.appcompat.R.attr.colorPrimary)
    val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(4), 0)
        addView(TextView(context).apply {
            setText(title)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.settingsColor(android.R.attr.textColorPrimary))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.FrameLayout(context).apply {
            contentDescription = context.getString(addDescription)
            isFocusable = true
            isClickable = true
            setOnClickListener { onAdd() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_plus_24)
                imageTintList = ColorStateList.valueOf(primary)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(context.settingsColor(com.google.android.material.R.attr.colorSecondaryContainer))
                }
            }, android.widget.FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }
    ThemeLibraryPanel(context, header, content)
}

/** The header clips and fades as its occupied height shrinks to zero. */
internal class ThemeLibraryPanel(
    context: Context,
    private val header: View,
    content: View
) : LinearLayout(context) {
    private val fullHeight = dp(48)
    private val headerFrame = FrameLayout(context).apply {
        clipChildren = true
        addView(header, FrameLayout.LayoutParams(-1, fullHeight))
    }

    init {
        orientation = VERTICAL
        addView(headerFrame, LayoutParams(-1, fullHeight))
        addView(content, LayoutParams(-1, 0, 1f))
    }

    fun setHeaderOffset(offset: Int) {
        val hidden = offset.coerceIn(0, fullHeight)
        val remaining = fullHeight - hidden
        val params = headerFrame.layoutParams
        if (params.height != remaining) {
            params.height = remaining
            headerFrame.layoutParams = params
        }
        header.translationY = -hidden.toFloat()
        header.alpha = remaining.toFloat() / fullHeight.coerceAtLeast(1)
        header.visibility = if (remaining == 0) View.INVISIBLE else View.VISIBLE
        header.importantForAccessibility = if (remaining == 0) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
}

