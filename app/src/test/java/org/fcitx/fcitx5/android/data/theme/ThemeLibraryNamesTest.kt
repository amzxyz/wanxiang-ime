/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeLibraryNamesTest {
    @Test fun defaultAndRandomStayAheadOfCustomAndOtherBuiltinThemes() {
        val random = ThemePreset.PixelDark.deriveCustomNoBackground("随机主题")
        val custom = ThemePreset.PixelLight.deriveCustomNoBackground("我的主题")
        val other = listOf(custom, ThemePreset.NordLight, ThemePreset.PixelDark)
        val themes = listOf(random) + other + ThemePreset.Watercolor + ThemePreset.Pulan
        assertEquals(listOf(ThemePreset.Pulan, ThemePreset.Watercolor, random) + other, orderThemeLibrary(themes))
        assertEquals(listOf(ThemePreset.Watercolor) + other, orderThemeLibrary(other + ThemePreset.Watercolor))
    }

    @Test fun originalPulanAndWatercolorHaveDistinctPalettesAndReadableKeyEdges() {
        val first = ThemePreset.Pulan
        val second = ThemePreset.Watercolor
        assertEquals("默认", first.name)
        assertEquals(0xffe9eaee.toInt(), first.backgroundColor)
        assertEquals(0xff898a8d.toInt(), first.keyShadowColor)
        assertEquals(first.keyBackgroundColor, second.keyBackgroundColor)
        assertEquals(0xffe9eae6.toInt(), second.backgroundColor)
        assertEquals(0xff898a8d.toInt(), second.keyShadowColor)
    }

    @Test fun presetNamesAreChineseWithoutChangingCustomNames() {
        val names = listOf("MaterialLight", "MaterialDark", "PixelLight", "PixelDark",
            "NordLight", "NordDark", "Monokai", "AMOLEDBlack", "MonetLight", "MonetDark",
            "Braun Minimal Dark", "Braun Minimal Light", "Caramel Dawn", "Caramel Ember",
            "GitHub Dark Dim", "Industrial Orange Light", "isGboard", "Sage Journey",
            "WeChat Dark", "WeChat Light", "森林集·write")
        val display = names.map(ThemeLibraryNames::displayName)
        assertTrue(display.none { name -> name.any { it in 'a'..'z' || it in 'A'..'Z' } })
        assertEquals(names.size, display.distinct().size)
        assertEquals("默认", ThemeLibraryNames.displayName(ThemePreset.Pulan.name))
        assertEquals("水彩", ThemeLibraryNames.displayName(ThemePreset.Watercolor.name))
        assertEquals("My custom theme", ThemeLibraryNames.displayName("My custom theme"))
    }
}
