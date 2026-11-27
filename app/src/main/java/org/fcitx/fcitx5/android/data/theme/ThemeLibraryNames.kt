/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

/** 名称用于展示；主题文件名、已保存的选择和莫奈映射仍用原有稳定标识。 */
internal object ThemeLibraryNames {
    private val names = mapOf(
        "MaterialLight" to "云灰",
        "MaterialDark" to "墨青",
        "PixelLight" to "素白",
        "PixelDark" to "炭灰",
        "NordLight" to "霜雪",
        "NordDark" to "极夜",
        "Monokai" to "暮林",
        "AMOLEDBlack" to "纯黑",
        "TransparentDark" to "夜雾",
        "TransparentLight" to "薄纱",
        "MonetLight" to "随景·晨光",
        "MonetDark" to "随景·夜色",
        "Braun Minimal Dark" to "黑曜橙",
        "Braun Minimal Light" to "暖白橙",
        "Caramel Dawn" to "焦糖晨光",
        "Caramel Ember" to "琥珀余温",
        "GitHub Dark Dim" to "深海微光",
        "Industrial Orange Light" to "橙光",
        "isGboard" to "春日新绿",
        "Sage Journey" to "青苔漫步",
        "WeChat Dark" to "墨绿夜色",
        "WeChat Light" to "清荷",
        "森林集·write" to "森林集·素白"
    )

    fun displayName(name: String): String = names[name] ?: name
}

/** The two bundled palettes precede random and other themes. */
internal fun orderThemeLibrary(themes: List<Theme>): List<Theme> =
    themes.sortedBy {
        when (it.name) {
            ThemePreset.Pulan.name -> 0
            ThemePreset.Watercolor.name -> 1
            ThemeManager.RANDOM_THEME_NAME -> 2
            else -> 3
        }
    }
