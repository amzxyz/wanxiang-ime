/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Context
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver

/** Editor colors only: reads existing key fields without changing layout data.
 * Inspired by boomker/fcitx5-android@1d85a0d5, with this project's key variants.
 */
internal class LayoutKeyColors(private val systemColor: (String) -> Int? = { null }) {
    fun background(key: Map<String, Any?>, theme: Theme): Int =
        resolveOverride(key, "backgroundColor", theme) ?: when (key["type"]) {
            "CapsKey", "LayoutSwitchKey", "CommaKey", "SymbolKey", "BackspaceKey", "MiniSpaceKey" -> theme.altKeyBackgroundColor
            "ReturnKey" -> theme.accentKeyBackgroundColor
            else -> theme.keyBackgroundColor
        }

    fun foreground(key: Map<String, Any?>, theme: Theme): Int =
        resolveOverride(key, "textColor", theme) ?: when (key["type"]) {
            "CapsKey", "LayoutSwitchKey", "CommaKey", "SymbolKey", "BackspaceKey", "MiniSpaceKey", "LanguageKey" -> theme.altKeyTextColor
            "ReturnKey" -> theme.accentKeyTextColor
            else -> theme.keyTextColor
        }

    private fun resolveOverride(key: Map<String, Any?>, field: String, theme: Theme): Int? {
        val reference = (key[field + "Monet"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val resolved = when {
            reference == null -> null
            reference.startsWith("theme:") -> themeToken(theme, reference.removePrefix("theme:"))
            else -> systemColor(reference)
        }
        return resolved ?: parseColor(key[field])
    }

    private fun parseColor(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> org.fcitx.fcitx5.android.utils.ColorLiteral.parseArgb(value)
        else -> null
    }

    private fun themeToken(theme: Theme, token: String): Int? =
        LayoutKeyColorResolver.resolveThemeTokenColor(theme, token)

    companion object {
        fun forContext(context: Context): LayoutKeyColors {
            val resourceIds = mutableMapOf<String, Int>()
            return LayoutKeyColors { name ->
                val id = resourceIds.getOrPut(name) { context.resources.getIdentifier(name, "color", "android") }
                if (id == 0) null else runCatching { context.getColor(id) }.getOrNull()
            }
        }
    }
}
