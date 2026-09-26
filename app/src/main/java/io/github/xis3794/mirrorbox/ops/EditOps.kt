package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTool
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.ClusterStateMap
import io.github.xis3794.mirrorbox.qcow2.ImageStats
import io.github.xis3794.mirrorbox.qcow2.Qcow2HeaderInfo
import io.github.xis3794.mirrorbox.qcow2.Qcow2Image
import io.github.xis3794.mirrorbox.qcow2.Qcow2SnapshotInfo
import io.github.xis3794.mirrorbox.qcow2.disk.NewPartition
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionTableInfo
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionTables
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The "edit without a VM" workhorse.
 *
 * Two tracks (as designed):
 *  - direct: the pure Kotlin engine reads/writes the image in place (hex editing, structure repair)
 *  - safe:   extract a partition to a raw file → edit it with e2fsprogs / mtools / ntfsprogs →
 *            write back only the clusters that actually changed → verify with qemu-img check
 */
object EditOps {

    enum class FsKind(val label: String) {
        EXT4("ext4"),
        FAT32("FAT32"),
        NTFS("NTFS"),
    }

    data class StructureSnapshot(
        val header: Qcow2HeaderInfo,
        val stats: ImageStats,
        val snapshots: List<Qcow2SnapshotInfo>,
        val states: ClusterStateMap,
        val backingFileName: String?,
    )

    data class WriteBackResult(val changedClusters: Long, val scannedClusters: Long, val bytesWritten: Long)

    // ------------------------------------------------------------------ structure (engine only)

    fun structure(image: File): StructureSnapshot? = runCatching {
        Qcow2Image.open(image).use { img ->
            StructureSnapshot(
                header = img.header,
                stats = img.stats(),
                snapshots = img.snapshots(),
                states = img.clusterStates(200_000),
                backingFileName = img.backingFileName,
            )
        }
    }.getOrNull()

    fun detectPartitionTable(image: File): PartitionTableInfo? = runCatching {
        Qcow2Image.open(image).use { img ->
            PartitionTables.parse({ offset, length -> img.readBytes(offset, length) }, img.virtualSize)
        }
    }.getOrNull()

    // ------------------------------------------------------------------ safe mode pipeline

    fun extractRange(
        image: File,
        start: Long,
        length: Long,
        out: File,
        onProgress: ((Long) -> Unit)? = null,
    ): Boolean = runCatching {
        Qcow2Image.open(image).use { img ->
            FileOutputStream(out).use { sink ->
                img.copyTo(sink, start, length, onProgress = onProgress)
            }
        }
        true
    }.getOrDefault(false)

    /** Writes back only the clusters that differ between the raw edit copy and the image. */
    fun writeBackRange(
        image: File,
        start: Long,
        length: Long,
        modified: File,
        onProgress: ((Long) -> Unit)? = null,
    ): WriteBackResult {
        Qcow2Image.open(image, writable = true).use { img ->
            RandomAccessFile(modified, "r").use { raf ->
                val cluster = img.clusterSize.toLong()
                var pos = 0L
                var changed = 0L
                var scanned = 0L
                var bytes = 0L
                while (pos < length) {
                    val chunk = minOf(cluster, length - pos).toInt()
                    val buf = ByteArray(chunk)
                    raf.seek(pos)
                    raf.readFully(buf)
                    val original = img.readBytes(start + pos, chunk)
                    if (!original.contentEquals(buf)) {
                        img.write(start + pos, buf)
                        changed++
                        bytes += chunk
                    }
                    scanned++
                    pos += chunk
                    onProgress?.invoke(pos)
                }
                img.flush()
                return WriteBackResult(changed, scanned, bytes)
            }
        }
    }

    // ------------------------------------------------------------------ 稀疏（不完整展开）写入

    /**
     * 「重建文件系统」场景的轻量轨（格式化分区 / 释放 WIM 用这条）。
     *
     * 关键洞察：把文件系统**重建**一遍时，分区里的旧数据没有任何用，所以不需要先把整个分区
     * 提取成 raw。做法：
     *   1. 目标文件做成**稀疏文件**（`setLength` 只记录逻辑长度，不占实际空间）
     *   2. mkfs / mcopy 只会写它们真正写过的块 —— 其余位置读到的就是 0，对新建文件系统无影响
     *   3. 用 `SEEK_DATA` / `SEEK_HOLE` 找出真正有数据的区段，只把这些区段差分回写到 qcow2
     *
     * 效果：临时占用从「分区大小」降到「实际写入量」（ext4 通常几十 MB，FAT 约 FAT 表 + 文件数据）。
     */
    fun createSparseRange(length: Long, out: File): Boolean = runCatching {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        RandomAccessFile(out, "rw").use { it.setLength(length) }
        true
    }.getOrDefault(false)

