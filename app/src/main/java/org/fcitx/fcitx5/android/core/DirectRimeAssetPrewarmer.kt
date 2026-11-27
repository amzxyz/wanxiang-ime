/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import org.fcitx.fcitx5.android.utils.LogTag
import org.fcitx.fcitx5.android.utils.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** Warms read-only file pages outside the interpreter and input queues. */
internal class DirectRimeAssetPrewarmer(private val context: Context, private val scope: CoroutineScope) {
    data class Assets(val profile: File, val shared: File, val absolutePaths: Array<String>)

    private val io = Dispatchers.IO.limitedParallelism(1)
    private var blocked = false
    private var task: Job? = null

    @Synchronized fun inputStarted() {
        blocked = true
        task?.cancel()
    }

    /** A new engine initialization/maintenance cycle; never a keyboard focus change. */
    @Synchronized fun invalidate() {
        task?.cancel()
        task = null
        blocked = false
    }

    @Synchronized fun request(target: () -> Assets?, release: () -> Unit) {
        if (blocked || task != null) return
        task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Skip native mapping too on low-memory, power-save or hot devices.
                if (budget() <= 0) return@launch
                ensureActive()
                val assets = target() ?: return@launch
                val pressure = withContext(io) { warm(assets, coroutineContext) }
                ensureActive()
                if (pressure) release()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logx.d(LogTag.RimeAssetPrewarm, "Optional asset prewarm stopped", e)
            }
        }.also { it.start() }
    }

    private fun budget(): Long {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return 0
        val power = context.getSystemService(PowerManager::class.java) ?: return 0
        if (activity.isLowRamDevice || power.isPowerSaveMode ||
            Build.VERSION.SDK_INT >= 29 && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return 0
        val memory = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        if (memory.lowMemory) return 0
        return (memory.availMem - maxOf(memory.threshold, memory.totalMem / 8)).coerceIn(0, MAX_BYTES)
    }

    // True means resource pressure: ask the owner to drop optional native pins.
    private fun warm(assets: Assets, jobContext: CoroutineContext): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false
        var remaining = budget()
        if (remaining <= 0) return true
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt()
        if (pageSize <= 0) return false
        val roots = listOf(assets.profile.canonicalFile, assets.shared.canonicalFile)
        val scanBytes = maxOf(1L, SCAN_BYTES / pageSize) * pageSize
        val buffer by lazy { ByteArray(CHUNK_BYTES) }
        var read = 0L
        val priority = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
        if (priority != null && priority < Process.THREAD_PRIORITY_BACKGROUND) {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        }
        try {
            for (path in assets.absolutePaths.distinct()) {
                jobContext.ensureActive()
                try {
                    val raw = File(path)
                    if (!raw.isAbsolute) continue
                    val file = raw.canonicalFile
                    if (roots.none { file.path.startsWith(it.path + File.separator) }) continue
                    val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                    try {
                        val stat = Os.fstat(fd)
                        val size = stat.st_size
                        if (!OsConstants.S_ISREG(stat.st_mode) || size <= 0) continue
                        // Scan small aligned windows, not the whole model's virtual address
                        // range. Even a file larger than the budget can be partly pre-read.
                        var base = 0L
                        var shortRead = false
                        while (base < size && remaining > 0 && !shortRead) {
                            jobContext.ensureActive()
                            val available = budget()
                            if (available < pageSize) return true
                            var quota = minOf(remaining, available)
                            val length = minOf(size - base, scanBytes)
                            val pages = ByteArray(((length + pageSize - 1) / pageSize).toInt())
                            val map = Os.mmap(0, length, OsConstants.PROT_NONE, OsConstants.MAP_SHARED, fd, base)
                            try { Os.mincore(map, length, pages) } finally { Os.munmap(map, length) }
                            var page = 0
                            while (page < pages.size && quota > 0) {
                                jobContext.ensureActive()
                                if (pages[page].toInt() and 1 != 0) { page++; continue }
                                val start = page++
                                while (page < pages.size && pages[page].toInt() and 1 == 0 &&
                                    (page - start) * pageSize < CHUNK_BYTES) page++
                                val offset = base + start.toLong() * pageSize
                                val count = minOf(size - offset, (page - start).toLong() * pageSize, quota).toInt()
                                val countRead = Os.pread(fd, buffer, 0, count, offset)
                                if (countRead > 0) {
                                    read += countRead
                                    remaining -= countRead
                                    quota -= countRead
                                }
                                if (countRead != count) { shortRead = true; break }
                                // Do not skip unread missing pages when the window hits its quota.
                                if (quota == 0L && remaining > 0) return true
                            }
                            base += length
                        }
                        if (remaining <= 0) return false
                    } finally { Os.close(fd) }
                } catch (_: ErrnoException) {
                    // A missing, changed, or unsupported file is left to the normal engine path.
                } catch (_: IOException) {
                    // An interrupted read must not affect the keyboard.
                }
            }
        } finally {
            if (priority != null && priority < Process.THREAD_PRIORITY_BACKGROUND) {
                runCatching { Process.setThreadPriority(priority) }
            }
            if (read > 0) Logx.i(LogTag.RimeAssetPrewarm, "bytes=$read cancelled=${!jobContext.isActive}")
        }
        return false
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
        const val CHUNK_BYTES = 1024 * 1024
        const val SCAN_BYTES = 32L * 1024 * 1024
    }
}
