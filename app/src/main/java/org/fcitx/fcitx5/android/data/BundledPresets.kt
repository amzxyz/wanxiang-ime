/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File

/**
 * 内置资源（键盘布局 / 主题 / 图标主题 / 弹出定义）安装器。
 *
 * 这些资源随 APK 打包在 `assets/bundled/` 下，首次启动时解包到应用外部
 * 文件目录的对应位置，实现"开箱即用"：
 *
 * - `bundled/default_keyboard_layout.json` → 运行时内置默认布局，不释放独立 profile
 * - `bundled/keyboard_layouts/PopupPreset.json`               → 不落盘，作为运行时默认弹出定义
 * - `bundled/themes/<主题名>.json`                            → `theme/`（自定义主题目录）
 * - `bundled/icon_themes/<主题名>.json`                       → `icon_themes/`
 * - `bundled/fonts/<字体文件名>`                              → `fonts/`（「字体设定」读取的目录）
 *
 * 安装幂等且不覆盖用户修改：每个资源以"asset 路径 + 字节数"为版本标记记录在
 * SharedPreferences 中；默认不覆盖已存在的文件，显式登记更新的资源只在
 * 文件大小仍等于上一版内置内容时替换，保留用户自行修改的版本。
 * 登记更新还记录旧字节数，允许修正旧登记后重试仍存在的原版文件；更新资源须同步登记旧字节数。
 * 目标文件不存在时总是按原名重新写出：用户删除的内置资源会在下次安装时补回，
 * 版本标记只用于判断已存在的文件是否需要更新（见 [installAsset]）。
 */
object BundledPresets {

    private const val PREF_NAME = "bundled_presets"
    private const val KEY_INSTALLED = "installed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 已处理版本及更新来源标记；用于判断已存在的文件是否需要更新，不阻止补回已删除的文件。 */
    private val handled: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_INSTALLED, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun mark(vararg tags: String) {
        handled.addAll(tags)
        prefs.edit { putStringSet(KEY_INSTALLED, handled) }
    }

    /**
     * 「上一版内置内容」的字节大小，用于区分用户是否改过本地文件：
     * [installAsset] 只在 `dest.length()` 等于这里的登记值时才原位替换，
     * 长度不同即视为用户自己编辑过、保留用户版本。
     *
     * 因此每次更新某个资源后，要把这里的值改成**更新前那一版的字节数**
     * （不是新版本的字节数），否则新内容对存量用户永不生效；未登记的
     * 资源则只在目标文件不存在时才写出。
     */
    private val assetSizes = mapOf(
        "bundled/icon_themes/谷歌.json" to 68073
    )

    /**
     * 已退役的内置资源：升级后删掉设备上仍是原版内容（字节数等于这里登记值）的旧文件。
     *
     * 主题改名会留下旧文件名的那一份，它与新文件同名不同名地并存，主题列表里就会出现
     * 两张几乎一样的卡片。用户改过的文件（长度不同）保留，与 [installAsset] 的判定一致。
     */
    private val retiredAssets = mapOf(
        "theme/水彩（浅色）.json" to 850L,
        "icon_themes/蓝狐狸.json" to 56240L,
        "config/TextKeyboardLayout.蓝狐狸.json" to 19729L,
        "theme/蓝狐狸.json" to 709L
    )

