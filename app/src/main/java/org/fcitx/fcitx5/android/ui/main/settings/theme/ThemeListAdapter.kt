/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.orderThemeLibrary
import splitties.views.dsl.core.Ui

abstract class ThemeListAdapter : RecyclerView.Adapter<ThemeListAdapter.ViewHolder>() {
    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    val entries = mutableListOf<Theme>()

    private var activeIndex = -1
    private var lightIndex = -1
    private var darkIndex = -1

    private fun entryAt(position: Int) = entries.getOrNull(position)

    private fun positionOf(theme: Theme? = null): Int {
        if (theme == null) return -1
        return positionOf(theme.name)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (holder.ui is ThemeThumbnailUi) {
            holder.ui.cleanup()
        }
    }

    fun setThemes(themes: List<Theme>) {
        updateThemes(themes)
    }

    private fun updateThemes(themes: List<Theme>, renamedFrom: String? = null, renamedTo: String? = null) {
        fun selectedName(index: Int) = entryAt(index)?.name?.let { if (it == renamedFrom) renamedTo else it }
        val activeName = selectedName(activeIndex)
        val lightName = selectedName(lightIndex)
        val darkName = selectedName(darkIndex)
        val previous = entries.toList()
        val updated = orderThemeLibrary(themes)
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = updated.size
            override fun areItemsTheSame(old: Int, new: Int): Boolean =
                previous[old].name == updated[new].name
            override fun areContentsTheSame(old: Int, new: Int): Boolean =
                previous[old] == updated[new]
        })
        entries.clear()
        entries.addAll(updated)
        activeIndex = positionOf(activeName)
        lightIndex = positionOf(lightName)
        darkIndex = positionOf(darkName)
        difference.dispatchUpdatesTo(this)
    }

    private fun positionOf(themeName: String?): Int {
        if (themeName == null) return -1
        val index = entries.indexOfFirst { it.name == themeName }
        return index
    }

    fun setSelectedThemes(active: Theme, light: Theme? = null, dark: Theme? = null) {
        val oldActive = entryAt(activeIndex)
        if (oldActive != active) {
            if (activeIndex >= 0) notifyItemChanged(activeIndex)
            activeIndex = positionOf(active)
            if (activeIndex >= 0) notifyItemChanged(activeIndex)
        }
        val oldLight = entryAt(lightIndex)
        if (oldLight != light) {
            if (lightIndex >= 0) notifyItemChanged(lightIndex)
            lightIndex = positionOf(light)
            if (lightIndex >= 0) {
                notifyItemChanged(lightIndex)
            }
        }
        val oldDark = entryAt(darkIndex)
        if (oldDark != dark) {
            if (darkIndex >= 0) notifyItemChanged(darkIndex)
            darkIndex = positionOf(dark)
            if (darkIndex >= 0) {
                notifyItemChanged(darkIndex)
            }
        }
    }

    fun prependTheme(it: Theme) {
        updateThemes(listOf(it) + entries)
    }

    fun removeTheme(name: String) {
        updateThemes(entries.filterNot { it.name == name })
    }

    fun replaceTheme(theme: Theme) {
        replaceTheme(theme.name, theme)
    }

    fun replaceTheme(oldName: String, theme: Theme) {
        updateThemes(listOf(theme) + entries.filterNot { it.name == oldName || it.name == theme.name }, oldName, theme.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ThemeThumbnailUi(parent.context, compact = true))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        (holder.ui as ThemeThumbnailUi).apply {
            val theme = entryAt(position)!!
            setTheme(theme)
            setChecked(
                when (position) {
                    darkIndex -> ThemeThumbnailUi.State.DarkMode
                    lightIndex -> ThemeThumbnailUi.State.LightMode
                    activeIndex -> ThemeThumbnailUi.State.Selected
                    else -> ThemeThumbnailUi.State.Normal
                }
            )
            val isRandom = ThemeManager.isRandomTheme(theme)
            root.setOnClickListener {
                onSelectTheme(theme)
            }
            root.setOnLongClickListener {
                if (theme is Theme.Custom && !isRandom) {
                    onExportTheme(theme)
                    true
                } else if (theme is Theme.Monet) {
                    onExportTheme(theme.toCustom())
                    true
                } else false
            }
            editButton.setOnClickListener {
                when {
                    isRandom -> onEditRandomTheme(theme as Theme.Custom)
                    theme is Theme.Custom -> onEditTheme(theme)
                    theme is Theme.Monet -> onEditMonetTheme(theme)
                }
            }
            exportButton.setOnClickListener {
                when {
                    theme is Theme.Custom && !isRandom -> onExportTheme(theme)
                    theme is Theme.Monet -> onExportTheme(theme.toCustom())
                }
            }
            setRandomAction(if (isRandom) ({ onRandomizeTheme() }) else null)
        }
    }

    override fun getItemCount() = entries.size

    abstract fun onSelectTheme(theme: Theme)

    abstract fun onEditTheme(theme: Theme.Custom)

    abstract fun onEditMonetTheme(theme: Theme.Monet)

    abstract fun onEditRandomTheme(theme: Theme.Custom)

    abstract fun onRandomizeTheme()

    abstract fun onExportTheme(theme: Theme.Custom)

}
