/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.config

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber

/** A single APK resource for runtime, direct boot and layout editor defaults. */
object BuiltinKeyboardLayout {
    const val ASSET = "bundled/default_keyboard_layout.json"

    val json: JsonObject? by lazy {
        runCatching {
            appContext.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use {
                UserJsonConfigStore.parser.parseToJsonElement(it.readText()).jsonObject
            }
        }.onFailure { Timber.e(it, "Failed to read built-in keyboard layout") }.getOrNull()
    }
}
