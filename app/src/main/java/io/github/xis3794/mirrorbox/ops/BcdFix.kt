package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.Qcow2Image
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Windows 的 BCD（Boot Configuration Data）诊断与修复。
 *
 * 实测链路：GRUB → `ntldr /bootmgr` → bootmgr 都正常执行，最后 bootmgr 报
 *
 *     状态: 0xC000000E
 *     信息: 引导选择失败，因为需要的设备不可访问。
 *
 * 含义是 **BCD 里记录的系统设备在本磁盘上不存在**。Win7 的 BCD 一般是**二进制设备项**
 * （`原磁盘签名 + 分区偏移`）—— 把镜像照搬到另一块盘后签名不匹配，必然是这个错误。
 *
 * 两种修法：
 *  ① **签名对齐（首选，几乎零成本）**：从 BCD 读出它期望的磁盘签名，把我们的 MBR 磁盘签名
 *     （bytes 440..443）改成那个值；
 *  ② 文本形式（`\Device\HarddiskVolumeN`）时直接改卷号。
 *
 * 读取方式：**不提取整个分区** —— 只提取分区开头一小段窗口（64 MiB 起，按需放大），文件长度
 * 仍设成分区大小，然后用自带工具 `mirrorbox-ntfs --dump` 在进程内把文件读出来。只读不回写。
 */
object BcdFix {

    private const val PREFIX = "\\Device\\HarddiskVolume"
    private val UTF16 = Charset.forName("UTF-16LE")

    /** 读取窗口候选（MiB）：64 → 256 → 1024 */
    val WINDOWS = listOf(64L, 256L, 1024L)

    data class Found(val offset: Int, val text: String, val volume: Int)

    data class Signature(val foundAt: Int, val expected: Long, val offsetMatched: String)

    data class Report(
        val sizeBytes: Long,
        val volumeRefs: List<Found>,
        val signatures: List<Signature>,
        val currentDiskSignature: Long,
        val hexPreview: String,
        val summary: String,
    )

    fun utf16le(text: String): ByteArray = text.toByteArray(UTF16)

    // ------------------------------------------------------------------ 纯字节工具

