/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import java.io.File
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 公共字体设置读取失败时必须保持文件原样。
 *
 * 曾经的失败路径把「配置损坏」当成「没有配置」，于是拿空 map 写回，把用户其它字体
 * 设置整份抹掉；这里走真实的 updateKeyboardFontSetting 并断言 fontset.json 一个字节都没变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ThemeKitFontFailureTest {
    private val broken = "{ this is not valid fontset json"
    private val fontName = "kit-font-failure-test.otf"

    private fun fontFile(): File? = UserConfigFiles.fontsDir()?.let { File(it, fontName) }

    @After
    fun cleanUp() {
        fontFile()?.delete()
        UserConfigFiles.fontsetJson()?.delete()
    }

    @Test
    fun corruptFontsetIsLeftUntouchedWhenEditingGlobalFont() {
        val font = requireNotNull(fontFile()) { "fontsDir is unavailable in this test environment" }
        font.parentFile?.mkdirs()
        font.writeBytes(byteArrayOf(1, 2, 3))
        val config = requireNotNull(UserConfigFiles.fontsetJson()) {
            "fontset config path is unavailable in this test environment"
        }
        config.parentFile?.mkdirs()
        config.writeText(broken)
        val before = config.readBytes()
        assertTrue(ConfigProviders.readFontsetPathMapSnapshot().isFailure)

        assertTrue(updateKeyboardFontSetting("key_alt_font", listOf(fontName)).isFailure)

        assertArrayEquals(
            "损坏的 fontset.json 在公共字体设置失败时不能被重写",
            before,
            config.readBytes()
        )
    }
}
