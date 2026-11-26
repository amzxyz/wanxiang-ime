/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    // Repository this build is made from; also the source of update checks
    // (see UpdateRepository.fetchLatestRelease) and the About page links.
    const val githubRepo = "https://github.com/amzxyz/wanxiang-ime"
    const val licenseSpdxId = "LGPL-2.1-or-later"
    const val licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"
    // Pin the code link to this APK; manuals are maintained on the docs branch.
    val sourceRevision = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
    val sourceUrl = "$githubRepo/tree/$sourceRevision"
    const val directRimeGuideUrl = "$githubRepo#使用说明"
    const val directRimeMaintainerUrl = "https://github.com/amzxyz/librime-evo"
    const val releasesUrl = "$githubRepo/releases"
    val privacyPolicyUrl = "$githubRepo#隐私与数据"
    val faqUrl = "$githubRepo#常见问题"
}
