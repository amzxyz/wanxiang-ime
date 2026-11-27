/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Rect
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the real keyboard refresh and popup dispatch with layout-owned labels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class TextKeyboardLabelsTest {
    private val layout = Json.parseToJsonElement("""
        {"default":[[
          {"type":"AlphabetKey","main":"a","alt":"!"},
          {"type":"AlphabetKey","main":"b","displayText":"固定 B","alt":"@"},
          {"type":"SymbolKey","label":"。"},
          {"type":"PlaceholderKey","main":"Decorative A","alt":"▴"}
        ]]}
    """).jsonObject

    // 只有层子布局：不是"按方案细分"，中文与 ASCII 必须共用同一份行缓存。
    private val layeredLayout = Json.parseToJsonElement("""
        {"preview":{
          "default":[[{"type":"AlphabetKey","main":"a","alt":"!"}]],
          "__layer__:数字":[[{"type":"MacroKey","label":"1"}]]
        }}
    """).jsonObject

    // 真正的方案子布局：仍应进入按方案细分路径。
    private val subModeLayout = Json.parseToJsonElement("""
        {"preview":{
          "default":[[{"type":"AlphabetKey","main":"a","alt":"!"}]],
          "英文":[[{"type":"AlphabetKey","main":"q","alt":"@"}]]
        }}
    """).jsonObject

    private fun keyboard(block: (TextKeyboard) -> Unit) = TextKeyboard.withPreviewLayout(layout) {
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        val ime = InputMethodEntry("preview")
        val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = ime, isPreview = true)
        keyboard.onInputMethodUpdate(ime)
        try { block(keyboard) } finally { keyboard.onDetach() }
    }

    private fun TextKeyboard.caps() {
        ReflectionHelpers.callInstanceMethod<Unit>(this, "onAction",
            ClassParameter.from(KeyAction::class.java, KeyAction.CapsAction(false)),
            ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard))
    }

    private fun usesSubMode(keyboard: TextKeyboard, ime: InputMethodEntry): Boolean =
        ReflectionHelpers.callInstanceMethod(
            keyboard, "layoutUsesSubMode",
            ClassParameter.from(InputMethodEntry::class.java, ime)
        )

    private fun TextKeyboard.popup(action: PopupAction) {
        ReflectionHelpers.callInstanceMethod<Unit>(this, "onPopupAction",
            ClassParameter.from(PopupAction::class.java, action))
    }

    @Test fun labelsSurviveCapsCompositionAndStyleRefresh() = keyboard { keyboard ->
        fun check(letter: String) {
            val keys = keyboard.allViews.filterIsInstance<TextKeyView>().toList()
            assertEquals(listOf(letter, "固定 B", "。", "Decorative A"),
                keys.map { it.mainText.text.toString() })
            assertEquals(listOf("!", "@", "▴"),
                keys.filterIsInstance<AltTextKeyView>().map { it.altText.text.toString() })
        }
        check("A")
        keyboard.caps()
        check("A")
        keyboard.onCompositionStateChanged(true)
        check("A")
        keyboard.refreshStyle()
        check("A")
        keyboard.caps()
        keyboard.onCompositionStateChanged(false)
        keyboard.refreshStyle()
        check("A")
    }

    @Test fun uppercaseLetterSizeIsSlightlySmallerAndDoesNotAffectOtherLabels() = keyboard { keyboard ->
        val fonts = org.fcitx.fcitx5.android.input.font.FontProviders
        val original = fonts.provider
        val sizes = mutableMapOf<String, Float>()
        val testFonts = object : org.fcitx.fcitx5.android.input.font.FontProviderApi by original {
            override val fontSizeMap: MutableMap<String, Float> get() = sizes
        }
        val cache = org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache()
        val chinese = cache.get(false, "wanxiang", "万象拼音", "Rime")
        val english = cache.get(true, "wanxiang", "万象拼音", "Rime")
        fun sizesOfKeys() = keyboard.allViews.filterIsInstance<TextKeyView>()
            .map { it.mainText.textSize }.toList()
        fun altSize() = keyboard.allViews.filterIsInstance<AltTextKeyView>().first().altText.textSize
        try {
            fonts.provider = testFonts
            keyboard.onInputMethodUpdate(english)
            keyboard.refreshStyle()
            val lowercase = sizesOfKeys()
            val secondary = altSize()

            keyboard.onInputMethodUpdate(chinese)
            val uppercase = sizesOfKeys()
            assertEquals(lowercase[0] * 0.92f, uppercase[0], 0.5f)
            assertEquals(lowercase.drop(1), uppercase.drop(1))
            assertEquals(secondary, altSize(), 0.01f)
            keyboard.refreshStyle()
            assertEquals(uppercase, sizesOfKeys())
            keyboard.onInputMethodUpdate(english)
            assertEquals(lowercase, sizesOfKeys())
            keyboard.caps()
            assertEquals(uppercase, sizesOfKeys())
            keyboard.caps()
            assertEquals(lowercase, sizesOfKeys())

            sizes["key_main_font_size"] = 27f
            fonts.provider = testFonts
            keyboard.refreshStyle()
            val custom = sizesOfKeys()
            keyboard.onInputMethodUpdate(chinese)
            assertEquals(custom, sizesOfKeys())
        } finally {
            fonts.provider = original
        }
    }

    @Test fun chineseAndEnglishKeycapsFollowModeWithoutChangingInputOrCustomLabels() {
        val modeLayout = Json.parseToJsonElement("""
            {"default":[[
              {"type":"AlphabetKey","main":"a","alt":"!"},
              {"type":"MacroKey","label":"b","tap":{"macro":[{"type":"tap","keys":[{"fcitx":"B"}]}]}},
              {"type":"AlphabetKey","main":"c","displayText":"c","alt":"#"},
              {"type":"AlphabetKey","main":"d","displayText":"D","alt":"$"},
              {"type":"PlaceholderKey","main":"x"}
            ]]}
        """).jsonObject
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        val cache = org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache()
        val chinese = cache.get(false, "wanxiang", "万象拼音", "Rime")
        val english = cache.get(true, "wanxiang", "万象拼音", "Rime")
        for (theme in listOf(ThemePreset.Pulan, ThemePreset.Watercolor)) {
            TextKeyboard.withPreviewLayout(modeLayout) {
                val keyboard = TextKeyboard(context, theme, initialIme = chinese, isPreview = true)
                fun labels() = keyboard.allViews.filterIsInstance<TextKeyView>().map { it.mainText.text.toString() }.toList()
                try {
                    keyboard.onInputMethodUpdate(chinese)
                    assertEquals(listOf("A", "B", "c", "D", "x"), labels())
                    val macro = MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("B")))))
                    val transformed = ReflectionHelpers.callInstanceMethod<MacroAction>(
                        keyboard, "preprocessMacroAction",
                        ClassParameter.from(MacroAction::class.java, macro),
                        ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard)
                    )
                    assertEquals(MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("b"))))), transformed)
                    keyboard.onInputMethodUpdate(english)
                    assertEquals(listOf("a", "b", "c", "D", "x"), labels())
                    keyboard.caps()
                    assertEquals(listOf("A", "B", "C", "D", "x"), labels())
                    keyboard.caps()
                    assertEquals(listOf("a", "b", "c", "D", "x"), labels())
                    keyboard.onInputMethodUpdate(chinese)
                    keyboard.onCompositionStateChanged(true)
                    keyboard.refreshStyle()
                    assertEquals(listOf("A", "B", "c", "D", "x"), labels())
                } finally {
                    keyboard.onDetach()
                }
            }
        }
    }

    @Test fun directInputPunctuationFollowsModeAndRestoresChineseLabels() {
        val punctuationLayout = Json.parseToJsonElement("""
            {"default":[[
              {"type":"SymbolKey","label":"。","swipeUpLabel":"？",
                "swipeUp":{"macro":[{"type":"tap","keys":[{"fcitx":"question"}]}]}},
              {"type":"MacroKey","label":",","tap":{"macro":[{"type":"tap","keys":[{"fcitx":"comma"}]}]},
                "swipeUpLabel":"、","swipeUp":{"macro":[{"type":"text","text":"、","directText":"\\"}]}},
              {"type":"PlaceholderKey","main":"。"}
            ]]}
        """).jsonObject
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        val cache = org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache()
        val chinese = cache.get(false, "wanxiang", "万象拼音", "Rime")
        val english = cache.get(true, "wanxiang", "万象拼音", "Rime")
        val previousPassword = TextKeyboard.isPasswordDefaultLayout()
        TextKeyboard.setPasswordDefaultLayout(false)
        try {
            TextKeyboard.withPreviewLayout(punctuationLayout) {
                val keyboard = TextKeyboard(context, ThemePreset.Pulan, initialIme = chinese)
                fun check(main: String, question: String, enumeration: String) {
                    val keys = keyboard.allViews.filterIsInstance<AltTextKeyView>().toList()
                    assertEquals(main, keys[0].mainText.text.toString())
                    assertEquals(question, keys[0].altText.text.toString())
                    assertEquals(enumeration, keys[1].altText.text.toString())
                    assertEquals("。", keys[2].mainText.text.toString())
                }
                fun transform(text: String, directText: String? = null): MacroAction = ReflectionHelpers.callInstanceMethod(
                    keyboard, "preprocessMacroAction",
                    ClassParameter.from(MacroAction::class.java, MacroAction(listOf(MacroStep.Text(text, directText)))),
                    ClassParameter.from(KeyActionListener.Source::class.java, KeyActionListener.Source.Keyboard)
                )
                try {
                    keyboard.onInputMethodUpdate(chinese)
                    check("。", "？", "、")
                    keyboard.onInputMethodUpdate(english)
                    check(".", "?", "\\")
                    keyboard.caps()
                    check(".", "?", "\\")
                    keyboard.caps()
                    check(".", "?", "\\")
                    assertEquals(MacroAction(listOf(MacroStep.Text("\\"))), transform("、", "\\"))
                    assertEquals(MacroAction(listOf(MacroStep.Text("、"))), transform("、"))
                    assertEquals(MacroAction(listOf(MacroStep.Text("你好，世界"))), transform("你好，世界"))
                    keyboard.onCompositionStateChanged(true)
                    keyboard.refreshStyle()
                    check(".", "?", "\\")
                    keyboard.onInputMethodUpdate(chinese)
                    check("。", "？", "、")
                    assertEquals(MacroAction(listOf(MacroStep.Text("、"))), transform("、", "\\"))
                    TextKeyboard.setPasswordDefaultLayout(true)
                    keyboard.onInputMethodUpdate(chinese)
                    check(".", "?", "\\")
                    assertEquals(MacroAction(listOf(MacroStep.Text("\\"))), transform("、", "\\"))
                    assertEquals(MacroAction(listOf(MacroStep.Text("、"))), transform("、"))
                    TextKeyboard.setPasswordDefaultLayout(false)
                    keyboard.onInputMethodUpdate(chinese)
                    check("。", "？", "、")
                } finally {
                    keyboard.onDetach()
                }
            }
        } finally {
            TextKeyboard.setPasswordDefaultLayout(previousPassword)
        }
    }

    @Test fun directPunctuationKeepsTheOriginalLongPressPresetLookup() = keyboard { keyboard ->
        val selected = mutableListOf<KeyDef.Popup.Keyboard.Preset>()
        keyboard.popupActionListener = PopupActionListener { action ->
            if (action is PopupAction.ShowKeyboardAction && action.keyboard is KeyDef.Popup.Keyboard.Preset) {
                selected.add(action.keyboard)
            }
        }
        val cache = org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache()
        keyboard.onInputMethodUpdate(cache.get(true, "wanxiang", "万象拼音", "Rime"))
        val preset = KeyDef.Popup.Keyboard.Preset("。", fallbackLabel = ".")
        keyboard.popup(PopupAction.ShowKeyboardAction(1, preset, Rect(0, 0, 40, 60)))
        assertEquals(listOf(preset), selected)
    }

    @Test fun directLabelsFollowActionsRatherThanACharacterConversionTable() {
        assertEquals(".", directActionLabel("。", KeyAction.FcitxKeyAction("."), true))
        assertEquals("。", directActionLabel("。", KeyAction.FcitxKeyAction("."), false))
        assertEquals("、", directActionLabel("、", MacroAction(listOf(MacroStep.Text("、"))), true))
        assertEquals("\\", directActionLabel("、", MacroAction(listOf(MacroStep.Text("、", "\\"))), true))
        assertEquals("?", directActionLabel("？", MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("question"))))), true))
        assertEquals("自定义", directActionLabel("自定义", KeyAction.FcitxKeyAction("."), true))
        assertEquals("！", directActionLabel("！", MacroAction(listOf(MacroStep.Text("！"))), true))
        assertEquals("*", directActionLabel("×", MacroAction(listOf(MacroStep.Tap(listOf(KeyRef.Fcitx("KP_Multiply"))))), true))
        val action = MacroAction(listOf(MacroStep.Text("、", "\\")))
        val codec = org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
        assertEquals(action, codec.parseMacroAction(codec.macroActionToJson(action)))
    }

    @Test fun layerEntriesDoNotMakeAFlatLayoutSubModeSpecific() {
        TextKeyboard.withPreviewLayout(layeredLayout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val previewIme = InputMethodEntry("preview")
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = previewIme, isPreview = true)
            keyboard.onInputMethodUpdate(previewIme)
            try {
                // __layer__:数字 是布局内的层，不是按方案细分；ASCII/中文不能再各存一份行缓存。
                assertFalse(usesSubMode(keyboard, previewIme))
            } finally {
                keyboard.onDetach()
            }
        }
        TextKeyboard.withPreviewLayout(subModeLayout) {
            val context = RuntimeEnvironment.getApplication()
            SplitKeyboardStateManager.init(context)
            val previewIme = InputMethodEntry("preview")
            val keyboard = TextKeyboard(context, ThemePreset.PixelLight, initialIme = previewIme, isPreview = true)
            keyboard.onInputMethodUpdate(previewIme)
            try {
                // 真正的子模式布局仍要进入按方案细分路径。
                assertTrue(usesSubMode(keyboard, previewIme))
            } finally {
                keyboard.onDetach()
            }
        }
    }

    @Test fun popupPreviewsKeepLiteralSymbolsAndExistingLetterCaps() = keyboard { keyboard ->
        val previews = mutableListOf<String>()
        keyboard.popupActionListener = PopupActionListener { action ->
            when (action) {
                is PopupAction.PreviewAction -> previews.add(action.content)
                is PopupAction.PreviewUpdateAction -> previews.add(action.content)
                else -> Unit
            }
        }
        keyboard.caps()
        keyboard.popup(PopupAction.PreviewAction(1, "a", Rect(0, 0, 40, 60)))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "."))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "，"))
        keyboard.popup(PopupAction.PreviewUpdateAction(1, "👨‍💻"))
        assertEquals(listOf("A", ".", "，", "👨‍💻"), previews)
    }
}