    data class DataSegment(val offset: Long, val length: Long)

    /** bionic 没有公开 SEEK_DATA/SEEK_HOLE 常量，但 Linux ABI 固定为 3 / 4。 */
    private const val SEEK_DATA = 3
    private const val SEEK_HOLE = 4

    /**
     * 列出稀疏文件中真正有数据的区段。
     *
     * @return `null` 表示当前文件系统不支持 SEEK_DATA/SEEK_HOLE（调用方需回退到完整提取）
     */
    fun dataSegments(file: File): List<DataSegment>? {
        val size = file.length()
        if (size <= 0) return emptyList()
        val fd = try {
            android.system.Os.open(file.absolutePath, android.system.OsConstants.O_RDONLY, 0)
        } catch (t: Throwable) {
            return null
        }
        try {
            val out = ArrayList<DataSegment>()
            var pos = 0L
            while (pos < size) {
                val dataStart = try {
                    android.system.Os.lseek(fd, pos, SEEK_DATA)
                } catch (e: android.system.ErrnoException) {
                    when (e.errno) {
                        android.system.OsConstants.ENXIO -> break        // 后面全是空洞
                        android.system.OsConstants.EINVAL,
                        android.system.OsConstants.ENOTSUP,
                        -> return null                                   // 文件系统不支持，回退
                        else -> return null
                    }
                }
                if (dataStart >= size) break
                val hole = try {
                    android.system.Os.lseek(fd, dataStart, SEEK_HOLE)
                } catch (e: android.system.ErrnoException) {
                    size
                }
                val end = minOf(hole, size)
                if (end > dataStart) out.add(DataSegment(dataStart, end - dataStart))
                pos = maxOf(end, dataStart + 1)
            }
            return out
        } finally {
            runCatching { android.system.Os.close(fd) }
        }
    }

    /** 只回写 [segments] 覆盖的区域（逐簇与 qcow2 对比，仍然只写真正变化的簇）。 */
    fun writeBackSparse(
        image: File,
        start: Long,
        sparse: File,
        segments: List<DataSegment>,
        onProgress: ((Long) -> Unit)? = null,
    ): WriteBackResult {
        Qcow2Image.open(image, writable = true).use { img ->
            val cluster = img.clusterSize.toLong()
            var changed = 0L
            var scanned = 0L
            var bytes = 0L
            var processed = 0L
            RandomAccessFile(sparse, "r").use { raf ->
                for (segment in segments) {
                    var pos = segment.offset
                    val end = segment.offset + segment.length
                    while (pos < end) {
                        val chunk = minOf(cluster, end - pos).toInt()
                        val buf = ByteArray(chunk)
                        raf.seek(pos)
                        raf.readFully(buf)
                        val original = img.readBytes(start + pos, chunk)
                        if (!sameBytes(original, buf, chunk)) {
                            img.write(start + pos, buf)
                            changed++
                            bytes += chunk
                        }
                        scanned++
                        processed += chunk
                        pos += chunk
                        onProgress?.invoke(processed)
                    }
                }
            }
            img.flush()
            return WriteBackResult(changed, scanned, bytes)
        }
    }

    data class RebuildWriteBack(val result: WriteBackResult, val sparse: Boolean, val dataBytes: Long)

    /**
     * 「重建文件系统」场景的统一回写入口：优先只写稀疏数据段，不支持时回退到完整提取 + 差分回写。
     */
    fun writeBackRebuilt(
        image: File,
        start: Long,
        length: Long,
        target: File,
        onProgress: ((Long) -> Unit)? = null,
        onLog: (String) -> Unit = {},
    ): RebuildWriteBack {
        val segments = dataSegments(target)
        if (segments != null) {
            val dataBytes = segments.sumOf { it.length }
            onLog(
                "稀疏回写：只处理 ${Fmt.size(dataBytes)} 数据段" +
                    "（跳过 ${Fmt.size((length - dataBytes).coerceAtLeast(0))} 空洞，未复制分区旧数据）",
            )
            return RebuildWriteBack(writeBackSparse(image, start, target, segments, onProgress), true, dataBytes)
        }
        onLog("当前文件系统不支持 SEEK_DATA：回退到完整提取 + 差分回写（需要与分区等大的临时空间）")
        if (!extractRange(image, start, length, target)) {
            return RebuildWriteBack(WriteBackResult(0L, 0L, 0L), false, 0L)
        }
        return RebuildWriteBack(writeBackRange(image, start, length, target, onProgress), false, length)
    }

