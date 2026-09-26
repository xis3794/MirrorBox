package io.github.xis3794.mirrorbox.ops

import android.content.Context
import android.os.StatFs
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry
import java.io.File

/**
 * 把「一个目录」写进镜像里的分区（不挂载、不需要 root、不需要循环设备）。
 *
 * 安全轨，与格式化分区完全一致：
 *  1. 把分区提取成临时 raw 文件（[EditOps.extractRange]）
 *  2. 在 raw 上建文件系统并灌数据：
 *     - ext4：`mke2fs -d <staging>`（建 fs 的同时填充，一次成型）
 *     - FAT32：`mkfs.fat` + `mcopy -s`（每个顶层条目调用一次，mtools 自己递归子目录）
 *  3. 只把变化的簇写回 qcow2（[EditOps.writeBackRange]）
 *
 * 因为要一个「和分区一样大」的临时文件，调用前必须做空间预检（[checkSpace]）。
 */
object ReleaseOps {

    data class Result(
        val ok: Boolean,
        val message: String,
        val changedClusters: Long = 0L,
        val bytesWritten: Long = 0L,
    )

    data class SpaceCheck(val requiredBytes: Long, val freeBytes: Long) {
        val enough: Boolean get() = freeBytes > requiredBytes
        val text: String
            get() = "需要 ${Fmt.size(requiredBytes)}（分区临时副本），可用 ${Fmt.size(freeBytes)}"
    }

    /** FAT 释放通过 mtools 完成；NTFS 逐文件写入太慢，暂不支持。 */
    fun supported(kind: EditOps.FsKind): Boolean = kind != EditOps.FsKind.NTFS

    fun unsupportedReason(kind: EditOps.FsKind): String = when (kind) {
        EditOps.FsKind.NTFS -> "NTFS 释放暂不支持：ntfscp 逐文件写入一个 Windows 镜像会非常慢；请改用 ext4 或 FAT32"
        else -> ""
    }

    /**
     * @param partitionBytes 目标分区大小（临时 raw 副本要这么大）
     * @param stagingBytes 待写入目录的大小
     */
    fun checkSpace(partitionBytes: Long, stagingBytes: Long, dir: File = AppPaths.tmp): SpaceCheck {
        val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
        val free = stat?.let { it.availableBlocksLong.toDouble() * it.blockSizeLong }?.toLong() ?: 0L
        return SpaceCheck(partitionBytes + stagingBytes, free)
    }

    /**
     * 把 [staging] 的内容写进 [entry] 所代表的分区（分区内原有文件系统会被重建）。
     */
    suspend fun releaseDirectory(
        context: Context,
        image: File,
        entry: PartitionEntry,
        staging: File,
        kind: EditOps.FsKind,
        label: String,
        onLog: (String) -> Unit = {},
        onProgress: ((Long) -> Unit)? = null,
    ): Result {
        val length = entry.sizeBytes
        if (length <= 0) return Result(false, "分区大小无效")
        if (!staging.isDirectory) return Result(false, "待写入目录不存在：${staging.absolutePath}")
        if (!supported(kind)) return Result(false, unsupportedReason(kind))

        val tmpRaw = File(AppPaths.tmp, "release-p${entry.index}-${System.currentTimeMillis()}.raw")
        try {
            onLog("① 提取分区 ${entry.index}（${Fmt.size(length)}）→ 临时 raw")
            if (!EditOps.extractRange(image, entry.startByte, length, tmpRaw, onProgress)) {
                return Result(false, "无法提取分区 ${entry.index}")
            }

            onLog("② 写入 ${kind.label} 文件系统（源：${staging.name}）")
            when (kind) {
                EditOps.FsKind.EXT4 -> {
                    val (tool, args) = EditOps.mkfsExt4FromDirectory(tmpRaw, staging, label)
                    val result = ToolRunner.run(context, tool, args, onLine = onLog)
                    if (!result.success) {
                        return Result(false, "mke2fs -d 失败：${result.lines.lastOrNull().orEmpty()}")
                    }
                }
                EditOps.FsKind.FAT32 -> {
                    val fatBits = if (length >= 33L * 1024 * 1024) "32" else "16"
                    val mkfs = ToolRunner.run(
                        context,
                        NativeTools.MKFS_FAT,
                        listOf("-F", fatBits, "-n", label.take(11).ifBlank { "MIRRORBOX" }, tmpRaw.absolutePath),
                        onLine = onLog,
                    )
                    if (!mkfs.success) {
                        return Result(false, "mkfs.fat 失败：${mkfs.lines.lastOrNull().orEmpty()}")
                    }
                    val entries = staging.listFiles().orEmpty()
                    if (entries.isEmpty()) return Result(false, "待写入目录是空的：${staging.absolutePath}")
                    var done = 0
                    for (child in entries) {
                        // 目录用 `mcopy -s <dir> ::`（mtools 会把该目录连同子树复制到根）；
                        // 单个文件用 `mcopy <file> ::`。每个顶层条目一次调用，避免逐文件开销。
                        val args = if (child.isDirectory) {
                            listOf("-s", "-i", tmpRaw.absolutePath, child.absolutePath, "::")
                        } else {
                            listOf("-i", tmpRaw.absolutePath, child.absolutePath, "::")
                        }
                        val copy = ToolRunner.run(context, NativeTools.MCOPY, args, cwd = staging, onLine = onLog)
                        if (!copy.success) {
                            return Result(false, "mcopy 写入 ${child.name} 失败：${copy.lines.lastOrNull().orEmpty()}")
                        }
                        done++
                        onLog("   已写入 ${child.name}（$done/${entries.size}）")
                    }
                }
                EditOps.FsKind.NTFS -> return Result(false, unsupportedReason(kind))
            }

            onLog("③ 差分回写变化的簇到镜像 …")
            val written = EditOps.writeBackRange(image, entry.startByte, length, tmpRaw, onProgress)
            return Result(
                ok = true,
                message = "已写入分区 ${entry.index}：${kind.label}，变化 ${written.changedClusters} 个簇" +
                    "（${Fmt.size(written.bytesWritten)}，共扫描 ${written.scannedClusters} 簇）",
                changedClusters = written.changedClusters,
                bytesWritten = written.bytesWritten,
            )
        } catch (t: Throwable) {
            return Result(false, "写入分区失败：${t.message}")
        } finally {
            tmpRaw.delete()
        }
    }
}