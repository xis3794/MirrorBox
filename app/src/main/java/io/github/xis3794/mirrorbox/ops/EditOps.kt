package io.github.xis3794.mirrorbox.ops

import android.content.Context
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