/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberRowTest {

    @Test
    fun inheritsFirstRowStyleWithoutCopyingActions() {
        val source = (0..9).map { index ->
            KeyDef(
                KeyDef.Appearance.Text(
                    displayText = "letter",
                    textSize = 19f + index,
                    textStyle = android.graphics.Typeface.BOLD,
                    variant = KeyDef.Appearance.Variant.Alternative,
                    border = KeyDef.Appearance.Border.On,
                    margin = true,
                    viewId = 123,
                    textColor = index,
                    backgroundColorMonet = "accent1_100",
                    shadowColor = 1234
                ),
                emptySet()
            )
        }
        val digits = NumberRow.layoutFrom(source).single()
        assertEquals("1234567890", digits.joinToString("") {
            (it.appearance as KeyDef.Appearance.ToolbarText).displayText
        })
        digits.forEachIndexed { index, key ->
            val style = key.appearance as KeyDef.Appearance.ToolbarText
            assertEquals(19f + index, style.textSize, 0f)
            assertEquals(android.graphics.Typeface.BOLD, style.textStyle)
            assertEquals(KeyDef.Appearance.Variant.Alternative, style.variant)
            assertEquals(KeyDef.Appearance.Border.On, style.border)
            assertEquals(true, style.margin)
            assertEquals(index, style.textColor)
            assertEquals("accent1_100", style.backgroundColorMonet)
            assertEquals(1234, style.shadowColor)
            assertEquals(-1, style.viewId)
            assertEquals(0.1f, style.percentWidth, 0f)
            assertEquals(1, key.behaviors.size)
            assertEquals(true, key.behaviors.single() is KeyDef.Behavior.Press)
        }
    }

    @Test
    fun nonTenKeyRowUsesNormalTextStyleForAllDigits() {
        val source = listOf(
            KeyDef(KeyDef.Appearance.Text("x", 18f, variant = KeyDef.Appearance.Variant.Accent), emptySet()),
            KeyDef(KeyDef.Appearance.Text("y", 22f), emptySet())
        )
        val digits = NumberRow.layoutFrom(source).single()
        assertEquals(10, digits.size)
        digits.forEach {
            val style = it.appearance as KeyDef.Appearance.ToolbarText
            assertEquals(KeyDef.Appearance.Variant.Normal, style.variant)
            assertEquals(22f, style.textSize, 0f)
        }
    }

    @Test
    fun numberRowUsesToolbarRendererWithKeyFont() {
        val appearances = NumberRow.Layout
            .single()
            .map { it.appearance }

        assertEquals(10, appearances.size)
        appearances.forEach { appearance ->
            // ToolbarText keeps the label out of the keyboard text-scale pipeline but still
            // renders through the normal key-label font path, so digits stay visible while
            // following the user's configured key typeface and font size.
            // textSize only exists on the Text subclasses, so the type assertion has to happen
            // before reading it.
            val toolbarText = requireNotNull(appearance as? KeyDef.Appearance.ToolbarText) {
                "expected ToolbarText, got ${appearance::class}"
            }
            assertEquals(KeyDef.Appearance.Variant.Normal, toolbarText.variant)
            assertEquals(21f, toolbarText.textSize, 0f)
            assertEquals(KeyDef.Appearance.Border.Off, toolbarText.border)
            assertEquals(false, toolbarText.margin)
        }
    }
}
