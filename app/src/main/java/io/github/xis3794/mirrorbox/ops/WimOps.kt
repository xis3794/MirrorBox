package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolResult
import io.github.xis3794.mirrorbox.core.ToolRunner
import java.io.File

/**
 * wimlib-imagex 封装：DISIM++ 风格的「释放 WIM」。
 *
 *  - `info`  列出镜像索引（名称 / 描述 / 大小 / 文件数）
 *  - `dir`   预览镜像里的文件树（不落盘）
 *  - `apply` 把某个索引释放到一个目录（不挂载、不需要 root）
 *  - `capture` 把目录重新打成 wim（用于「备份/打包」，二期 UI）
 *
 * 释放出来的目录随后交给 [ReleaseOps] 写入分区：ext4 用 `mke2fs -d` 一次成型，FAT 用 `mcopy -s`。
 */
object WimOps {

    data class WimImage(
        val index: Int,
        val name: String,
        val description: String,
        val displayName: String,
        val totalBytes: Long,
        val fileCount: Long,
    ) {
        val title: String get() = name.ifBlank { "镜像 $index" }
        val ident: String get() = if (name.isNotBlank()) name else index.toString()
        val subtitle: String
            get() = buildString {
                append("索引 $index")
                if (displayName.isNotBlank() && displayName != name) append(" · ").append(displayName)
                if (totalBytes > 0) append(" · ").append(io.github.xis3794.mirrorbox.core.Fmt.size(totalBytes))
                if (fileCount > 0) append(" · ").append(fileCount).append(" 个文件")
            }
    }

    data class WimInfo(
        val images: List<WimImage>,
        val compression: String,
        val chunkSize: String,
        val bootIndex: Int,
        val partNumber: String,
        val raw: List<String>,
    ) {
        val bootImage: WimImage? get() = images.firstOrNull { it.index == bootIndex }
    }

    private val KEY = Regex("^([A-Za-z][A-Za-z0-9 _]*?)\\s*:\\s*(.*)$")

    /** 解析 `wimlib-imagex info` 的文本输出（头部信息 + 每个索引一块）。 */
    fun parseInfo(lines: List<String>): WimInfo {
        val images = ArrayList<WimImage>()
        var index = 0
        var name = ""
        var desc = ""
        var display = ""
        var total = 0L
        var files = 0L
        var compression = ""
        var chunk = ""
        var bootIndex = 0
        var part = ""

        fun flush() {
            if (index > 0) images.add(WimImage(index, name, desc, display, total, files))
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("Available Images")) continue
            if (line.startsWith("WIM Information")) continue
            if (line.all { it == '-' || it == '=' }) continue
            val match = KEY.matchEntire(line) ?: continue
            val key = match.groupValues[1].trim().lowercase()
            val value = match.groupValues[2].trim()
            when (key) {
                "index" -> {
                    flush()
                    index = value.toIntOrNull() ?: 0
                    name = ""
                    desc = ""
                    display = ""
                    total = 0L
                    files = 0L
                }
                "name" -> name = value
                "description" -> desc = value
                "display name" -> display = value
                "file count" -> files = value.replace(",", "").toLongOrNull() ?: 0L
                "total bytes" -> total = value.replace(",", "").toLongOrNull() ?: 0L
                "compression" -> compression = value
                "chunk size" -> chunk = value
                "boot index" -> bootIndex = value.toIntOrNull() ?: 0
                "part number" -> part = value
            }
        }
        flush()
        return WimInfo(images, compression, chunk, bootIndex, part, lines)
    }

    suspend fun info(context: Context, wim: File, onLine: (String) -> Unit = {}): WimInfo {
        val result = ToolRunner.run(context, NativeTools.WIMLIB, listOf("info", wim.absolutePath), onLine = onLine)
        return parseInfo(result.lines)
    }

    /** 列出某个索引下的文件（`--path=` 指定子目录）。 */
    suspend fun dir(
        context: Context,
        wim: File,
        index: Int,
        path: String = "/",
        limit: Int = 400,
        onLine: (String) -> Unit = {},
    ): Pair<List<String>, Boolean> {
        val args = mutableListOf("dir", wim.absolutePath, index.toString())
        if (path.isNotBlank() && path != "/") args.add("--path=$path")
        val result = ToolRunner.run(context, NativeTools.WIMLIB, args, onLine = onLine)
        val all = result.lines.map { it.trimEnd() }.filter { it.isNotBlank() }
        return if (all.size > limit) all.take(limit) to true else all to false
    }

    /**
     * 把 [index] 释放到 [dest] 目录（不挂载）。
     *
     * `--no-acls`：Android 的文件系统没有 Windows 安全描述符的概念，跳过它既省时间又避免报错。
     */
    suspend fun apply(
        context: Context,
        wim: File,
        index: Int,
        dest: File,
        onLine: (String) -> Unit = {},
    ): ToolResult {
        dest.mkdirs()
        return ToolRunner.run(
            context,
            NativeTools.WIMLIB,
            listOf("apply", wim.absolutePath, index.toString(), dest.absolutePath, "--no-acls"),
            onLine = onLine,
        )
    }

    /** 校验 WIM 是否完整（较慢，可选）。 */
    suspend fun verify(context: Context, wim: File, onLine: (String) -> Unit = {}): ToolResult =
        ToolRunner.run(context, NativeTools.WIMLIB, listOf("verify", wim.absolutePath), onLine = onLine)
    /**
     * 释放 WIM 的暂存目录必须是**支持符号链接/硬链接**的文件系统。
     *
     * Windows 镜像里存在符号链接（典型的是 `Documents and Settings` → `Users`）和大量硬链接，
     * 而共享存储（`/sdcard`、`Android/data/...`）不允许普通应用创建链接 —— 之前正是在那里报
     * `Can't create symbolic link ... Permission denied`（wimlib 退出码 35），而且 wimlib 没有
     * 「跳过链接」的开关。所以这里强制把暂存目录放到应用内部存储。
     *
     * @return 实际使用的目录 to 需要提示用户的说明（无需替换时为 null）
     */
    fun safeStagingDir(requested: File?, wimName: String): Pair<File, String?> {
        val base = File(File(io.github.xis3794.mirrorbox.core.AppPaths.work, "releases"), "$wimName-img")
        if (requested == null) return base to null
        if (supportsLinks(requested)) return requested to null
        return base to "该路径在共享存储上（不支持符号链接/硬链接），已改用应用内部目录：${base.absolutePath}"
    }

    /** 内部存储（/data/...）才能创建链接；共享存储不行。 */
    fun supportsLinks(dir: File): Boolean {
        val path = dir.absolutePath
        return path.startsWith("/data/") || path.startsWith("/data/user/") || path.startsWith("/data/local/")
    }

    /** 释放结果的体积统计。 */
    fun treeStats(dir: File): Pair<Long, Long> {
        var bytes = 0L
        var count = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    bytes += child.length()
                    count++
                }
            }
        }
        return bytes to count
    }
}