    /** 找所有 `\Device\HarddiskVolumeN`（UTF-16LE）。 */
    fun scan(bytes: ByteArray): List<Found> {
        val needle = utf16le(PREFIX)
        val out = ArrayList<Found>()
        var i = 0
        while (i + needle.size + 2 <= bytes.size) {
            var match = true
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                var digits = 0
                var volume = 0
                var k = i + needle.size
                while (k + 1 < bytes.size && bytes[k + 1].toInt() == 0 &&
                    bytes[k] in '0'.code.toByte()..'9'.code.toByte()
                ) {
                    volume = volume * 10 + (bytes[k] - '0'.code.toByte())
                    digits++
                    k += 2
                }
                if (digits > 0) out.add(Found(i, "$PREFIX$volume", volume))
                i = k
            } else {
                i += 2
            }
        }
        return out
    }

    /** 把卷号改成 [targetVolume]（长度不变，变短时 NUL 补齐）。 */
    fun rewrite(bytes: ByteArray, targetVolume: Int): Pair<ByteArray, List<String>> {
        val found = scan(bytes)
        if (found.isEmpty()) return bytes to emptyList()
        val out = bytes.copyOf()
        val notes = ArrayList<String>()
        val replacement = targetVolume.toString()
        for (item in found) {
            val start = item.offset + utf16le(PREFIX).size
            val oldDigits = item.volume.toString().length
            var pos = start
            for (ch in replacement) {
                if (pos + 1 >= out.size) break
                out[pos] = ch.code.toByte()
                out[pos + 1] = 0
                pos += 2
            }
            while (pos < start + oldDigits * 2) {
                out[pos] = 0
                out[pos + 1] = 0
                pos += 2
            }
            notes.add("${item.text} → $PREFIX$targetVolume")
        }
        return out to notes
    }

    /**
     * 找"二进制分区设备项"候选：u64 等于目标分区的**字节偏移**或**扇区号**，紧随其后的 4 字节
     * 就是 BCD 期望的磁盘签名（Win7 设备项的经典布局）。
     */
    fun candidateSignatures(
        bytes: ByteArray,
        partitionStartBytes: Long,
        partitionStartSectors: Long,
    ): List<Signature> {
        val out = ArrayList<Signature>()
        fun u64(at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xff)
            return v
        }
        fun u32(at: Int): Long {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xff)
            return v
        }
        var i = 0
        while (i + 12 <= bytes.size) {
            val v = u64(i)
            val which = when (v) {
                partitionStartBytes -> "分区字节偏移 $v"
                partitionStartSectors -> "分区扇区号 $v"
                else -> null
            }
            if (which != null) {
                val sig = u32(i + 8)
                if (sig != 0L && sig != 0xffffffffL) out.add(Signature(i, sig, which))
            }
            i++
        }
        return out
    }

    fun hexPreview(bytes: ByteArray, count: Int = 128): String {
        val sb = StringBuilder()
        var i = 0
        while (i < minOf(count, bytes.size)) {
            if (i % 16 == 0) sb.append(String.format("%04X  ", i))
            sb.append(String.format("%02X ", bytes[i]))
            if (i % 16 == 15) sb.append('\n')
            i++
        }
        return sb.toString()
    }

    private fun describe(refs: List<Found>, sigs: List<Signature>): String = buildString {
        if (refs.isNotEmpty()) append("文本设备项：").append(refs.map { it.text }.distinct().joinToString("、"))
        if (sigs.isNotEmpty()) {
            if (isNotEmpty()) append("；")
            append("二进制设备项期望签名：")
            append(sigs.map { String.format("0x%08X", it.expected) }.distinct().joinToString("、"))
        }
        if (isEmpty()) append("未识别到设备项（可能格式特殊，请把 BCD 导出给我）")
    }

    // ------------------------------------------------------------------ staging（释放流程）

    fun bcdFile(root: File): File? {
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            if (depth > 3) continue
            val kids = dir.listFiles() ?: continue
            for (child in kids) {
                if (child.isDirectory) {
                    stack.addLast(child to depth + 1)
                } else if (child.name.equals("bcd", ignoreCase = true) &&
                    dir.name.equals("Boot", ignoreCase = true)
                ) {
                    return child
                }
            }
        }
        return null
    }

    /** 释放前分析 staging 里的 BCD（纯文件操作）。 */
    fun analyzeStaging(staging: File, partitionStartBytes: Long, partitionStartSectors: Long): Report? {
        val bcd = bcdFile(staging) ?: return null
        val bytes = runCatching { bcd.readBytes() }.getOrNull() ?: return null
        val refs = scan(bytes)
        val sigs = candidateSignatures(bytes, partitionStartBytes, partitionStartSectors)
        return Report(
            sizeBytes = bytes.size.toLong(),
            volumeRefs = refs,
            signatures = sigs,
            currentDiskSignature = -1,
            hexPreview = hexPreview(bytes),
            summary = describe(refs, sigs),
        )
    }

    /** 文本卷号形式的自动修复（释放流程）。 */
    fun fixInStaging(staging: File, targetPartition: Int): String {
        val bcd = bcdFile(staging) ?: return "镜像里没有 Boot\\BCD"
        val bytes = runCatching { bcd.readBytes() }.getOrNull() ?: return "读不到 ${bcd.absolutePath}"
        val found = scan(bytes)
        if (found.isEmpty()) return "BCD 用的是二进制设备项（见「检查 BCD」的签名对齐）"
        val (patched, notes) = rewrite(bytes, targetPartition)
        val ok = runCatching { bcd.writeBytes(patched); true }.getOrDefault(false)
        return if (ok) "BCD 卷号已改为分区 $targetPartition：${notes.joinToString("；")}" else "写入 BCD 失败"
    }

    // ------------------------------------------------------------------ 已有分区：窗口读取

    private suspend fun readBcdViaWindow(
        context: Context,
        image: File,
        entry: PartitionEntry,
        windowMiB: Long,
        onLog: (String) -> Unit,
    ): ByteArray? {
        val windowBytes = minOf(entry.sizeBytes, windowMiB * 1024 * 1024)
        val raw = File(AppPaths.tmp, "bcdw-${entry.index}-${System.currentTimeMillis()}.raw")
        try {
            onLog("读取分区 ${entry.index} 头部 ${Fmt.size(windowBytes)}（分区共 ${Fmt.size(entry.sizeBytes)}）")
            if (!EditOps.extractRange(image, entry.startByte, windowBytes, raw, null)) {
                onLog("提取窗口失败（临时空间不足？）")
                return null
            }
            // 文件长度必须是整个分区大小，NTFS 才能挂载；窗口之外保持稀疏（读为 0 不影响读 BCD）
            RandomAccessFile(raw, "rw").use { it.setLength(entry.sizeBytes) }
            return readViaTool(context, raw, onLog)
        } finally {
            raw.delete()
        }
    }

    /** 用自带的 mirrorbox-ntfs（libntfs-3g，进程内）导出 `Boot\BCD`。 */
    private suspend fun readViaTool(context: Context, raw: File, onLog: (String) -> Unit): ByteArray? {
        val out = File(AppPaths.tmp, "bcd-dump-${System.currentTimeMillis()}.bin")
        try {
            val result = ToolRunner.run(
                context,
                NativeTools.NTFS_APPLY,
                listOf(raw.absolutePath, "--dump", "Boot/BCD", out.absolutePath),
                onLine = onLog,
            )
            if (result.success && out.length() > 0) {
                onLog("已读出 Boot/BCD（${Fmt.size(out.length())}）")
                return out.readBytes()
            }
            onLog("--dump 失败：${result.lines.lastOrNull().orEmpty()}")
        } finally {
            out.delete()
        }
        return null
    }

    suspend fun analyzeInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): Report {
        var bytes: ByteArray? = null
        for (window in WINDOWS) {
            bytes = readBcdViaWindow(context, image, entry, window, onLog)
            if (bytes != null) break
            onLog("窗口 ${window} MiB 内没读到，扩大窗口重试…")
        }
        if (bytes == null) {
            return Report(
                0, emptyList(), emptyList(), currentSignature(image), "",
                "没读到 \\Boot\\BCD：分区里可能没有它（那就得用 Win7 安装盘的「启动修复」/ bcdboot）",
            )
        }
        val refs = scan(bytes)
        val sigs = candidateSignatures(bytes, entry.startByte, entry.startLba)
        val current = currentSignature(image)
        return Report(
            sizeBytes = bytes.size.toLong(),
            volumeRefs = refs,
            signatures = sigs,
            currentDiskSignature = current,
            hexPreview = hexPreview(bytes),
            summary = describe(refs, sigs) + "；本磁盘签名 " + String.format("0x%08X", current),
        )
    }

    /** 导出 BCD 到可访问目录（便于发给我分析）。 */
    suspend fun exportFromPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): String {
        var bytes: ByteArray? = null
        for (window in WINDOWS) {
            bytes = readBcdViaWindow(context, image, entry, window, onLog)
            if (bytes != null) break
        }
        val data = bytes ?: return "没读到 BCD，无法导出"
        val dir = GuestFsOps.publicExportDir() ?: File(AppPaths.externalRoot(), "bcd").apply { mkdirs() }
        val out = File(dir, "BCD-p${entry.index}.bin")
        return runCatching {
            out.writeBytes(data)
            "已导出 ${data.size} 字节 → ${out.absolutePath}"
        }.getOrElse { "导出失败：${it.message}" }
    }

    /** **签名对齐**：读出 BCD 期望的磁盘签名，写进 MBR（只改 4 字节）。 */
    suspend fun alignDiskSignature(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): String {
        val report = analyzeInPartition(context, image, entry, onLog)
        val candidates = report.signatures.map { it.expected }.distinct()
        if (candidates.isEmpty()) {
            return "BCD 里没找到与本分区偏移匹配的二进制设备项 → 请点「导出 BCD」，把文件发我做精确修复"
        }
        val target = candidates.first()
        val old = String.format("0x%08X", report.currentDiskSignature)
        val ok = PartitionOps.setDiskSignature(image, target)
        return if (ok) {
            "已把磁盘签名对齐到 BCD 期望值：$old → " + String.format("0x%08X", target) +
                "（只改了 MBR 的 4 字节）。重启试引导。"
        } else {
            "写入磁盘签名失败"
        }
    }

    /** 读取当前 MBR 磁盘签名。 */
    fun currentSignature(image: File): Long = runCatching {
        Qcow2Image.open(image).use { img ->
            val mbr = img.readBytes(0L, 512)
            (mbr[440].toLong() and 0xff) or
                ((mbr[441].toLong() and 0xff) shl 8) or
                ((mbr[442].toLong() and 0xff) shl 16) or
                ((mbr[443].toLong() and 0xff) shl 24)
        }
    }.getOrDefault(0L)

    /** 文本卷号形式的就地修复（窗口读写，只动窗口内的簇）。 */
    suspend fun fixVolumeInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        targetVolume: Int,
        onLog: (String) -> Unit = {},
    ): String {
        val windowBytes = minOf(entry.sizeBytes, WINDOWS.first() * 1024 * 1024)
        val raw = File(AppPaths.tmp, "bcdw-${entry.index}-${System.currentTimeMillis()}.raw")
        try {
            onLog("提取分区 ${entry.index} 头部 ${Fmt.size(windowBytes)}")
            if (!EditOps.extractRange(image, entry.startByte, windowBytes, raw, null)) {
                return "提取窗口失败（本机临时空间不足）"
            }
            RandomAccessFile(raw, "rw").use { it.setLength(entry.sizeBytes) }
            val bytes = readViaTool(context, raw, onLog) ?: return "没读到 Boot\\BCD"
            if (scan(bytes).isEmpty()) return "BCD 是二进制设备项，请改用「对齐磁盘签名」"
            val (patched, notes) = rewrite(bytes, targetVolume)
            val tmp = File(AppPaths.tmp, "bcd-patched-${System.currentTimeMillis()}.bin")
            tmp.writeBytes(patched)
            try {
                val put = ToolRunner.run(
                    context, NativeTools.NTFS_APPLY,
                    listOf(raw.absolutePath, "--put", tmp.absolutePath, "Boot/BCD"),
                    onLine = onLog,
                )
                if (!put.success) return "写回失败：${put.lines.lastOrNull().orEmpty()}"
            } finally {
                tmp.delete()
            }
            onLog("回写窗口内的变化簇")
            val written = EditOps.writeBackRange(image, entry.startByte, windowBytes, raw, null)
            return "BCD 卷号已改为 Volume$targetVolume（${notes.joinToString("；")}），变化 ${written.changedClusters} 个簇"
        } catch (t: Throwable) {
            return "修复失败：${t.message}"
        } finally {
            raw.delete()
        }
    }
}