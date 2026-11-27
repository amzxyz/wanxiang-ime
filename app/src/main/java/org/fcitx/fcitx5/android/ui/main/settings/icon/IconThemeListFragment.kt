/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrBitmapUtil
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import org.fcitx.fcitx5.android.ui.common.withProgressLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.QrScanOptions
import splitties.dimensions.dp
import org.fcitx.fcitx5.android.utils.settingsColor
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding

class IconThemeListFragment : Fragment() {
    private companion object {
        private const val QR_PREVIEW_ICON_SIZE = 96

        /**
         * Largest icon-theme JSON accepted on import.
         *
         * These documents hold icon slot names and inline SVG; 1MB is already generous, and an
         * unbounded read on the main thread was the original problem (see D10).
         */
        private const val MAX_IMPORT_JSON_BYTES = 1024 * 1024
    }

    private val themes get() = IconThemeManager.iconThemes
    private val builtinDefault = IconTheme.default()
    private val adapter = ThemeAdapter()
    private val qrImporter = IconThemeQrImporter()

    private val onListChangeListener = IconThemeManager.OnIconThemeListChangeListener { refreshThemes() }
    private val onThemeChangeListener = IconThemeManager.OnIconThemeChangeListener { refreshThemes() }
    private val onKeyboardThemeChangeListener = ThemeManager.OnThemeChangeListener {
        adapter.notifyDataSetChanged()
    }