    private fun sameBytes(a: ByteArray, b: ByteArray, len: Int): Boolean {
        if (a.size < len || b.size < len) return false
        for (i in 0 until len) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    /** Rebuilds the partition table of a fresh image (MBR or GPT) using the pure Kotlin writer. */
    fun writePartitionTable(image: File, scheme: String, partitions: List<NewPartition>): Boolean = runCatching {
        Qcow2Image.open(image, writable = true).use { img ->
            val diskSectors = img.virtualSize / 512L
            if (scheme.equals("GPT", ignoreCase = true)) {
                val layout = PartitionTables.buildGpt(partitions, diskSectors)
                img.write(0L, layout.protectiveMbr)
                img.write(512L, layout.primaryHeader)
                img.write(layout.primaryEntriesLba * 512L, layout.entries)
                img.write(layout.backupEntriesLba * 512L, layout.backupEntries)
                img.write(layout.backupHeaderLba * 512L, layout.backupHeader)
            } else {
                img.write(0L, PartitionTables.buildMbr(partitions))
            }
            img.flush()
        }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ filesystems

    fun mkfsCommand(kind: FsKind, target: File, label: String): Pair<NativeTool, List<String>> = when (kind) {
        FsKind.EXT4 -> NativeTools.MKE2FS to listOf("-t", "ext4", "-F", "-L", label.take(16).ifBlank { "MIRRORBOX" }, target.absolutePath)
        FsKind.FAT32 -> NativeTools.MKFS_FAT to listOf("-F", "32", "-n", label.take(11).ifBlank { "MIRRORBOX" }, target.absolutePath)
        FsKind.NTFS -> NativeTools.MKNTFS to listOf("-f", "-L", label.take(32), target.absolutePath)
    }

    suspend fun format(context: Context, kind: FsKind, target: File, label: String): ImageOps.ToolResultSnapshot {
        val (tool, args) = mkfsCommand(kind, target, label)
        val result = ToolRunner.run(context, tool, args)
        return ImageOps.ToolResultSnapshot(result.exitCode, result.lines)
    }

    /** Fill an ext4 filesystem from a directory while creating it (`mke2fs -d`). */
    fun mkfsExt4FromDirectory(target: File, sourceDir: File, label: String): Pair<NativeTool, List<String>> =
        NativeTools.MKE2FS to listOf("-t", "ext4", "-F", "-d", sourceDir.absolutePath, "-L", label.take(16).ifBlank { "MIRRORBOX" }, target.absolutePath)

    suspend fun listFiles(context: Context, kind: FsKind, rawImage: File, offsetBytes: Long, path: String): List<String> {
        return when (kind) {
            FsKind.EXT4 -> ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-R", "ls -l $path", rawImage.absolutePath)).lines
            FsKind.FAT32 -> {
                val spec = if (offsetBytes > 0) "${rawImage.absolutePath}@@$offsetBytes" else rawImage.absolutePath
                ToolRunner.run(context, NativeTools.MDIR, listOf("-i", spec, "-/", "::" + path)).lines
            }
            FsKind.NTFS -> ToolRunner.run(context, NativeTools.NTFSLS, listOf("-p", path, rawImage.absolutePath)).lines
        }
    }

    // ------------------------------------------------------------------ hex editing

    fun readHex(image: File, offset: Long, length: Int): ByteArray? = runCatching {
        Qcow2Image.open(image).use { img -> img.readBytes(offset, length) }
    }.getOrNull()

    fun writeHex(image: File, offset: Long, bytes: ByteArray): Boolean = runCatching {
        Qcow2Image.open(image, writable = true).use { img ->
            img.write(offset, bytes)
            img.flush()
        }
        true
    }.getOrDefault(false)

    /** Byte exact copy of a whole image (engine based, works without qemu-img). */
    fun cloneImage(source: File, target: File, onProgress: ((Long) -> Unit)? = null): Boolean = runCatching {
        Qcow2Image.open(source, writable = false).use { img ->
            FileOutputStream(target).use { sink ->
                img.copyTo(sink, 0L, img.virtualSize, onProgress = onProgress)
            }
        }
        true
    }.getOrDefault(false)

    fun createImage(target: File, virtualSize: Long, clusterBits: Int = 16): Boolean = runCatching {
        Qcow2Image.create(target, virtualSize, clusterBits).use { }
        true
    }.getOrDefault(false)

    fun parseHexText(text: String): ByteArray? {
        val cleaned = text.replace(Regex("[^0-9a-fA-F]"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun toHexDump(bytes: ByteArray, baseOffset: Long, bytesPerRow: Int = 16): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            sb.append(String.format("%08X  ", baseOffset + i))
            val row = StringBuilder()
            for (j in 0 until bytesPerRow) {
                if (i + j < bytes.size) {
                    sb.append(String.format("%02X ", bytes[i + j]))
                    val c = bytes[i + j].toInt() and 0xff
                    row.append(if (c in 32..126) c.toChar() else '.')
                } else {
                    sb.append("   ")
                    row.append(' ')
                }
            }
            sb.append(" |").append(row).append("|\n")
            i += bytesPerRow
        }
        return sb.toString()
    }
}