    // Only remove byte-identical previously shipped presets. User-edited files survive.
    private val retiredPresetBlobs = mapOf(
        "config/TextKeyboardLayout.QM-朝花.json" to setOf("a0f3c5bcf5e4e906c72d41bf902ab6fb6b71f0ee"),
        "config/TextKeyboardLayout.Sandy-数字行26+万象九键.json" to setOf("77671de8bf4048f58ae2777d268bfebf524cd157"),
        "config/TextKeyboardLayout.九键布局.json" to setOf("adb48201e44eb608528c3a03c3528f200c741e18"),
        "config/TextKeyboardLayout.大同-9+18+26keys.json" to setOf("45b4cd3ade5251057f89a662346f2f2799f26d6c"),
        "config/TextKeyboardLayout.普蓝.json" to setOf("e9d2ab406b66e5ab402493c36b749157bb7aee11"),
        "config/TextKeyboardLayout.水彩.json" to setOf("5aadb7d598fa26dae167280d87907bce34a32453", "d6e92bb0878e9ba8696e39b2854ab01e783d0a87"),
        "config/TextKeyboardLayout.行之-26键.json" to setOf("ef35ebb2a1cf6580fe2c27694864dd9744097845"),
        "config/TextKeyboardLayout.行之-万象九键v2.json" to setOf("9becffbfc0cf2a193bbff3e74cdfeb2fdd36fb13"),
        "config/TextKeyboardLayout.万象-9+18+26keys.json" to setOf("21ef3cd97fbe1472160fdca8d4e1b7dc1c0ca513"),
        "theme/水彩.json" to setOf("930bb3dd32bf98d73acf6d4db1b865585e9ed3a0"),
        "theme/普蓝.json" to setOf("bc816f6f958d9935fd4ab44ea772965f69119dc8"),
        "icon_themes/普蓝.json" to setOf("bc424062b8d60a85c729eff71843359b386190f9")
    )

    private val themeAssets = listOf(
        "森林集·write.json",
        "长日将烬.json",
        "braun-minimal-dark.json",
        "braun-minimal-light.json",
        "Caramel Dawn.json",
        "Caramel Ember.json",
        "github-dark-dim.json",
        "industrial-orange-light.json",
        "isGboard.json",
        "sage-journey.json",
        "WeChat Dark.json",
        "wechat-light.json"
    )

    private val iconThemeAssets = listOf(
        "水彩.json",
        "经典.json",
        "谷歌.json",
        "无题.json",
        "长日将烬.json"
    )

    /**
     * 内置字体：安装到 `fonts/`，即「字体设定」列出可选字体的目录。
     * 成套主题引用的字体必须在这里登记，否则新装设备上主题会指向一个不存在的文件。
     */
    const val EDIT_ICON_FONT_ASSET = "bundled/fonts/keyboard-edit-icons.otf"
    private const val EDIT_ICON_FONT_FILE = "字体图标.otf"

    const val POPUP_PRESET_ASSET = "bundled/keyboard_layouts/PopupPreset.json"

    /**
     * 在后台安装内置资源。重复调用是安全的；安装失败只记录日志，不影响启动。
     * [onComplete] 在本次安装尝试结束后于后台线程调用。
     */
    fun installAsync(onComplete: () -> Unit = {}) {
        scope.launch {
            runCatching { install() }.onFailure {
                Timber.w(it, "Failed to install bundled presets")
            }
            onComplete()
        }
    }

    private fun install() {
        val extDir = appContext.getExternalFilesDir(null) ?: return
        var installedCount = 0

        // Default layout is read directly from the APK, with optional user overrides.
        // Remove old stock copies without touching a user's edited layout.
        retiredPresetBlobs.forEach { (path, hashes) ->
            val file = File(extDir, path)
            if (file.isFile && gitBlobSha(file) in hashes && file.delete()) {
                Timber.i("Retired bundled preset %s", path)
                installedCount += 1
            }
        }

        // 退役资源：只删除仍与上一版内置内容一致的文件，用户改过的保留。
        // 必须排在下面的改名迁移之前：迁移是否安全取决于旧文件是否还在——
        // 原版旧文件删掉才迁移选择，用户改过而留下的旧文件则保留其选择。
        retiredAssets.forEach { (path, size) ->
            val file = File(extDir, path)
            if (file.isFile && file.length() == size) {
                if (file.delete()) {
                    Timber.i("Retired bundled asset %s", path)
                    installedCount += 1
                }
            }
        }

        ensureLayoutProfile()

        // 主题：安装到 theme/（与用户自定义主题同目录）
        val themeDir = File(extDir, "theme").apply { mkdirs() }
        themeAssets.forEach { name ->
            installAsset("bundled/themes/$name", File(themeDir, name)).let { installedCount += it }
        }

        // 图标主题：安装到 icon_themes/
        val iconThemeDir = File(extDir, "icon_themes").apply { mkdirs() }
        iconThemeAssets.forEach { name ->
            installAsset("bundled/icon_themes/$name", File(iconThemeDir, name)).let { installedCount += it }
        }

        // 字体：安装到 fonts/（「字体设定」与成套主题的按键副字体都从这里取）
        val fontDir = UserConfigFiles.fontsDir() ?: File(extDir, "fonts").apply { mkdirs() }
        installedCount += installAsset(EDIT_ICON_FONT_ASSET, File(fontDir, EDIT_ICON_FONT_FILE))

        if (installedCount > 0) {
            Timber.i("Bundled presets installed %d file(s)", installedCount)
        }
    }