    private val jsonImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importJson(it) } }

    private val qrImageImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFromQrImage(it) } }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
        } else {
            Toast.makeText(requireContext(), getString(R.string.text_keyboard_layout_qr_camera_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result?.contents ?: return@registerForActivityResult
        addImportedChunkFromText(content)
    }

    private val zipImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importIconThemeZip(it) } }

    private var recycler: RecyclerView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        RecyclerView(requireContext()).apply {
            layoutManager = GridLayoutManager(context, 1)
            adapter = this@IconThemeListFragment.adapter
            applyNavBarInsetsBottomPadding()
            id = R.id.icon_theme_library_list
            recycler = this
        }

    override fun onStart() {
        super.onStart()
        IconThemeManager.addOnListChangeListener(onListChangeListener)
        IconThemeManager.addOnChangedListener(onThemeChangeListener)
        ThemeManager.addOnChangedListener(onKeyboardThemeChangeListener)
        IconThemeManager.initAsync()
        refreshThemes()
    }

    override fun onStop() {
        IconThemeManager.removeOnListChangeListener(onListChangeListener)
        IconThemeManager.removeOnChangedListener(onThemeChangeListener)
        ThemeManager.removeOnChangedListener(onKeyboardThemeChangeListener)
        super.onStop()
    }

    override fun onDestroyView() {
        recycler?.adapter = null
        recycler = null
        super.onDestroyView()
    }

    /**
     * Import an icon theme from a JSON document.
     *
     * The read + parse + write used to run on the main thread straight from the menu callback,
     * with no size limit at all (see D10). Now on IO, and capped: an icon theme is a small
     * document, so anything larger is not one.
     */
    private fun importJson(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val theme = withContext(Dispatchers.IO) {
                runCatching {
                    val json = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        val limited = ByteArray(MAX_IMPORT_JSON_BYTES + 1)
                        var read = 0
                        while (read < limited.size) {
                            val n = input.read(limited, read, limited.size - read)
                            if (n <= 0) break
                            read += n
                        }
                        if (read > MAX_IMPORT_JSON_BYTES) {
                            throw IllegalArgumentException("icon theme JSON exceeds $MAX_IMPORT_JSON_BYTES bytes")
                        }
                        String(limited, 0, read, Charsets.UTF_8)
                    } ?: throw IllegalStateException("Cannot open JSON")
                    IconThemeManager.importTheme(json)
                }
            }
            theme.onSuccess {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_imported, it.name), Toast.LENGTH_SHORT).show()
                refreshThemes()
            }.onFailure {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_import_json_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importFromQrImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.withProgressLoadingDialog(requireContext()) { updateProgress ->
            val chunks = try {
                withContext(Dispatchers.Default) {
                    JsonFileQrShareManager.decodeQrChunksFromImage(requireContext(), uri) { current, total ->
                        updateProgress(getString(R.string.qr_image_decode_progress, current, total))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.icon_theme_decode_qr_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@withProgressLoadingDialog
            }
            if (chunks.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.icon_theme_no_qr_found),
                    Toast.LENGTH_SHORT
                ).show()
                return@withProgressLoadingDialog
            }
            try {
                val theme = qrImporter.importImageChunks(chunks)
                showImportedTheme(theme)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_decode_qr_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImportedTheme(theme: IconTheme) {
        if (!isAdded || activity?.isFinishing == true || activity?.isDestroyed == true) return
        Toast.makeText(requireContext(), getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
        refreshThemes()
    }

    private fun startCameraScanImport() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun addImportedChunkFromText(raw: String) {
        viewLifecycleOwner.lifecycleScope.withProgressLoadingDialog(requireContext()) {
            val result = try {
                qrImporter.addScan(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_decode_qr_failed), Toast.LENGTH_SHORT).show()
                return@withProgressLoadingDialog
            }
            if (!isAdded || activity?.isFinishing == true || activity?.isDestroyed == true) return@withProgressLoadingDialog
            when (result) {
                IconThemeQrImporter.ScanResult.Invalid -> Toast.makeText(requireContext(),
                    getString(R.string.text_keyboard_layout_qr_invalid_payload), Toast.LENGTH_SHORT).show()
                is IconThemeQrImporter.ScanResult.WrongType -> Toast.makeText(requireContext(),
                    getString(R.string.text_keyboard_layout_qr_type_mismatch,
                        getString(R.string.qr_payload_type_icon_theme), when (result.type) {
                            LayoutQrTransferCodec.TRANSFER_TYPE_THEME -> getString(R.string.qr_payload_type_theme)
                            LayoutQrTransferCodec.TRANSFER_TYPE_LAYOUT -> getString(R.string.qr_payload_type_layout)
                            LayoutQrTransferCodec.TRANSFER_TYPE_POPUP -> getString(R.string.qr_payload_type_popup)
                            else -> getString(R.string.qr_payload_type_unknown)
                        }), Toast.LENGTH_SHORT).show()
                is IconThemeQrImporter.ScanResult.Progress -> {
                    val progress = result.progress
                    if (progress.duplicate) Toast.makeText(requireContext(),
                        getString(R.string.text_keyboard_layout_qr_duplicate_chunk), Toast.LENGTH_SHORT).show()
                    Toast.makeText(requireContext(),
                        getString(R.string.text_keyboard_layout_qr_scan_progress, progress.current, progress.total),
                        Toast.LENGTH_SHORT).show()
                    if (result.theme != null) showImportedTheme(result.theme)
                    else cameraScanLauncher.launch(QrScanOptions.forPrompt(getString(R.string.text_keyboard_layout_qr_scan_prompt)))
                }
            }
        }
    }

    private fun refreshThemes() {
        adapter.notifyDataSetChanged()
    }

    private inner class ThemeAdapter : RecyclerView.Adapter<ThemeAdapter.VH>() {
        override fun getItemCount() = themes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ThemeThumbnailUi(parent.context))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val theme = themes[position]
            val isDefault = theme.name == builtinDefault.name
            holder.ui.bind(theme, theme.name == IconThemeManager.activeTheme.name, isDefault)
            holder.ui.setEditAction(visible = !isDefault) {
                startActivity(Intent(requireContext(), IconThemeEditorActivity::class.java)
                    .putExtra("icon_theme_name", theme.name))
            }
            holder.ui.root.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current != RecyclerView.NO_POSITION) {
                    themes.getOrNull(current)?.let(IconThemeManager::selectTheme)
                }
            }
        }

        inner class VH(val ui: ThemeThumbnailUi) : RecyclerView.ViewHolder(ui.root)
    }

    internal fun showCreateThemeMenu() {
        val actions = arrayOf(
            getString(R.string.new_icon_theme),
            getString(R.string.icon_theme_duplicate),
            getString(R.string.icon_theme_import_json),
            getString(R.string.text_keyboard_layout_qr_import_scan),
            getString(R.string.icon_theme_import_qr_image),
            getString(R.string.icon_theme_import_zip),
            getString(R.string.icon_theme_share_title)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.icon_theme_new))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(requireContext(), IconThemeEditorActivity::class.java))
                    1 -> promptDuplicateTheme()
                    2 -> jsonImportLauncher.launch("application/json")
                    3 -> startCameraScanImport()
                    4 -> qrImageImportLauncher.launch("image/*")
                    5 -> zipImportLauncher.launch("application/zip")
                    6 -> promptShareTheme()
                }
            }
            .show()
    }

    private fun promptDuplicateTheme() {
        val customThemes = themes
        val names = customThemes.map {
            if (it.name == builtinDefault.name) getString(R.string.default_) else it.name
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.icon_theme_duplicate))
            .setItems(names) { _, which ->
                val source = customThemes[which]
                val duplicated = source.copy(name = generateCopyName(source.name))
                IconThemeManager.saveTheme(duplicated)
                refreshThemes()
                Toast.makeText(requireContext(), getString(R.string.icon_theme_duplicated), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(requireContext(), IconThemeEditorActivity::class.java)
                        .putExtra("icon_theme_name", duplicated.name)
                )
            }
            .show()
    }

    private fun promptShareTheme() {
        val customThemes = themes.drop(1)
        if (customThemes.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.icon_theme_no_custom_theme), Toast.LENGTH_SHORT).show()
            return
        }
        val names = customThemes.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.icon_theme_share_title))
            .setItems(names) { _, which ->
                val theme = customThemes[which]
                val hasFiles = IconThemeManager.collectFileReferences(theme).isNotEmpty()
                if (hasFiles) {
                    shareThemeAsZip(theme)
                } else {
                    exportAsQrLongImage(theme)
                }
            }
            .show()
    }

    private fun shareThemeAsZip(theme: IconTheme) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.Default) {
                    val dir = java.io.File(requireContext().cacheDir, "shared").also { it.mkdirs() }
                    val zipFile = java.io.File(dir, "icon_theme_${theme.name.replace(Regex("[/\\\\:*?\"<>|]"), "_")}.zip")
                    java.io.FileOutputStream(zipFile).use { out ->
                        IconThemeManager.exportThemeToZip(theme, out).getOrThrow()
                    }
                    androidx.core.content.FileProvider.getUriForFile(
                        requireContext(),
                        "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                        zipFile
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.icon_theme_share_title)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_export_zip_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importIconThemeZip(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val theme = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        IconThemeManager.importThemeFromZip(input).getOrThrow()
                    } ?: throw IllegalStateException("Cannot open ZIP")
                }
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
                refreshThemes()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable, not Exception: a malformed archive could previously surface as an
                // OutOfMemoryError, which is an Error and escaped as a process crash (see A6).
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_import_zip_failed, t.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderThumbnailBitmap(theme: IconTheme, sizePx: Int): Bitmap? {
        val svgContent = theme.thumbnailSvg?.takeIf { it.isNotBlank() }
            ?: IconThemeManager.buildThemeThumbnailSvg(theme.icons)
            ?: return null
        return runCatching {
            val svg = com.caverock.androidsvg.SVG.getFromString(svgContent)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()))
            bitmap
        }.getOrNull()
    }

    private fun exportAsQrLongImage(theme: IconTheme) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bundle = withContext(Dispatchers.Default) { IconThemeQrTransferCodec.encodeIconThemeToChunks(theme) }
                val labels = JsonFileQrShareManager.buildChunkLabels(bundle, getString(R.string.qr_payload_type_icon_theme), theme.name)
                val image = withContext(Dispatchers.Default) {
                    val preview = renderThumbnailBitmap(theme, QR_PREVIEW_ICON_SIZE)?.let { icon ->
                        val strip = LayoutQrBitmapUtil.composeCenteredPreviewStrip(icon, QR_PREVIEW_ICON_SIZE)
                        icon.recycle()
                        strip
                    }
                    val composed = LayoutQrBitmapUtil.composeLongImageStreamingWithPreview(
                        bundle.chunks.map { it.encode() }, labels, preview
                    )
                    if (preview != null && !preview.isRecycled) preview.recycle()
                    composed
                }
                // saveLongImageToShareCache now suspends (PNG encode is on IO) and sanitizes
                // the prefix itself, so a theme name with path characters is safe here.
                val uri = JsonFileQrShareManager.saveLongImageToShareCache(
                    requireContext(), image, "icon_theme_${theme.name}")
                if (!image.isRecycled) image.recycle()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.icon_theme_share_title)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    getString(R.string.icon_theme_export_qr_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateCopyName(name: String): String {
        val base = "$name (copy)"
        if (themes.none { it.name == base }) return base
        var i = 2
        while (themes.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

}

class ThemeThumbnailUi(private val context: android.content.Context) {
    val root: FrameLayout = FrameLayout(context).apply {
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        minimumHeight = context.dp(88)
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(5), context.dp(22), context.dp(5), context.dp(10))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private val previewSlots = listOf(
        "keys.capslock.none" to R.drawable.ic_capslock_none,
        "keys.backspace" to R.drawable.ic_baseline_backspace_24,
        "keys.language" to R.drawable.ic_baseline_language_24,
        "keys.return.default" to R.drawable.ic_baseline_keyboard_return_24
    )
    private val previews = previewSlots.map {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
            background = GradientDrawable().apply {
                cornerRadius = context.dp(5).toFloat()
                setColor(context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            }
        }
    }
    private val previewRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        previews.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, context.dp(25), 1f).apply {
                if (index > 0) marginStart = context.dp(2)
            })
        }
    }

    private val nameText = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        maxLines = 2
        setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
        setTextColor(settingsColor(android.R.attr.textColorPrimary))
    }

    private val activeIndicator = TextView(context).apply {
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(settingsColor(android.R.attr.textColorSecondary))
    }

    private val editButton = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.TOP or Gravity.END)
        setPadding(context.dp(10), context.dp(10), context.dp(6), context.dp(6))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_edit_24)
        imageTintList = ColorStateList.valueOf(context.settingsColor(android.R.attr.textColorSecondary))
        contentDescription = context.getString(R.string.edit_icon_theme)
    }

    private val checkMark = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(22), context.dp(22), Gravity.TOP or Gravity.START)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_check_circle_24)
        visibility = android.view.View.GONE
    }

    init {
        content.addView(previewRow, LinearLayout.LayoutParams(-1, -2))
        content.addView(nameText)
        content.addView(activeIndicator)
        root.addView(content)
        root.addView(checkMark)
        root.addView(editButton)
    }

    private fun applySelectionBackground(isActive: Boolean) {
        val accent = context.settingsColor(android.R.attr.colorAccent)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12f)
            setColor(context.settingsColor(com.google.android.material.R.attr.colorSurfaceBright))
            setStroke(context.dp(if (isActive) 2 else 1), if (isActive) accent
                else context.settingsColor(com.google.android.material.R.attr.colorOutlineVariant))
        }
        content.background = background
        checkMark.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
        checkMark.imageTintList = ColorStateList.valueOf(accent)
    }

    fun bind(theme: IconTheme, isActive: Boolean, isDefault: Boolean) {
        val accent = context.settingsColor(android.R.attr.colorAccent)
        val primary = context.settingsColor(android.R.attr.textColorPrimary)
        previews.forEachIndexed { index, preview ->
            val (slot, fallback) = previewSlots[index]
            val value = theme.icons[slot]
            val drawable = when {
                ButtonIconFile.isFileIcon(value) -> value?.let(ButtonIconFile::loadDrawable)
                value != null -> SlotRowUi.renderSvgPreview(context, value, 24)
                else -> null
            }
            if (drawable != null) {
                preview.setImageDrawable(drawable)
                preview.imageTintList = if (ButtonIconFile.isFileIcon(value) && value != null &&
                    ButtonIconFile.shouldTintIcon(value)) ColorStateList.valueOf(primary) else null
            } else {
                preview.setImageResource(fallback)
                preview.imageTintList = ColorStateList.valueOf(primary)
            }
        }
        nameText.text = if (isDefault) context.getString(R.string.default_) else theme.name
        nameText.setTextColor(if (isActive) accent else primary)
        activeIndicator.visibility = android.view.View.GONE
        applySelectionBackground(isActive)
        setEditAction(visible = !isDefault, onClick = null)
    }

    fun setEditAction(visible: Boolean, onClick: (() -> Unit)?) {
        editButton.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (visible && onClick != null) {
            editButton.setOnClickListener { onClick() }
        } else {
            editButton.setOnClickListener(null)
        }
    }
}
