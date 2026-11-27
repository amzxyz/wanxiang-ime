/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

/**
 * 成套主题：一个配色主题 + 可选的配套键盘布局和操作键形状。
 *
 * 关联按主题名登记在代码里，而不是写进主题 JSON：布局与配色是各自独立的文件，字体、图标为独立全局设置，
 * 导出/分享主题时它们不会跟着走，写进 JSON 只会得到一条指向不存在资源的悬空引用。
 * 主题被用户复制或改名后不再成套，这也是期望的行为——副本不是内置套装。
 */
object ThemeKits {

    data class Kit(
        /** 键盘布局 profile 名，即 `TextKeyboardLayout.<名称>.json` 里的 `<名称>`。 */
        val layoutProfile: String,
        /**
         * 这套主题要求的操作按键形状，对应「使用 Gboard 风格的操作按键」偏好。
         * 为 null 表示这套主题不改动用户的选择。
         */
        val specialKeyOvalShape: Boolean? = null
    )

    private val kits = mapOf(
        ThemePreset.Pulan.name to Kit(
            layoutProfile = "default",
            specialKeyOvalShape = false
        ),
        ThemePreset.Watercolor.name to Kit(
            layoutProfile = "default",
            specialKeyOvalShape = false
        )
    )

    /** 该主题的配套资源；为 null 表示它只是一套配色。 */
    fun kitOf(theme: Theme): Kit? = kits[theme.name]
}
