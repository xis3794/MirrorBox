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
 *  ② 文本形式（`\Device\HarddiskVolumeN`）时直接改卷号 —— 因为 BCD 里的数据长度不变，
 *     可以**就地改写**（按工具给出的物理位置写回那几个字节，不需要重建文件系统元数据）。
 *
 * ## 怎么读到 BCD（关键）
 *
 * NTFS 的元数据是散在整个卷里的 —— mkntfs 把 $MFTMirr/$LogFile 放在卷中部（10 GB 卷 ≈ 5 GB 处），
 * $AttrDef/$Bitmap/$Secure/$UpCase 放在 1/8 处。所以：
 *  - 提取整个分区不行（Win7 装完好几 GB，手机没那么多临时空间）；
 *  - 只提取"开头 64 MiB"也不行（libntfs-3g 挂载时一定要读 $MFTMirr 和 $UpCase）。
 *
 * 做法改成**按需索取**：`mirrorbox-ntfs` 用自定义 device 包住这个稀疏窗口文件，只信任
 * `--regions` 列出的范围；越界读取会被记为 `NEED off len` 并以退出码 10 结束。我们把这些
 * 区域从 qcow2 里补进窗口再重跑，通常 2~5 轮、几百 KB 就能读出 BCD。读取是只读的，
 * 全程不会碰用户镜像里的其它数据。
 */
object BcdFix {

    private const val PREFIX = "\\Device\\HarddiskVolume"
    private val UTF16 = Charset.forName("UTF-16LE")

    /** 按需区域的硬上限：正常只要几百 KB，超过说明出问题了，及时放弃而不是把存储塞满。 */
    private const val MAX_WINDOW_BYTES = 32L * 1024 * 1024

    /** 补齐-重跑的最大轮数。 */
    private const val MAX_ROUNDS = 16

    /** 单次工具调用的超时（镜像损坏时工具可能陷在死循环里）。 */
    private const val TOOL_TIMEOUT_MS = 90_000L

    data class Found(val offset: Int, val text: String, val volume: Int)

    /** BCD 里的「分区设备」结构：bootmgr 用它找系统分区。 */
    data class DeviceStruct(
        val fileOffset: Int,
        val expectedOffset: Long,
        val expectedSignature: Long,
    )

    /** Windows 分区设备结构（88 字节）里的字段偏移。 */
    private const val DEV_KIND = 0x10          // u32 = 6（分区）
    private const val DEV_SIZE = 0x18          // u32 = 0x48（结构长度 72）
    private const val DEV_OFFSET = 0x20        // u64 = 分区字节偏移
    private const val DEV_FLAG = 0x34          // u32 ≤ 4
    private const val DEV_SIGNATURE = 0x38     // u32 = 磁盘签名

    /**
     * 扫描 BCD 里所有的分区设备结构。
     *
     * 实测（Win7 从 install.wim 释放的镜像）：7 处结构，全部是
     * `分区偏移=32256（LBA 63）`，签名则是原机器的 `0xE488E488`（个别对象是 `0xA05EA05E`）。
     * 我们的盘如果分区不在 LBA 63、MBR 签名也不是它，bootmgr 就会 0xC000000E。
     */
    fun findDeviceStructs(bytes: ByteArray): List<DeviceStruct> {
        fun u32(at: Int): Int {
            var v = 0
            for (i in 3 downTo 0) v = (v shl 8) or (bytes[at + i].toInt() and 0xff)
            return v
        }
        fun u64(at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (bytes[at + i].toLong() and 0xff)
            return v
        }
        val out = ArrayList<DeviceStruct>()
        var p = 0
        while (p + 0x40 <= bytes.size) {
            // 结构前面 16 字节是 0；紧跟着 kind=6、size=0x48，尾部是 0
            var zeros = true
            for (i in 0 until 16) {
                if (bytes[p + i].toInt() != 0) {
                    zeros = false
                    break
                }
            }
            if (zeros && u32(p + DEV_KIND) == 6 && u32(p + DEV_SIZE) == 0x48 &&
                u32(p + 0x3C) == 0 && u32(p + DEV_FLAG) <= 4
            ) {
                val off = u64(p + DEV_OFFSET)
                val sig = u32(p + DEV_SIGNATURE).toLong() and 0xffffffffL
                // 签名允许为 0：Windows 首次开机可能把它写成 0（MBR 签名为 0 时），
                // 我们仍要认出来并改成真实签名，否则会一直 0xC000000E。
                if (off > 0 && off % 512L == 0L && off < (1L shl 42) && sig != 0xffffffffL) {
                    out.add(DeviceStruct(p, off, sig))
                }
            }
            p++
        }
        return out
    }

