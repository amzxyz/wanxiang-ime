/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ButtonsLayoutConfigTest {
    private val expectedStatusIds = listOf("theme", "one_handed_keyboard", "rime_sync", "reload_config")

    @Test fun expandedMenuDefaultsAndLegacyDefaultsUseTheNewFourButtonOrder() {
        assertEquals(expectedStatusIds, ButtonsLayoutConfig.default().displayedStatusAreaButtons().map { it.id })
        for (old in listOf(
            listOf("theme", "icon_theme", "reload_config", "virtual_keyboard", "one_handed_keyboard"),
            listOf("theme", "reload_config", "virtual_keyboard", "one_handed_keyboard")
        )) {
            val legacy = ButtonsLayoutConfig(emptyList(), old.map { ConfigurableButton(it) } +
                ConfigurableButton("input_method_options"))
            assertEquals(expectedStatusIds, legacy.displayedStatusAreaButtons().map { it.id })
        }
    }

    @Test fun customizedLegacyMenuKeepsItsOrderAndCustomPresentation() {
        val deploy = ConfigurableButton("reload_config", label = "Deploy", text = "D")
        val custom = ConfigurableButton("custom_1", text = "C")
        val legacy = ButtonsLayoutConfig(emptyList(), listOf(custom, deploy,
            ConfigurableButton("virtual_keyboard"), ConfigurableButton("icon_theme"),
            ConfigurableButton("input_method_options"), ConfigurableButton("theme")))
        assertEquals(listOf(custom, ConfigurableButton("rime_sync"), deploy, ConfigurableButton("theme")),
            legacy.displayedStatusAreaButtons())
        val movedSync = legacy.copy(kawaiiBarButtons = listOf(ConfigurableButton("rime_sync")))
        assertEquals(listOf(custom, deploy, ConfigurableButton("theme")), movedSync.displayedStatusAreaButtons())
    }

    @Test fun migratedAndSavedMenusDoNotReinsertAnIntentionallyRemovedSyncButton() {
        val config = ButtonsLayoutConfig.default().let { original ->
            original.copy(statusAreaButtons = original.statusAreaButtons.filterNot { it.id == "rime_sync" })
        }
        val restored = Json.decodeFromString<ButtonsLayoutConfig>(Json.encodeToString(config))
        assertEquals(listOf("theme", "one_handed_keyboard", "reload_config"),
            restored.displayedStatusAreaButtons().map { it.id })
        assertTrue(restored.copy(statusAreaButtons = emptyList()).displayedStatusAreaButtons().isEmpty())
    }

    @Test fun syncIsConfigurableButRedundantLegacyActionsAreOnlyKeptForCompatibility() {
        val actions = org.fcitx.fcitx5.android.input.action.ButtonAction
        assertSame(org.fcitx.fcitx5.android.input.action.SyncRimeAction, actions.fromId("rime_sync"))
        val ids = actions.allConfigurableActions.map { it.id }
        assertTrue("rime_sync" in ids)
        retiredStatusAreaButtonIds.forEach {
            assertFalse(it in ids)
            assertNotNull(actions.fromId(it))
        }
    }

    @Test fun removingTheDayNightControlSurvivesSavingAndReloading() {
        val config = ButtonsLayoutConfig.default().let { original ->
            original.copy(kawaiiBarButtons = original.kawaiiBarButtons.filterNot { it.id == "theme_toggle" })
        }
        val restored = Json.decodeFromString<ButtonsLayoutConfig>(Json.encodeToString(config))
        assertEquals(listOf("undo", "redo", "cursor_move", "floating_toggle", "clipboard"),
            restored.displayedKawaiiBarButtons().map { it.id })
    }

    @Test fun movingTheDayNightControlToStatusDoesNotCreateAnotherToolbarIcon() {
        val config = ButtonsLayoutConfig.default().let { original ->
            original.copy(kawaiiBarButtons = original.kawaiiBarButtons.filterNot { it.id == "theme_toggle" },
                statusAreaButtons = original.statusAreaButtons + ConfigurableButton("theme_toggle", text = "☀"))
        }
        val restored = Json.decodeFromString<ButtonsLayoutConfig>(Json.encodeToString(config))
        assertFalse(restored.displayedKawaiiBarButtons().any { it.id == "theme_toggle" })
        assertEquals(1, restored.statusAreaButtons.count { it.id == "theme_toggle" })
        assertEquals("☀", restored.statusAreaButtons.last().text)
    }

    @Test fun newDefaultsIncludeTheControlAndLegacyMoreDoesNotReplaceSavedButtons() {
        assertEquals(1, ButtonsLayoutConfig.default().displayedKawaiiBarButtons().count { it.id == "theme_toggle" })
        val configured = listOf(ConfigurableButton("clipboard", text = "剪"), ConfigurableButton("theme_toggle", text = "☀"))
        val config = ButtonsLayoutConfig(configured + ConfigurableButton("more"), emptyList())
        assertEquals(configured, config.displayedKawaiiBarButtons())
        assertTrue(config.copy(kawaiiBarButtons = emptyList()).displayedKawaiiBarButtons().isEmpty())
    }
}
