/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN")
class KeyboardFontSettingsTest {
    @Test fun legacyReloadButtonKeepsItsIdButNowRepresentsDeployment() {
        val action = org.fcitx.fcitx5.android.input.action.ButtonAction.fromId("reload_config")
        assertEquals(org.fcitx.fcitx5.android.input.action.RedeployRimeAction, action)
        assertEquals("toolbar.reload_config", action?.iconSlot)
        assertEquals("重新部署", RuntimeEnvironment.getApplication().getString(R.string.reload_config))
    }

    @Test fun unconfiguredSlotsShowTheirActualDefaultSizes() {
        val context = RuntimeEnvironment.getApplication()
        val default = context.getString(R.string.default_)
        val slots = listOf(
            Triple("key_main_font", 23f, "23"),
            Triple("key_alt_font", 10.67f, "10.67"),
            Triple("cand_font", 20f, "20"),
            Triple("comment_font", 14f, "14"),
            Triple("popup_key_font", 23f, "23"),
            Triple("preedit_font", 16f, "16")
        )
        for ((slot, size, label) in slots) {
            assertEquals("${context.getString(R.string.font_size_value, label)} · $default",
                KeyboardFontSettings.fontSummary(context, emptyMap(), slot, size))
        }
        assertEquals(default, KeyboardFontSettings.fontSummary(context, emptyMap(), "font", null))
    }

    @Test fun fontAndSizeEditsMergeTogetherWithoutTouchingOtherSlots() {
        val original = mapOf(
            "font" to listOf("base.ttf"),
            "key_main_font" to listOf("old.ttf"),
            "key_main_font_size" to listOf("23"),
            "key_alt_font_size" to listOf("10.67")
        )
        val changed = mergeKeyboardFontSettings(original, mapOf(
            "key_main_font" to listOf("new.ttf", "fallback.otf"),
            "key_main_font_size" to listOf("24.5")
        ))
        assertEquals(listOf("new.ttf", "fallback.otf"), changed["key_main_font"])
        assertEquals(listOf("24.5"), changed["key_main_font_size"])
        assertEquals(original["font"], changed["font"])
        assertEquals(original["key_alt_font_size"], changed["key_alt_font_size"])
        val reset = mergeKeyboardFontSettings(changed, mapOf(
            "key_main_font" to emptyList(),
            "key_main_font_size" to emptyList()
        ))
        assertEquals(mapOf("font" to listOf("base.ttf"), "key_alt_font_size" to listOf("10.67")), reset)
    }

    @Test fun configuredSizeAndFamilyAreShownIndependently() {
        val context = RuntimeEnvironment.getApplication()
        val values = mapOf(
            "font" to listOf("base.ttf"),
            "key_main_font" to listOf("main.ttf", "fallback.otf"),
            "key_main_font_size" to listOf("25.5"),
            "key_alt_font_size" to listOf("NaN")
        )
        assertEquals("${context.getString(R.string.font_size_value, "25.5")} · main.ttf, fallback.otf",
            KeyboardFontSettings.fontSummary(context, values, "key_main_font", 23f))
        assertEquals("${context.getString(R.string.font_size_value, "10.67")} · ${context.getString(R.string.default_)}",
            KeyboardFontSettings.fontSummary(context, values, "key_alt_font", 10.67f))
        assertEquals("base.ttf", KeyboardFontSettings.fontSummary(context, values, "font", null))
    }
}
