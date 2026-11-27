/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.utils.settingsColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import java.io.File

/** Only the explicitly edited slot changes; an unreadable configuration must never be overwritten. */
internal fun updateKeyboardFontSetting(key: String, value: List<String>): Result<Boolean> =
    updateKeyboardFontSettings(mapOf(key to value))

internal fun mergeKeyboardFontSettings(
    old: Map<String, List<String>>, changes: Map<String, List<String>>
): Map<String, List<String>> = old.toMutableMap().apply {
    changes.forEach { (key, value) ->
        if (value.isEmpty()) remove(key) else put(key, value.toList())
    }
}

internal fun updateKeyboardFontSettings(changes: Map<String, List<String>>): Result<Boolean> =
    ConfigProviders.readFontsetPathMapSnapshot().mapCatching { snapshot ->
        val old = snapshot?.value.orEmpty()
        val updated = mergeKeyboardFontSettings(old, changes)
        if (old == updated) false else {
            ConfigProviders.provider.writeFontsetPathMap(updated).getOrThrow()
            true
        }
    }

/** Global font settings live in the keyboard-style preference list, not in color themes. */
internal class KeyboardFontSettings(private val ctx: Context, private val onChanged: () -> Unit) {
    private data class FontEntry(
        val key: String, @StringRes val titleRes: Int, @StringRes val sampleRes: Int,
        val defaultSize: Float? = null
    )

    private val fontsDir get() = ConfigProviders.provider.fontsetFile()?.parentFile
    private val entries = listOf(
        FontEntry("font", R.string.fontset_entry_font, R.string.fontset_sample_main),
        FontEntry("key_main_font", R.string.fontset_entry_key_main, R.string.fontset_sample_main, 23f),
        FontEntry("key_alt_font", R.string.fontset_entry_key_alt, R.string.fontset_sample_alt, 10.67f),
        FontEntry("cand_font", R.string.fontset_entry_candidate, R.string.fontset_sample_candidate, FontProviders.DEFAULT_CANDIDATE_FONT_SIZE),
        FontEntry(FontProviders.KEY_COMMENT_FONT, R.string.fontset_entry_comment, R.string.fontset_sample_comment, FontProviders.DEFAULT_COMMENT_FONT_SIZE),
        FontEntry("popup_key_font", R.string.fontset_entry_popup_key, R.string.fontset_sample_popup, 23f),
        FontEntry("preedit_font", R.string.fontset_entry_preedit, R.string.fontset_sample_preedit, 16f)
    )
    private val rows = mutableMapOf<String, Preference>()

    fun install(screen: PreferenceScreen) {
        rows.clear()
        val category = PreferenceCategory(ctx).apply {
            key = "keyboard_fonts"
            setTitle(R.string.edit_fontset)
            order = Int.MIN_VALUE + 1
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        entries.forEach { entry ->
            val row = Preference(ctx).apply {
                key = preferenceKey(entry.key)
                setTitle(entry.titleRes)
                isPersistent = false
                isIconSpaceReserved = false
                setOnPreferenceClickListener {
                    openFontEditor(entry)
                    true
                }
            }
            category.addPreference(row)
            rows[entry.key] = row
        }
        refresh()
    }

    fun refresh() {
        val snapshot = ConfigProviders.readFontsetPathMapSnapshot()
        if (snapshot.isFailure) {
            rows.values.forEach { it.isEnabled = false }
            return
        }
        val values = snapshot.getOrNull()?.value.orEmpty()
        entries.forEach { entry ->
            rows[entry.key]?.apply {
                isEnabled = true
                summary = fontSummary(ctx, values, entry.key, entry.defaultSize)
            }
        }
    }

    private fun readValues(): Map<String, List<String>>? =
        ConfigProviders.readFontsetPathMapSnapshot().fold(
            onSuccess = { it?.value.orEmpty() },
            onFailure = { ctx.toast(it); null }
        )

    private fun save(changes: Map<String, List<String>>): Boolean {
        return updateKeyboardFontSettings(changes).fold(onSuccess = { changed ->
            if (changed) {
                FontProviders.markNeedsRefresh()
                FcitxDaemon.getFirstConnectionOrNull()?.let { connection ->
                    runCatching { connection.runIfReady { reloadConfig() } }
                }
                refresh()
                onChanged()
            }
            true
        }, onFailure = { ctx.toast(it); false })
    }

    private fun openFontEditor(entry: FontEntry) {
        val availableFonts = fontsDir?.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "ttc", "otc") }
            .map { it.name }.sorted()
        val values = readValues() ?: return
        val current = values[entry.key].orEmpty().distinct()
        val selectedOrder = current.toMutableList()
        val allOrdered = mutableListOf<String>().apply {
            addAll(selectedOrder)
            addAll(availableFonts.filter { it !in selectedOrder })
        }

