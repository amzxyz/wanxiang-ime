/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.BundledWanxiang
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.rimeupdate.RimeSchemeUpdateActivity
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangUpdateActivity
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.setup

/**
 * 「方案更新」：先提供 APK 内置方案还原，再提供万象在线更新与自定义资源更新。
 */
class RimeSchemeUpdatesFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(context).apply {
            if (BundledWanxiang.available(context)) {
                addPreference(Preference(context).apply {
                    key = "restore_bundled_wanxiang"
                    isPersistent = false
                    setup(context.getString(R.string.bundled_wanxiang_restore),
                        context.getString(R.string.bundled_wanxiang_restore_summary)) {
                        startActivity(Intent(context, RimeImportActivity::class.java)
                            .putExtra(RimeImportActivity.EXTRA_BUNDLED_WANXIANG, true))
                    }
                    widgetLayoutResource = R.layout.preference_settings_navigation
                })
            }
            addPreference(R.string.wanxiang_update, R.string.wanxiang_update_summary) {
                startActivity(Intent(context, WanxiangUpdateActivity::class.java))
            }
            addPreference(R.string.scheme_update_title, R.string.scheme_update_summary) {
                startActivity(Intent(context, RimeSchemeUpdateActivity::class.java))
            }
        }
    }
}
