/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.File
import kotlinx.serialization.json.*
import org.fcitx.fcitx5.android.data.theme.ThemeKits
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.BuiltinKeyboardLayout
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class BundledPresetsUpgradeTest {
    @get:Rule val temp = TemporaryFolder()
    private val asset = "bundled/icon_themes/谷歌.json"
    // Use the actual packaged source; Windows Robolectric cannot open its non-ASCII ZIP path.
    private val current get() = File("src/main/assets/$asset").readBytes()
    private val handled get() = ReflectionHelpers.callInstanceMethod<MutableSet<String>>(BundledPresets, "getHandled")
    private fun previous() = javaClass.classLoader!!.getResourceAsStream(
        "bundled/google-icon-theme-before-20261117.json"
    )!!.use { it.readBytes() }
    private fun install(file: File) = ReflectionHelpers.callInstanceMethod<Int>(
        BundledPresets, "installAsset", ClassParameter.from(String::class.java, asset),
        ClassParameter.from(File::class.java, file), ClassParameter.from(ByteArray::class.java, current)
    )

    @Before fun clearHandledVersions() { handled.clear() }

    @Test fun upgradingTheActualPreviousThemeInstallsTheNewBundledIcons() {
        val old = previous()
        assertEquals(68073, old.size)
        val destination = temp.newFile().apply { writeBytes(old) }
        install(destination)
        assertArrayEquals(current, destination.readBytes())
    }

    @Test fun anOldSkipMarkerDoesNotPreventTheCorrectedUpgrade() {
        val destination = temp.newFile().apply { writeBytes(previous()) }
        handled.add("$asset#${current.size}")
        install(destination)
        assertArrayEquals(current, destination.readBytes())
        assertTrue(destination.setLastModified(1000))
        assertEquals(0, install(destination))
        assertEquals(1000L, destination.lastModified())
    }

    @Test fun aUserEditedThemeSurvivesTheCorrectedRegistration() {
        val edited = previous() + "\n ".toByteArray()
        val destination = temp.newFile().apply { writeBytes(edited) }
        handled.add("$asset#${current.size}")
        install(destination)
        assertArrayEquals(edited, destination.readBytes())
    }

    @Test fun aDeletedHandledThemeIsRecreated() {
        handled.add("$asset#${current.size}")
        val destination = File(temp.root, "deleted-theme.json")
        assertEquals(1, install(destination))
        assertArrayEquals(current, destination.readBytes())
    }

    @Test fun defaultLayoutIsTheOnlyBundledProfile() {
        val assets = File("src/main/assets/bundled/keyboard_layouts")
        assertTrue(assets.listFiles().orEmpty().none {
            it.isFile && it.name.startsWith("TextKeyboardLayout.") && it.name.endsWith(".json")
        })
        assertTrue(File(assets, "PopupPreset.json").isFile)
        val root = requireNotNull(BuiltinKeyboardLayout.json)
        val base = root.getValue("default").jsonObject
        val rows = base.getValue("default").jsonArray
        val bottom = rows.last().jsonArray.map { it.jsonObject }
        assertEquals("Symbol", bottom[0].getValue("subLabel").jsonPrimitive.content)
        assertEquals("?123", bottom[1].getValue("label").jsonPrimitive.content)
        assertEquals("Number", bottom[1].getValue("subLabel").jsonPrimitive.content)
        assertEquals("LayoutSwitchKey", bottom[1].getValue("type").jsonPrimitive.content)
        for (field in listOf("backgroundColor", "shadowColor", "textColor")) {
            val token = field + "Monet"
            val first = bottom.first().getValue(token).jsonPrimitive.content.removePrefix("theme:")
            val last = bottom.last().getValue(token).jsonPrimitive.content.removePrefix("theme:")
            assertEquals(
                org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver.resolveThemeTokenColor(
                    org.fcitx.fcitx5.android.data.theme.ThemePreset.Watercolor, first),
                org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver.resolveThemeTokenColor(
                    org.fcitx.fcitx5.android.data.theme.ThemePreset.Watercolor, last))
        }
        assertEquals(listOf("全", "切", "复", "贴", "撤", "重", "首"),
            rows[2].jsonArray.mapNotNull { it.jsonObject["altLabel"]?.jsonPrimitive?.content })
        assertTrue(appContext.assets.open(BundledPresets.EDIT_ICON_FONT_ASSET).use {
            it.readBytes().isNotEmpty()
        })
        val comma = bottom[2]
        assertEquals("、", comma.getValue("swipeUpLabel").jsonPrimitive.content)
        assertEquals("、", comma.getValue("swipeUp").jsonObject.getValue("macro")
            .jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue(bottom.none { it["type"]?.jsonPrimitive?.content == "LanguageKey" })
        assertEquals("language_switch", bottom.single { it["type"]?.jsonPrimitive?.content == "SpaceKey" }
            .getValue("swipe").jsonObject.getValue("macro").jsonArray[0].jsonObject.getValue("id").jsonPrimitive.content)
        val period = bottom[4]
        val slash = bottom[5]
        assertEquals("？", period.getValue("swipeUpLabel").jsonPrimitive.content)
        assertEquals("`", slash.getValue("swipeUpLabel").jsonPrimitive.content)
        assertEquals("/", slash.getValue("label").jsonPrimitive.content)
        for (key in listOf(comma, period, slash)) {
            assertTrue(key.containsKey("swipeUp"))
            assertFalse(key.containsKey("swipeDown"))
            assertFalse(key.containsKey("swipeDownLabel"))
        }
        assertTrue(base.containsKey("wanxiang_t9"))
        assertTrue(base.containsKey("wanxiang_zrm_18keys"))
        assertTrue(base.containsKey("__layer__:数字"))
        assertEquals("默认", ThemeManager.DefaultTheme.name)
        assertEquals("default", ThemeKits.kitOf(ThemeManager.DefaultTheme)?.layoutProfile)
        val parsed = LayoutDataManager(appContext).loadDefaultPreset()
        assertTrue(parsed.keys.containsAll(listOf("default", "default:wanxiang_t9",
            "default:wanxiang_zrm_18keys", "default:__layer__:数字")))
    }

    @Test fun bundledFunctionKeyShadowsHaveVisibleContrast() {
        val root = requireNotNull(BuiltinKeyboardLayout.json)
        val base = root.getValue("default").jsonObject
        val keys = listOf("default", "__layer__:数字").flatMap { layout ->
            base.getValue(layout).jsonArray.flatMap { row -> row.jsonArray.map { it.jsonObject } }
        } + root.getValue("text_editor").jsonArray.flatMap { row -> row.jsonArray.map { it.jsonObject } }
        val colored = keys.filter { it["shadowColorMonet"]?.jsonPrimitive?.content?.startsWith("theme:role.") == true }
        assertEquals(23, colored.size)
        for (key in colored) {
            val theme = org.fcitx.fcitx5.android.data.theme.ThemePreset.Watercolor
            fun resolve(field: String): Int = requireNotNull(
                org.fcitx.fcitx5.android.input.keyboard.LayoutKeyColorResolver.resolveThemeTokenColor(
                    theme, key.getValue(field + "Monet").jsonPrimitive.content.removePrefix("theme:")))
            assertFalse(key.containsKey("backgroundColor"))
            assertFalse(key.containsKey("shadowColor"))
            assertFalse(key.containsKey("textColor"))
            val face = resolve("backgroundColor")
            val shadow = resolve("shadowColor")
            val difference = listOf(16, 8, 0).sumOf { shift ->
                ((face ushr shift) and 255) - ((shadow ushr shift) and 255)
            }
            assertTrue("Shadow should contrast with its key face: $key", difference >= 150)
        }
    }

    @Test fun defaultIconThemeUsesNineDotToolbarAndNativeNumericKeys() {
        val dir = File("src/main/assets/bundled")
        val icons = Json.parseToJsonElement(File(dir, "default_icon_theme.json").readText())
            .jsonObject.getValue("icons").jsonObject
        val untitled = Json.parseToJsonElement(File(dir, "icon_themes/无题.json").readText())
            .jsonObject.getValue("icons").jsonObject
        assertEquals(untitled.getValue("system.toolbar_toggle"), icons.getValue("system.toolbar_toggle"))
        assertFalse(icons.containsKey("keys.numpad"))
        assertFalse(icons.containsKey("keys.numpad.layout"))
        assertTrue(icons.containsKey("keys.backspace"))
    }

    @Test fun sixBundledIconThemesIncludeOriginalWatercolorAndClassicFallback() {
        val dir = File("src/main/assets/bundled")
        val registered = ReflectionHelpers.getField<List<String>>(BundledPresets, "iconThemeAssets")
        val names = setOf("水彩.json", "经典.json", "谷歌.json", "无题.json", "长日将烬.json")
        assertEquals(names, registered.toSet())
        assertEquals(names.size, registered.size)
        assertEquals(names, File(dir, "icon_themes").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" }.map { it.name }.toSet())
        val watercolor = File(dir, "icon_themes/水彩.json")
        val classic = File(dir, "icon_themes/经典.json")
        val parsedWatercolor = Json.decodeFromString<org.fcitx.fcitx5.android.data.theme.IconTheme>(watercolor.readText())
        val parsedClassic = Json.decodeFromString<org.fcitx.fcitx5.android.data.theme.IconTheme>(classic.readText())
        assertEquals("水彩", parsedWatercolor.name)
        assertEquals(93, parsedWatercolor.icons.size)
        assertEquals("经典", parsedClassic.name)
        assertEquals(org.fcitx.fcitx5.android.data.theme.IconTheme.default().copy(name = "经典"), parsedClassic)
        val retired = ReflectionHelpers.getField<Map<String, Set<String>>>(BundledPresets, "retiredPresetBlobs")
        assertFalse(retired.containsKey("icon_themes/水彩.json"))
        assertFalse(retired.containsKey("icon_themes/经典.json"))
        assertTrue(File(dir, "default_icon_theme.json").isFile)
    }

    @Test fun missingProfileReturnsToDefaultAndExistingUserLayoutIsPreserved() {
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val file = requireNotNull(UserConfigFiles.textKeyboardLayoutJson("user-edited"))
        file.parentFile?.mkdirs()
        file.writeText("{\"user\":true}")
        val previousProfile = profile.getValue()
        try {
            profile.setValue("user-edited")
            ReflectionHelpers.callInstanceMethod<Unit>(BundledPresets, "ensureLayoutProfile")
            assertEquals("user-edited", profile.getValue())
            file.delete()
            ReflectionHelpers.callInstanceMethod<Unit>(BundledPresets, "ensureLayoutProfile")
            assertEquals(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE, profile.getValue())
        } finally { file.delete(); profile.setValue(previousProfile) }
    }

    @Test fun oldStockLayoutIsRetiredButAnEqualLengthUserEditSurvives() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("layouts/retired-wanxiang-layout.json")!!
            .use { it.readBytes() }
        val old = requireNotNull(UserConfigFiles.textKeyboardLayoutJson("万象-9+18+26keys"))
        val profile = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val previousProfile = profile.getValue()
        try {
            old.parentFile?.mkdirs()
            val edited = bytes.copyOf().apply { this[0] = ' '.code.toByte() }
            old.writeBytes(edited)
            profile.setValue("万象-9+18+26keys")
            ReflectionHelpers.callInstanceMethod<Unit>(BundledPresets, "install")
            assertArrayEquals(edited, old.readBytes())
            assertEquals("万象-9+18+26keys", profile.getValue())
            old.writeBytes(bytes)
            ReflectionHelpers.callInstanceMethod<Unit>(BundledPresets, "install")
            assertFalse(old.exists())
            assertEquals("default", profile.getValue())
        } finally { old.delete(); profile.setValue(previousProfile) }
    }
}