        val sizeKey = "${entry.key}_size"
        val defaultSize = entry.defaultSize
        val currentSize = defaultSize?.let {
            FontProviders.parseConfiguredSize(values[sizeKey]?.firstOrNull()) ?: it
        }
        var pendingSize = currentSize
        var resetSize = false
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
        }
        val previewText = TextView(ctx).apply {
            text = ctx.getString(entry.sampleRes)
            textSize = currentSize ?: 23f
            gravity = android.view.Gravity.CENTER
            minHeight = ctx.dp(64)
            maxLines = 2
            setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        }
        fun refreshPreview() {
            val inherited = if (entry.key == "font") emptyList() else
                values["font"].orEmpty()
            val candidate = if (entry.key == FontProviders.KEY_COMMENT_FONT)
                values["cand_font"].orEmpty() else emptyList()
            previewText.typeface = buildTypeface(selectedOrder)
                ?: buildTypeface(candidate)
                ?: buildTypeface(inherited)
                ?: (if (entry.key == "key_alt_font") runCatching {
                    Typeface.createFromAsset(ctx.assets,
                        org.fcitx.fcitx5.android.data.BundledPresets.EDIT_ICON_FONT_ASSET)
                }.getOrNull() else null)
                ?: Typeface.DEFAULT
        }
        refreshPreview()
        container.addView(previewText)

        var sizeDisplay: TextView? = null
        var sizeSlider: org.fcitx.fcitx5.android.ui.common.SettingsSlider? = null
        if (currentSize != null) {
            val label = TextView(ctx).apply {
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                text = ctx.getString(R.string.font_size_value, formatFontSize(currentSize))
            }
            sizeDisplay = label
            container.addView(label)
            val slider = org.fcitx.fcitx5.android.ui.common.SettingsSlider(ctx).apply {
                max = 64
                progress = (currentSize - 8f).toInt().coerceIn(0, max)
                setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            }
            sizeSlider = slider
            slider.setOnSeekBarChangeListener(object : org.fcitx.fcitx5.android.ui.common.SettingsSlider.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: org.fcitx.fcitx5.android.ui.common.SettingsSlider, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val size = 8f + progress
                    pendingSize = size
                    resetSize = false
                    label.text = ctx.getString(R.string.font_size_value, formatFontSize(size))
                    previewText.textSize = size
                }
                override fun onStartTrackingTouch(seekBar: org.fcitx.fcitx5.android.ui.common.SettingsSlider) {}
                override fun onStopTrackingTouch(seekBar: org.fcitx.fcitx5.android.ui.common.SettingsSlider) {}
            })
            container.addView(slider)
        }
        container.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.keyboard_font_choose_type)
            textSize = 14f
            setPadding(0, ctx.dp(8), 0, 0)
        })
        container.addView(TextView(ctx).apply {
            text = ctx.getString(if (allOrdered.isEmpty()) R.string.no_fonts_found else R.string.fontset_editor_hint)
            textSize = 12f
            setTextColor(ctx.settingsColor(android.R.attr.textColorSecondary))
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
        })

        class FontItemHolder(
            row: LinearLayout,
            val order: TextView,
            val checkbox: CheckBox
        ) : RecyclerView.ViewHolder(row)

        val adapter = object : RecyclerView.Adapter<FontItemHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FontItemHolder {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pv = ctx.dp(4)
                    setPadding(0, pv, 0, pv)
                }
                val orderLabel = TextView(ctx).apply {
                    width = ctx.dp(24)
                }
                val checkBox = MaterialCheckBox(ctx)
                row.addView(orderLabel)
                row.addView(
                    checkBox,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                return FontItemHolder(row, orderLabel, checkBox)
            }

            override fun getItemCount(): Int = allOrdered.size

            override fun onBindViewHolder(holder: FontItemHolder, position: Int) {
                val fontName = allOrdered[position]
                val selectedIndex = selectedOrder.indexOf(fontName)
                val selected = selectedIndex >= 0

                holder.order.text = if (selected) "${selectedIndex + 1}." else ""

                holder.checkbox.setOnCheckedChangeListener(null)
                holder.checkbox.text = fontName
                holder.checkbox.isChecked = selected
                holder.checkbox.setOnCheckedChangeListener { _, checked ->
                    val adapterPos = holder.bindingAdapterPosition
                    if (adapterPos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                    val name = allOrdered[adapterPos]
                    val currentlySelected = name in selectedOrder
                    if (checked) {
                        if (!currentlySelected) {
                            allOrdered.removeAt(adapterPos)
                            val insertPos = selectedOrder.size
                            selectedOrder.add(name)
                            allOrdered.add(insertPos, name)
                            // 精确刷新受影响的 item
                            notifyItemRemoved(adapterPos)
                            notifyItemInserted(insertPos)
                            notifyItemRangeChanged(0, selectedOrder.size)
                        }
                    } else {
                        if (currentlySelected) {
                            selectedOrder.remove(name)
                            allOrdered.removeAt(adapterPos)
                            allOrdered.add(name)
                            // 精确刷新受影响的 item
                            notifyItemRemoved(adapterPos)
                            notifyItemInserted(allOrdered.size - 1)
                            notifyItemRangeChanged(0, selectedOrder.size)
                        }
                    }
                    refreshPreview()
                }
            }
        }

        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        }

        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false

                val selectedCount = selectedOrder.size
                if (from >= selectedCount || to >= selectedCount) return false

                val moving = allOrdered.removeAt(from)
                allOrdered.add(to, moving)

                selectedOrder.clear()
                selectedOrder.addAll(allOrdered.take(selectedCount))

                adapter.notifyItemMoved(from, to)
                // 只刷新受影响的 item 范围
                val minPos = minOf(from, to)
                val maxPos = maxOf(from, to)
                adapter.notifyItemRangeChanged(minPos, maxPos - minPos + 1)
                refreshPreview()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // 高亮拖拽中的 item
                    viewHolder.itemView.alpha = 0.85f
                    viewHolder.itemView.translationZ = 10f
                    // 添加背景色增强反馈
                    viewHolder.itemView.setBackgroundColor(
                        ctx.settingsColor(android.R.attr.colorControlHighlight)
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 恢复外观
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.translationZ = 0f
                viewHolder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // 启用长按拖拽
            override fun isLongPressDragEnabled(): Boolean = true
        })
        itemTouchHelper.attachToRecyclerView(recycler)

        // The font list scrolls inside the same dialog; the size slider and
        // sample stay visible. The dialog also works with no imported fonts.
        if (allOrdered.isNotEmpty()) {
            val listHeight = minOf(ctx.dp(240), (ctx.resources.displayMetrics.heightPixels * 0.30f).toInt())
            container.addView(recycler, LinearLayout.LayoutParams(-1, listHeight))
        }
        val scroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = false
            addView(container, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        // Keep both edits as a draft. One confirmation writes a single
        // merged snapshot; cancel never touches the existing fontset.
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(entry.titleRes)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reset, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val changes = mutableMapOf<String, List<String>>()
                if (selectedOrder != current) changes[entry.key] = selectedOrder.toList()
                if (currentSize != null) {
                    if (resetSize) changes[sizeKey] = emptyList()
                    else if (pendingSize != currentSize) {
                        pendingSize?.let { changes[sizeKey] = listOf(it.toString()) }
                    }
                }
                if (changes.isEmpty() || save(changes)) dialog.dismiss()
            }
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                selectedOrder.clear()
                allOrdered.clear()
                allOrdered.addAll((current + availableFonts).distinct().sorted())
                adapter.notifyDataSetChanged()
                if (defaultSize != null) {
                    resetSize = true
                    pendingSize = defaultSize
                    sizeSlider?.progress = (defaultSize - 8f).toInt().coerceIn(0, 64)
                    sizeDisplay?.text = ctx.getString(R.string.font_size_value, formatFontSize(defaultSize))
                    previewText.textSize = defaultSize
                }
                refreshPreview()
            }
        }
        dialog.show()
    }

    private fun buildTypeface(fontNames: List<String>): Typeface? {
        val dir = fontsDir ?: return null
        val files = fontNames.map { File(dir, it) }.filter { it.isFile }
        if (files.isEmpty()) return null

        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val firstFont = Font.Builder(files.first()).build()
                var builder = Typeface.CustomFallbackBuilder(
                    FontFamily.Builder(firstFont).build()
                )
                files.drop(1).forEach { file ->
                    val family = FontFamily.Builder(Font.Builder(file).build()).build()
                    builder = builder.addCustomFallback(family)
                }
                builder.build()
            } else {
                Typeface.createFromFile(files.first())
            }
        }.getOrNull()
    }

    companion object {
        const val FIRST_KEY = "keyboard_font_font"
        fun preferenceKey(slot: String): String = "keyboard_font_$slot"
        fun isPreferenceKey(key: String): Boolean = key.startsWith("keyboard_font_")

        internal fun fontSummary(
            context: Context, values: Map<String, List<String>>, key: String, defaultSize: Float?
        ): String {
            val family = values[key].orEmpty().filter { it.isNotBlank() }
                .joinToString(", ").ifEmpty { context.getString(R.string.default_) }
            // Base font only defines a fallback family, not a global font size.
            if (defaultSize == null) return family
            val size = FontProviders.parseConfiguredSize(values["${key}_size"]?.firstOrNull())
                ?: defaultSize
            val sizeLabel = context.getString(R.string.font_size_value, formatFontSize(size))
            return "$sizeLabel · $family"
        }

        internal fun formatFontSize(size: Float): String {
            if (!size.isFinite()) return size.toString()
            return java.math.BigDecimal(size.toString()).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()
        }
    }
}
