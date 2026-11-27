/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.preview

import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePreviewHero
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 主题页、分体键盘校准页的预览键盘必须落在真实输入法的布局分节上。
 *
 * 布局文件按输入法名分节（本应用的输入法是 "rime"），每节的键可以带自己的
 * `backgroundColor`。这些预览曾用裸的 "Preview" 名去查布局：文件里没有这个分节，
 * 于是回退到内置 QWERTY，逐键颜色全部丢失，预览只剩主题统一底色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ThemePreviewLayoutTest {

    /** 只有 rime 分节、且第一个键带显式颜色的布局。 */
    private val coloredRimeLayout = Json.parseToJsonElement("""
        {"rime":{"default":[[
          {"type":"AlphabetKey","main":"a","alt":"1","backgroundColor":-65536},
          {"type":"AlphabetKey","main":"b","alt":"2"}
        ]]}}
    """).jsonObject

    private fun rimeIme() = InputMethodEntry(
        uniqueName = "rime",
        name = "Rime",
        icon = "",
        nativeName = "",
        label = "R",
        languageCode = "en",
        addon = "rime",
        isConfigurable = false
    )

    private fun keyboardOf(ime: InputMethodEntry): TextKeyboard {
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        return TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true).apply {
            onInputMethodUpdate(ime)
        }
    }

    @Test fun heroEnlargesTheLivePreviewWithoutGrowingTheOuterFrame() {
        val context = RuntimeEnvironment.getApplication()
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        val width = dp(360)
        val nativeWidth = dp(360)
        val nativeHeight = dp(230)
        val heading = TextView(context).apply {
            text = "Theme"
            textSize = 16f
        }
        val preview = View(context)
        val hero = ThemePreviewHero(context, heading, preview) { nativeWidth to nativeHeight }
        val oldAvailable = width - dp(20) - dp(16) - dp(12)
        val oldPreviewWidth = oldAvailable - (oldAvailable / 2.65f).toInt()
        val oldScale = minOf(1f, oldPreviewWidth.toFloat() / nativeWidth,
            dp(144).toFloat() / nativeHeight)
        val oldHeight = (nativeHeight * oldScale).roundToInt() + dp(14) * 2

        hero.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        hero.layout(0, 0, width, hero.measuredHeight)

        assertEquals(oldHeight, hero.measuredHeight)
        assertEquals(preview.scaleX, preview.scaleY, 0f)
        assertTrue(preview.scaleX > oldScale)
        assertEquals(nativeWidth, preview.measuredWidth)
        assertEquals(nativeHeight, preview.measuredHeight)
        assertEquals((width - dp(16)).toFloat(), preview.left + nativeWidth * preview.scaleX, 1f)
        assertTrue(preview.left >= heading.right + dp(8) - 1)
        assertTrue(preview.top >= dp(6) - 1)
        assertTrue(preview.top + nativeHeight * preview.scaleY <= hero.height - dp(6) + 1)
    }

    @Test fun themePreviewLayoutNameFollowsRealInputMethod() {
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(rimeIme()))
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(null))
    }

    @Test fun themePreviewKeepsRealInputMethodDisplayName() {
        val preview = PreviewInputMethodEntry.createRealLayoutPreview(rimeIme())
        // 布局分节名只管取布局，空格键上仍应显示真实输入法名。
        assertEquals("Rime", preview.displayName)
        assertEquals("rime", preview.uniqueName)
        assertEquals("", preview.subMode.name)
    }

    @Test fun unknownInputMethodFallsBackToReadableName() {
        // 真实输入法还没上报时，空格键不能直接暴露布局分节名 "rime"。
        val preview = PreviewInputMethodEntry.createRealLayoutPreview(null)
        assertEquals("rime", preview.uniqueName)
        assertEquals("Rime", preview.displayName)
        // 查布局仍要落在真实分节上，否则冷启动时又会退回内置 QWERTY。
        assertEquals("rime", PreviewInputMethodEntry.resolveRealLayoutName(preview))
    }

    @Test fun themePreviewRendersRealLayoutColors() =
        TextKeyboard.withPreviewLayout(coloredRimeLayout) {
            val keyboard = keyboardOf(PreviewInputMethodEntry.createRealLayoutPreview(rimeIme()))
            try {
                val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
                assertEquals(listOf("A", "B"), keys.map { it.mainText.text.toString() })
                assertEquals(-65536, keys.first().def.backgroundColor)
            } finally {
                keyboard.onDetach()
            }
        }

    @Test fun barePreviewNameFallsBackToBuiltInLayoutWithoutColors() =
        TextKeyboard.withPreviewLayout(coloredRimeLayout) {
            // 旧写法：不带布局名的 Preview IME 在布局文件里查不到分节。
            val keyboard = keyboardOf(PreviewInputMethodEntry.create())
            try {
                val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
                assertEquals("Q", keys.first().mainText.text.toString())
                assertNull(keys.first().def.backgroundColor)
            } finally {
                keyboard.onDetach()
            }
        }
}
