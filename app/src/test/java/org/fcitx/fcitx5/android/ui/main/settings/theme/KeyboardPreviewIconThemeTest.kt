/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.app.Activity
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.keyboard.AltTextKeyView
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class]
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardPreviewIconThemeTest {
    private val layout = Json.parseToJsonElement(
        """
        {"rime":{"default":[[
          {"type":"AlphabetKey","main":"a","alt":"1"}
        ]]}}
        """.trimIndent()
    ).jsonObject

    private fun svg(color: String) =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'>" +
            "<rect x='0' y='0' width='24' height='24' fill='$color'/></svg>"

    @Test
    fun selectingIconThemeRefreshesTheKeyboardInsideThemePreview() {
        val context = RuntimeEnvironment.getApplication()
        SplitKeyboardStateManager.init(context)
        val previousTheme = IconThemeManager.activeTheme
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val preview = KeyboardPreviewUi(activity, ThemePreset.PixelLight)
        try {
            IconThemeManager.selectTheme(IconTheme.default())
            activity.setContentView(preview.root)
            TextKeyboard.withPreviewLayout(layout) {
                preview.setTheme(ThemePreset.PixelLight)
                shadowOf(Looper.getMainLooper()).idle()

                val keyboard = preview.root.allViews.filterIsInstance<TextKeyboard>().single()
                val letter = keyboard.allViews.filterIsInstance<AltTextKeyView>().single()
                assertEquals(View.GONE, letter.img.visibility)

                IconThemeManager.selectTheme(
                    IconTheme(
                        name = "preview-watercolor",
                        icons = mapOf("keys.letter.a" to svg("#FF00FF"))
                    )
                )
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals(View.VISIBLE, letter.img.visibility)
                assertTrue(
                    "The preview should render the selected SVG, not retain the old letter glyph",
                    letter.img.drawable is BitmapDrawable
                )
            }
        } finally {
            IconThemeManager.selectTheme(previousTheme)
            preview.dispose()
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
