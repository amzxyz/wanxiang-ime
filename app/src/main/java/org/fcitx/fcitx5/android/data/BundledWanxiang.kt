/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.directrime.ProfileArchive
import java.io.File
import java.security.MessageDigest

/** APK-only scheme source. Never participates in shared data synchronization. */
internal object BundledWanxiang {
    private const val DIRECTORY = "bundled/rime"
    private const val MAX_BYTES = 512L * 1024 * 1024

    @Serializable
    private data class Manifest(
        val repository: String,
        val branch: String,
        val commit: String,
        val sha256: String,
        val files: Int,
    )

    fun available(context: Context): Boolean {
        val names = context.assets.list(DIRECTORY).orEmpty()
        return "wanxiang.zip" in names && "manifest.json" in names
    }

    /** The returned temporary ZIP is owned and removed by the caller. */
    fun copyArchive(context: Context): File {
        val manifest = context.assets.open("$DIRECTORY/manifest.json").bufferedReader().use {
            Json.decodeFromString<Manifest>(it.readText())
        }
        require(manifest.repository == "amzxyz/rime-wanxiang" &&
            manifest.branch == "rime-wanxiang-for-librime-evo" &&
            manifest.commit.matches(Regex("[0-9a-f]{40}")) &&
            manifest.sha256.matches(Regex("[0-9a-f]{64}")) && manifest.files > 0) {
            "内置万象方案来源无效"
        }
        val directory = File(context.cacheDir, "rime-import")
        check(directory.isDirectory || directory.mkdirs())
        val archive = File.createTempFile("bundled-wanxiang-", ".zip", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("$DIRECTORY/wanxiang.zip").use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        require(bytes <= MAX_BYTES)
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == manifest.sha256) {
                "内置万象方案校验失败"
            }
            return archive
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
    }

    /** First installation only. Publish a whole directory atomically, never merge into user data. */
    fun installInitial(context: Context, target: File) {
        val archive = copyArchive(context)
        try {
            installInitialArchive(archive, target)
        } finally {
            archive.delete()
        }
    }

    internal fun installInitialArchive(archive: File, target: File) {
        check(!target.exists()) { "默认方案目录已经存在" }
        val staging = File.createTempFile(".bundled-wanxiang-", ".import", target.parentFile!!)
        check(staging.delete())
        try {
            archive.inputStream().use { ProfileArchive.extract(it, staging) }
            check(!target.exists() && staging.renameTo(target)) { "无法安装内置万象方案" }
        } finally {
            staging.deleteRecursively()
        }
    }
}
