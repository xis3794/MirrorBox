package io.github.xis3794.mirrorbox.core

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Central place for every directory MirrorBox uses.
 *
 * Layout:
 *  - files/mirrorbox/home       HOME for bundled native tools (config files, temporary state)
 *  - files/mirrorbox/images     the image library (qcow2 / vmdk / raw ...)
 *  - files/mirrorbox/iso        ISO projects and results
 *  - files/mirrorbox/work       scratch space for extract → edit → write back pipelines
 *  - files/mirrorbox/logs       per task logs
 *  - cache/tmp                  TMPDIR for the tools
 *  - external ../Android/data/<pkg>/files/MirrorBox   user visible working area (no permission needed)
 */
object AppPaths {

    private lateinit var ctx: Context

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    val root: File get() = File(ctx.filesDir, "mirrorbox").apply { mkdirs() }
    val home: File get() = File(root, "home").apply { mkdirs() }
    val images: File get() = File(root, "images").apply { mkdirs() }
    val iso: File get() = File(root, "iso").apply { mkdirs() }
    val work: File get() = File(root, "work").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }
    val tmp: File get() = File(ctx.cacheDir, "tmp").apply { mkdirs() }

    /** External, user visible area — a real filesystem path, so bundled tools can work in place. */
    fun externalRoot(): File {
        val ext = ctx.getExternalFilesDir(null)
        return if (ext != null) File(ext, "MirrorBox").apply { mkdirs() } else root
    }

    fun imports(): File = File(externalRoot(), "imports").apply { mkdirs() }

    /** /sdcard style access is only possible with MANAGE_EXTERNAL_STORAGE (optional user choice). */
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun uniqueFile(dir: File, baseName: String, extension: String): File {
        dir.mkdirs()
        var candidate = File(dir, "$baseName$extension")
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName-$i$extension")
            i++
        }
        return candidate
    }

    /**
     * 目录体积统计，**带硬上限**，可以安全地在 UI 线程附近调用。
     *
     * 之前这里是无上限递归：释放 WIM 之后 `work/releases/<镜像>` 里有几十万个文件，
     * 一次统计要跑几十秒 —— 设置页因为在组合期调用它而直接卡死。现在超过
     * [maxEntries] 个文件或 [budgetMs] 毫秒就停下，返回 (字节数, 是否被截断)。
     */
    fun sizeOfTreeBounded(
        dir: File,
        maxEntries: Int = 20_000,
        budgetMs: Long = 400L,
    ): Pair<Long, Boolean> {
        if (!dir.exists()) return 0L to false
        val deadline = System.currentTimeMillis() + budgetMs
        var entries = 0
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && entries < maxEntries && System.currentTimeMillis() < deadline) {
            val kids = stack.removeLast().listFiles() ?: continue
            for (f in kids) {
                if (f.isDirectory) {
                    stack.addLast(f)
                } else {
                    total += f.length()
                    entries++
                }
            }
        }
        val truncated = entries >= maxEntries || System.currentTimeMillis() >= deadline
        return total to truncated
    }

    /** 释放 WIM 的暂存目录（可能极大，单独统计）。 */
    fun stagingDir(): File = File(work, "releases")

    /** 工作区占用：排除 `work/releases`，避免被暂存镜像的几十万文件拖死。 */
    fun workspaceUsage(): Triple<Long, Long, Boolean> {
        val staging = sizeOfTreeBounded(stagingDir(), maxEntries = 4_000, budgetMs = 300L)
        val roots = listOf(home, images, iso, logs, File(work, "tmp"))
        var main = 0L
        var truncated = staging.second
        for (root in roots) {
            val (bytes, cut) = sizeOfTreeBounded(root, maxEntries = 6_000, budgetMs = 200L)
            main += bytes
            truncated = truncated || cut
        }
        return Triple(main, staging.first, truncated)
    }

    /** 兼容旧调用：小目录够用，但同样带上限。 */
    fun sizeOfTree(dir: File): Long = sizeOfTreeBounded(dir).first
}