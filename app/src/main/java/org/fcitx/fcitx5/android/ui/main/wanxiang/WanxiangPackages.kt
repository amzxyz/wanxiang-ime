/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import kotlinx.serialization.Serializable
import org.fcitx.fcitx5.android.data.WanxiangProtection

/** Public release asset protocol; no upstream updater code or runtime is bundled. */
@Serializable
internal data class WanxiangSelection(
    val scheme: Scheme = Scheme.BASE,
    val auxiliary: String = "zrm",
    val channel: Channel = Channel.STABLE,
    val source: Source = Source.CNB,
    val scope: Scope = Scope.ALL,
    val protection: String = WanxiangProtection.DEFAULT,
    val extraDictionaries: List<String> = emptyList(),
    val components: List<WanxiangPackage.Kind>? = null
) {
    enum class Scheme { BASE, PRO, LITE, PURE }
    enum class Channel { STABLE, PREVIEW }
    enum class Source { CNB, GITHUB }
    enum class Scope { ALL, SCHEME, DICTIONARY, MODEL, EXTRA }

    // Old persisted selections are decoded only for migration. New work always
    // uses the EVO branch and explicit component checkboxes.
    fun asEvo(): WanxiangSelection = WanxiangSelection(
        source = Source.GITHUB, protection = protection, extraDictionaries = extraDictionaries,
        components = components ?: when (scope) {
            Scope.ALL -> listOf(WanxiangPackage.Kind.SCHEME, WanxiangPackage.Kind.DICTIONARY)
            Scope.SCHEME -> listOf(WanxiangPackage.Kind.SCHEME)
            Scope.DICTIONARY -> listOf(WanxiangPackage.Kind.DICTIONARY)
            Scope.MODEL -> listOf(WanxiangPackage.Kind.MODEL)
            Scope.EXTRA -> emptyList()
        })

    private fun evoPackages(selected: List<WanxiangPackage.Kind>): List<WanxiangPackage> {
        require(selected.distinct().size == selected.size && WanxiangPackage.Kind.EXTRA !in selected)
        val packages = listOf(WanxiangPackage.Kind.SCHEME, WanxiangPackage.Kind.DICTIONARY, WanxiangPackage.Kind.MODEL)
            .filter { it in selected }.map { kind ->
                when (kind) {
                    WanxiangPackage.Kind.MODEL -> WanxiangPackage(kind, EVO_MODEL_NAME, EVO_MODEL_URL)
                    else -> WanxiangPackage(kind, EVO_ARCHIVE_NAME, EVO_ARCHIVE_URL)
                }
            }
        return packages + extraDictionaries.map {
            WanxiangPackage(WanxiangPackage.Kind.EXTRA, "$it.dict.yaml",
                "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/dicts/$it.dict.yaml")
        }
    }

    fun packages(): List<WanxiangPackage> {
        require(auxiliary in auxiliaryCodes)
        WanxiangProtection(protection)
        require(extraDictionaries.size <= 200 && extraDictionaries.distinct().size == extraDictionaries.size &&
            extraDictionaries.all { it.matches(Regex("[a-z0-9][a-z0-9_-]{0,63}")) }) { "无效附加词库" }
        components?.let { return evoPackages(it) }
        val variant = when (scheme) {
            Scheme.PRO -> "$auxiliary-fuzhu"
            else -> scheme.name.lowercase(java.util.Locale.ROOT)
        }
        val schema = "rime-wanxiang-$variant.zip"
        val dictionary = if (scheme == Scheme.PRO) "pro-$variant-dicts.zip" else "$variant-dicts.zip"
        fun url(kind: WanxiangPackage.Kind, name: String): String = when (source) {
            Source.GITHUB -> when (kind) {
                WanxiangPackage.Kind.MODEL -> "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/$name"
                WanxiangPackage.Kind.SCHEME -> if (channel == Channel.STABLE)
                    "https://github.com/amzxyz/rime-wanxiang/releases/latest/download/$name"
                else "https://github.com/amzxyz/rime-wanxiang/releases/download/dict-nightly/$name"
                WanxiangPackage.Kind.DICTIONARY -> "https://github.com/amzxyz/rime-wanxiang/releases/download/dict-nightly/$name"
                WanxiangPackage.Kind.EXTRA -> "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/dicts/$name"
            }
            Source.CNB -> {
                val release = when (kind) {
                    WanxiangPackage.Kind.MODEL -> "download/model"
                    WanxiangPackage.Kind.SCHEME -> if (channel == Channel.STABLE) "latest/download" else "download/v1.0.0"
                    WanxiangPackage.Kind.DICTIONARY -> "download/v1.0.0"
                    WanxiangPackage.Kind.EXTRA -> error("Extra dictionaries use their own repository")
                }
                "https://cnb.cool/amzxyz/rime-wanxiang/-/releases/$release/$name"
            }
        }
        val main = listOf(
            WanxiangPackage.Kind.SCHEME to schema,
            WanxiangPackage.Kind.DICTIONARY to dictionary,
            WanxiangPackage.Kind.MODEL to MODEL_NAME
        ).filter { (kind, _) -> scope == Scope.ALL || scope.name == kind.name }
            .map { (kind, name) -> WanxiangPackage(kind, name, url(kind, name)) }
        val extras = if (scope in setOf(Scope.ALL, Scope.DICTIONARY, Scope.EXTRA)) extraDictionaries.map {
            WanxiangPackage(WanxiangPackage.Kind.EXTRA, "$it.dict.yaml",
                "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/dicts/$it.dict.yaml")
        } else emptyList()
        return main + extras
    }

    companion object {
        const val MODEL_NAME = "wanxiang-lts-zh-hans.gram" // legacy jobs only
        const val EVO_ARCHIVE_NAME = "rime-wanxiang-for-librime-evo.zip"
        const val EVO_ARCHIVE_URL = "https://github.com/amzxyz/rime-wanxiang/archive/refs/heads/rime-wanxiang-for-librime-evo.zip"
        const val EVO_MODEL_NAME = "wanxiang-lts-zh-hans.dlm"
        // Release contract: the EVO model must be published at this exact name.
        // Never fall back to the incompatible .gram asset.
        const val EVO_MODEL_URL = "https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/$EVO_MODEL_NAME"
        val auxiliaryCodes = listOf("zrm", "wx", "flypy", "moqi", "hanxin", "shouyou", "shyplus", "tiger", "wubi")
    }
}

internal data class WanxiangPackage(val kind: Kind, val name: String, val url: String) {
    enum class Kind { SCHEME, DICTIONARY, MODEL, EXTRA }
}