    /** 把所有分区设备结构改成指向 (offset, signature)；返回新字节与原结构列表。 */
    fun patchDeviceStructs(
        bytes: ByteArray,
        offset: Long,
        signature: Long,
    ): Pair<ByteArray, List<DeviceStruct>> {
        val structs = findDeviceStructs(bytes)
        if (structs.isEmpty()) return bytes to emptyList()
        val out = bytes.copyOf()
        for (s in structs) {
            for (i in 0 until 8) out[s.fileOffset + DEV_OFFSET + i] = ((offset shr (8 * i)) and 0xff).toByte()
            for (i in 0 until 4) out[s.fileOffset + DEV_SIGNATURE + i] = ((signature shr (8 * i)) and 0xff).toByte()
        }
        return out to structs
    }

    data class Signature(val foundAt: Int, val expected: Long, val offsetMatched: String)

    /** 文件内偏移 → 卷内物理偏移（就地改写用）。 */
    data class MapSegment(val fileOffset: Long, val diskOffset: Long, val length: Long)

    /** 读出来的 BCD 及其在卷里的物理位置。 */
    data class Dump(
        val bytes: ByteArray,
        val segments: List<MapSegment>,
        val windowBytes: Long,
        val rounds: Int,
    )

    data class Report(
        val sizeBytes: Long,
        val volumeRefs: List<Found>,
        val signatures: List<Signature>,
        val currentDiskSignature: Long,
        val hexPreview: String,
        val summary: String,
        val devices: List<DeviceStruct> = emptyList(),
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
        val devices = findDeviceStructs(bytes)
        return Report(
            sizeBytes = bytes.size.toLong(),
            volumeRefs = refs,
            signatures = sigs,
            currentDiskSignature = -1,
            hexPreview = hexPreview(bytes),
            summary = describeDevices(devices, partitionStartBytes, 0L) + "；" + describe(refs, sigs),
            devices = devices,
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

    // ------------------------------------------------------------------ 已有分区：按需区域读取

    private class Window {
        private val ranges = ArrayList<LongArray>()

        fun covers(off: Long, len: Long): Boolean =
            ranges.any { off >= it[0] && off + len <= it[0] + it[1] }

        fun add(off: Long, len: Long) {
            ranges.add(longArrayOf(off, len))
        }

        fun total(): Long = ranges.sumOf { it[1] }

        fun save(file: File) {
            file.writeText(ranges.joinToString("\n") { "${it[0]} ${it[1]}" })
        }
    }

    /** 从 qcow2 里把 [off, off+len) 抽进稀疏窗口文件（全 0 的块留空洞，不占空间）。 */
    private fun materialize(
        img: Qcow2Image,
        entry: PartitionEntry,
        off: Long,
        len: Long,
        raw: File,
    ): Boolean = runCatching {
        RandomAccessFile(raw, "rw").use { raf ->
            val chunk = 1L shl 20
            var pos = 0L
            while (pos < len) {
                val want = minOf(chunk, len - pos).toInt()
                val bytes = img.readBytes(entry.startByte + off + pos, want)
                if (bytes.isEmpty()) break
                raf.seek(off + pos)
                if (bytes.any { it.toInt() != 0 }) raf.write(bytes)
                pos += bytes.size
            }
        }
        true
    }.getOrElse { false }

    /**
     * 从引导扇区推出来的"必读区域"猜测（少跑几轮）：引导扇区、$MFT 前 1 MiB、
     * 以及 mkntfs 放置元数据的 1/8 与 1/2 处。猜错只是多读一点，不影响正确性。
     */
    private fun primeRegions(img: Qcow2Image, entry: PartitionEntry): List<LongArray> {
        val out = ArrayList<LongArray>()
        out.add(longArrayOf(0L, 64L * 1024))
        val bs = runCatching { img.readBytes(entry.startByte, 512) }.getOrNull() ?: return out
        if (bs.size < 512 || String(bs, 3, 4, Charsets.US_ASCII) != "NTFS") return out
        val bps = (bs[0x0b].toInt() and 0xff) or ((bs[0x0c].toInt() and 0xff) shl 8)
        val spc = bs[0x0d].toInt() and 0xff
        val cluster = (bps.toLong() * spc).coerceAtLeast(512L)
        fun u64(at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (bs[at + i].toLong() and 0xff)
            return v
        }
        val totalSectors = u64(0x28)
        val mftOff = u64(0x30) * cluster
        val clusters = if (spc > 0) totalSectors / spc else 0L
        out.add(longArrayOf(mftOff, 1024L * 1024))
        if (clusters > 0) {
            out.add(longArrayOf(clusters / 8 * cluster, 512L * 1024))
            out.add(longArrayOf(clusters / 2 * cluster, 64L * 1024))
        }
        return out
    }

    private fun parseNeed(line: String): LongArray? {
        if (!line.startsWith("NEED ")) return null
        val p = line.removePrefix("NEED ").trim().split(' ')
        if (p.size < 2) return null
        val off = p[0].toLongOrNull() ?: return null
        val len = p[1].toLongOrNull() ?: return null
        return if (len > 0) longArrayOf(off, len) else null
    }

    private fun parseMap(line: String): MapSegment? {
        if (!line.startsWith("MAP ")) return null
        val p = line.removePrefix("MAP ").trim().split(' ')
        if (p.size < 3) return null
        val fo = p[0].toLongOrNull() ?: return null
        val disk = p[1].toLongOrNull() ?: return null
        val len = p[2].toLongOrNull() ?: return null
        return if (len > 0) MapSegment(fo, disk, len) else null
    }

    /** 按需读取分区里的 `\Boot\BCD`；读不到返回 null（日志里会有原因）。 */
    suspend fun readBcd(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit,
    ): Dump? = Qcow2Image.open(image).use { img ->
        val stamp = System.currentTimeMillis()
        val raw = File(AppPaths.tmp, "bcdw-$stamp.raw")
        val reg = File(AppPaths.tmp, "bcdw-$stamp.regions")
        val out = File(AppPaths.tmp, "bcdw-$stamp.bcd")
        val window = Window()
        try {
            // 长度必须是整个分区大小（NTFS 才知道卷多大），数据只写需要的区域，其余是空洞。
            RandomAccessFile(raw, "rw").use { it.setLength(entry.sizeBytes) }
            for (r in primeRegions(img, entry)) {
                val off = r[0]
                val len = minOf(r[1], entry.sizeBytes - off)
                if (off < 0 || len <= 0) continue
                if (materialize(img, entry, off, len, raw)) window.add(off, len)
            }
            onLog("按需读取 \\Boot\\BCD（分区 ${Fmt.size(entry.sizeBytes)}，窗口随需增长）…")

            var rounds = 0
            while (rounds < MAX_ROUNDS) {
                rounds++
                window.save(reg)
                val res = ToolRunner.run(
                    context,
                    NativeTools.NTFS_APPLY,
                    listOf(
                        raw.absolutePath, "--regions", reg.absolutePath,
                        "--dump", "Boot/BCD", out.absolutePath,
                    ),
                    timeoutMs = TOOL_TIMEOUT_MS,
                    onLine = { line -> if (!line.startsWith("NEED ")) onLog(line) },
                )
                if (res.exitCode == 0 && out.length() > 0L) {
                    val segments = res.lines.mapNotNull { parseMap(it) }
                    onLog(
                        "已读出 Boot/BCD（${Fmt.size(out.length())}）：第 $rounds 轮，" +
                            "窗口 ${Fmt.size(window.total())}，物理位置 ${segments.size} 段",
                    )
                    return Dump(out.readBytes(), segments, window.total(), rounds)
                }
                val needs = res.lines.mapNotNull { parseNeed(it) }
                    .map { longArrayOf(it[0], minOf(it[1], entry.sizeBytes - it[0])) }
                    .filter { it[1] > 0 && !window.covers(it[0], it[1]) }
                if (needs.isEmpty()) {
                    onLog("工具没有再要新的区域（rc=${res.exitCode}）：${res.lines.lastOrNull().orEmpty()}")
                    return null
                }
                var added = 0L
                for (n in needs) {
                    if (window.total() + n[1] > MAX_WINDOW_BYTES) {
                        onLog("还需要 ${Fmt.size(window.total() + n[1])}，超过 ${Fmt.size(MAX_WINDOW_BYTES)} 上限，放弃")
                        return null
                    }
                    if (materialize(img, entry, n[0], n[1], raw)) {
                        window.add(n[0], n[1])
                        added += n[1]
                    }
                }
                if (added == 0L) {
                    onLog("补齐区域失败（临时空间不足？）")
                    return null
                }
                onLog("第 $rounds 轮：补齐 ${Fmt.size(added)} 元数据（窗口共 ${Fmt.size(window.total())}）")
            }
            onLog("超过 $MAX_ROUNDS 轮仍未读出 BCD")
            null
        } finally {
            raw.delete()
            reg.delete()
            out.delete()
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

    suspend fun analyzeInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): Report {
        val current = currentSignature(image)
        val dump = readBcd(context, image, entry, onLog)
            ?: return Report(
                0, emptyList(), emptyList(), current, "",
                "没读到 \\Boot\\BCD：分区里可能没有它（那就得用 Win7 安装盘的「启动修复」/ bcdboot）",
            )
        val refs = scan(dump.bytes)
        val sigs = candidateSignatures(dump.bytes, entry.startByte, entry.startLba)
        val devices = findDeviceStructs(dump.bytes)
        return Report(
            sizeBytes = dump.bytes.size.toLong(),
            volumeRefs = refs,
            signatures = sigs,
            currentDiskSignature = current,
            hexPreview = hexPreview(dump.bytes),
            summary = describeDevices(devices, entry.startByte, current) + "；" + describe(refs, sigs),
            devices = devices,
        )
    }

    /** 设备结构的可读摘要：BCD 期望什么 vs 本盘实际是什么。 */
    fun describeDevices(devices: List<DeviceStruct>, partitionStart: Long, diskSignature: Long): String {
        if (devices.isEmpty()) {
            return "BCD 里没找到「分区设备」结构（可能是文本卷号形式，或格式特殊）"
        }
        val wantOffsets = devices.map { it.expectedOffset }.distinct()
        val wantSigs = devices.map { it.expectedSignature }.distinct()
        val sameOffset = wantOffsets.size == 1 && wantOffsets.first() == partitionStart
        val sameSig = diskSignature != 0L && wantSigs.size == 1 && wantSigs.first() == diskSignature
        return buildString {
            append("分区设备结构 ${devices.size} 处：期望偏移 ")
            append(wantOffsets.joinToString("、") { "$it（LBA ${it / 512}）" })
            append("，期望磁盘签名 ")
            append(wantSigs.joinToString("、") { String.format("0x%08X", it) })
            append("；本分区偏移 $partitionStart（LBA ${partitionStart / 512}），本磁盘签名 ")
            append(String.format("0x%08X", diskSignature))
            if (!sameOffset || !sameSig) {
                append(" → 不一致，这就是 0xC000000E 的原因，点「修复 BCD（指向本分区）」")
            } else {
                append(" → 已一致")
            }
        }
    }

    /** 导出 BCD 到可访问目录（便于发给我分析）。 */
    suspend fun exportFromPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): String {
        val dump = readBcd(context, image, entry, onLog) ?: return "没读到 BCD，无法导出"
        val dir = GuestFsOps.publicExportDir() ?: File(AppPaths.externalRoot(), "bcd").apply { mkdirs() }
        val out = File(dir, "BCD-p${entry.index}.bin")
        return runCatching {
            out.writeBytes(dump.bytes)
            "已导出 ${dump.bytes.size} 字节 → ${out.absolutePath}"
        }.getOrElse { "导出失败：${it.message}" }
    }

    /** 把 [patched] 里相对 [original] 有变化的段落写回卷里的物理位置（按 MAP）。 */
    private fun writeBackChanged(
        image: File,
        entry: PartitionEntry,
        original: ByteArray,
        patched: ByteArray,
        segments: List<MapSegment>,
    ): Int {
        var written = 0
        for (seg in segments) {
            val from = seg.fileOffset.toInt()
            val to = (seg.fileOffset + seg.length).toInt().coerceAtMost(minOf(patched.size, original.size))
            if (from < 0 || from >= to) continue
            var dirty = false
            for (i in from until to) {
                if (patched[i] != original[i]) {
                    dirty = true
                    break
                }
            }
            if (!dirty) continue
            val slice = patched.copyOfRange(from, to)
            val ok = runCatching {
                Qcow2Image.open(image, writable = true).use { img ->
                    img.write(entry.startByte + seg.diskOffset, slice)
                    img.flush()
                }
                true
            }.getOrDefault(false)
            if (!ok) return -written - 1
            written++
        }
        return written
    }

    /** **签名对齐**：把 MBR 磁盘签名改成 BCD 期望的值（只改 4 字节）。 */
    suspend fun alignDiskSignature(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): String {
        val report = analyzeInPartition(context, image, entry, onLog)
        val fromDevices = report.devices.map { it.expectedSignature }.distinct()
        val candidates = if (fromDevices.isNotEmpty()) fromDevices else report.signatures.map { it.expected }.distinct()
        if (candidates.isEmpty()) {
            return "BCD 里没找到期望的磁盘签名 → 请点「导出 BCD」，把文件发我做精确修复"
        }
        if (candidates.size > 1) {
            return "BCD 里有多个不同签名（${candidates.joinToString("、") { String.format("0x%08X", it) }}），" +
                "请改用「修复 BCD（指向本分区）」"
        }
        val target = candidates.first()
        val old = String.format("0x%08X", report.currentDiskSignature)
        val ok = PartitionOps.setDiskSignature(image, target)
        return if (ok) {
            "已把磁盘签名对齐到 BCD 期望值：$old → " + String.format("0x%08X", target) +
                "（只改了 MBR 的 4 字节）。注意：BCD 期望的分区偏移是 " +
                report.devices.map { it.expectedOffset }.distinct().joinToString("、") +
                "，若与本分区偏移 ${entry.startByte} 不同，仍然起不来 —— 那就用「修复 BCD（指向本分区）」。"
        } else {
            "写入磁盘签名失败"
        }
    }

    /**
     * **修复 BCD（推荐）**：把 BCD 里所有「分区设备」结构改成指向**本分区**
     *（分区字节偏移 + 本磁盘签名），就地写回。
     *
     * 这是 0xC000000E 的正解：Win7 的 BCD 里存的是**原机器**的
     * `(分区偏移 32256 / LBA63, 磁盘签名 0xE488E488)`，我们的盘两者都不一样，
     * 所以只把 MBR 签名改一致还不够 —— 分区偏移也要跟着改。
     */
    suspend fun fixDevicesInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
    ): String {
        val dump = readBcd(context, image, entry, onLog) ?: return "没读到 Boot\\BCD"
        if (dump.segments.isEmpty()) return "工具没给出 BCD 的物理位置，无法就地改写"
        val hadSignature = currentSignature(image)
        if (hadSignature == 0L && !PartitionOps.setDiskSignature(image, GrubBoot.DEFAULT_DISK_SIGNATURE)) {
            return "写入磁盘签名失败"
        }
        val signature = currentSignature(image)
        val (patched, structs) = patchDeviceStructs(dump.bytes, entry.startByte, signature)
        if (structs.isEmpty()) return "BCD 里没有「分区设备」结构（可能是文本卷号形式，用「修复卷号」）"
        val written = writeBackChanged(image, entry, dump.bytes, patched, dump.segments)
        if (written < 0) return "写回失败（分区偏移 ${-written - 1}）"
        val before = structs.map { "${it.expectedOffset}/" + String.format("0x%08X", it.expectedSignature) }.distinct()
        val extra = if (hadSignature == 0L) {
            "（原 MBR 磁盘签名是 0，已生成 " + String.format("0x%08X", signature) +
                "—— 否则 Windows 开机时会自己分配一个，BCD 又会对不上）"
        } else {
            ""
        }
        return "BCD 已指向本分区：${structs.size} 处分区设备结构 " +
            "${before.joinToString("、")} → ${entry.startByte}（LBA ${entry.startLba}）/" +
            String.format("0x%08X", signature) + "；写回 $written 段。$extra 重启试引导。"
    }

    /**
     * 释放流程用：直接改**staging 里的 BCD 文件**（普通文件，最省事），
     * 这样写进分区的 BCD 一开始就是对的。
     */
    fun fixDevicesInStaging(staging: File, partitionStart: Long, signature: Long): String {
        val bcd = bcdFile(staging) ?: return "镜像里没有 Boot\\BCD"
        val bytes = runCatching { bcd.readBytes() }.getOrNull() ?: return "读不到 ${bcd.absolutePath}"
        val (patched, structs) = patchDeviceStructs(bytes, partitionStart, signature)
        if (structs.isEmpty()) return "BCD 里没有「分区设备」结构（不需要改）"
        val ok = runCatching { bcd.writeBytes(patched); true }.getOrDefault(false)
        if (!ok) return "写入 BCD 失败"
        val before = structs.map { "${it.expectedOffset}/" + String.format("0x%08X", it.expectedSignature) }.distinct()
        return "BCD 设备结构已指向本分区：${structs.size} 处 ${before.joinToString("、")} → " +
            "$partitionStart（LBA ${partitionStart / 512}）/" + String.format("0x%08X", signature)
    }

    /**
     * 文本卷号形式的就地修复：读出 BCD → 改卷号 → **只把变化的字节写回它在卷里的物理位置**。
     */
    suspend fun fixVolumeInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        targetVolume: Int,
        onLog: (String) -> Unit = {},
    ): String {
        val dump = readBcd(context, image, entry, onLog) ?: return "没读到 Boot\\BCD"
        if (scan(dump.bytes).isEmpty()) return "BCD 用的是二进制设备项，请用「修复 BCD（指向本分区）」"
        if (dump.segments.isEmpty()) return "工具没给出 BCD 的物理位置，无法就地改写"
        val (patched, notes) = rewrite(dump.bytes, targetVolume)
        val written = writeBackChanged(image, entry, dump.bytes, patched, dump.segments)
        if (written < 0) return "写回失败（分区偏移 ${-written - 1}）"
        return if (written == 0) {
            "BCD 卷号本来就是 Volume$targetVolume，无需修改"
        } else {
            "BCD 卷号已就地改为 Volume$targetVolume（${notes.joinToString("；")}），写回 $written 段。"
        }
    }
}