    /**
     * 安装单个 asset。返回 1 表示本次实际写出了文件，0 表示跳过（已处理/已存在/读取失败）。
     */
    private fun installAsset(assetPath: String, dest: File, assetBytes: ByteArray? = readAsset(assetPath)): Int {
        val bytes = assetBytes ?: return 0
        val tag = "$assetPath#${bytes.size}"
        val previousSize = assetSizes[assetPath]
        val updateTag = previousSize?.let { "$tag#from=$it" } ?: tag
        // A corrected previous-size registration must retry a previously skipped old file.
        // Existing legacy tags still protect files the user has edited, but a missing file
        // is always recreated: bundled presets should not depend on the user never deleting one.
        val missing = !dest.isFile
        if (!missing && tag in handled && (updateTag in handled || previousSize == null ||
                    dest.length() != previousSize.toLong())) return 0
        // 内容有更新的内置资源（assetSizes 中登记了上一版字节数）：只有用户从未改过
        // 本地文件（大小仍等于上一版内置内容）时才原位替换；否则保留用户版本。
        if (previousSize != null && dest.exists() && dest.length() != previousSize.toLong()) {
            mark(tag, updateTag)
            return 1
        }
        if (missing || !dest.exists() || previousSize != null) {
            runCatching {
                dest.parentFile?.mkdirs()
                val tmp = File(dest.absolutePath + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(dest)) {
                    dest.writeBytes(bytes)
                    tmp.delete()
                }
            }.onFailure {
                Timber.w(it, "Failed to install bundled asset %s", assetPath)
                return 0
            }
            Timber.d("Installed bundled asset %s", assetPath)
        }
        mark(tag, updateTag)
        return 1
    }

    private fun gitBlobSha(file: File): String? = runCatching {
        val bytes = file.readBytes()
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }.getOrNull()

    private fun readAsset(assetPath: String): ByteArray? {
        return runCatching {
            appContext.assets.open(assetPath).use { it.readBytes() }
        }.onFailure {
            Timber.w(it, "Failed to read bundled asset %s", assetPath)
        }.getOrNull()
    }

    /** Missing/retired profiles return to the built-in default. User files are never renamed. */
    private fun ensureLayoutProfile() {
        val preference = AppPrefs.getInstance().keyboard.textKeyboardLayoutProfile
        val current = preference.getValue()
        if (current != UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE &&
            UserConfigFiles.textKeyboardLayoutJson(current)?.isFile != true
        ) {
            preference.setValue(UserConfigFiles.DEFAULT_TEXT_KEYBOARD_LAYOUT_PROFILE)
        }
    }

    /**
     * 读取内置的 PopupPreset.json（默认弹出定义）。
     * 该文件不安装到用户目录：`config/PopupPreset.json` 一旦存在就会整体覆盖
     * 默认定义，因此内置定义只作为运行时 fallback 从 assets 直接读取。
     */
    fun readBundledPopupPreset(): Map<String, List<String>>? {
        val bytes = readAsset(POPUP_PRESET_ASSET) ?: return null
        return runCatching {
            val text = bytes.decodeToString()
            val stripped = org.fcitx.fcitx5.android.input.config.UserJsonConfigStore
                .stripLineComments(text)
            org.fcitx.fcitx5.android.input.config.UserJsonConfigStore.parser
                .decodeFromString<Map<String, List<String>>>(stripped)
        }.onFailure {
            Timber.w(it, "Failed to parse bundled popup preset")
        }.getOrNull()
    }
}
