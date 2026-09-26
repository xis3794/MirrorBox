package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry
import java.io.File
import java.nio.charset.Charset

/**
 * Windows 的 BCD（Boot Configuration Data）修复。
 *
 * 为什么需要它：把 install.wim 释放到分区后，`\Boot\BCD` 是从**原机器/原分区布局**继承来的，
 * 里面的"系统设备"往往写成 `\Device\HarddiskVolume2` 之类（原机器上 Windows 装在第二个卷）。
 * 我们的磁盘上分区编号不同 → bootmgr 找不到设备，报
 *
 *     状态: 0xC000000E
 *     信息: 引导选择失败，因为需要的设备不可访问。
 *
 * 引导链本身是好的（GRUB → ntldr → bootmgr 都执行了），差的只是 BCD 里的卷号。
 * 修复方式：把 BCD 二进制里所有 UTF-16LE 的 `\Device\HarddiskVolumeN` 改成目标分区号。
 * 长度不同时用 NUL 补齐（REG_SZ 以 NUL 结束，多余字节不会被读），因此不改变文件大小、
 * 不需要重算 hive 校验和。
 */
object BcdFix {

    private const val PREFIX = "\\Device\\HarddiskVolume"
    private val UTF16 = Charset.forName("UTF-16LE")

    data class Found(val offset: Int, val text: String, val volume: Int)

    fun utf16le(text: String): ByteArray = text.toByteArray(UTF16)

    /** 在二进制里找所有 `\Device\HarddiskVolumeN`（UTF-16LE）。 */
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
                while (k + 1 < bytes.size && bytes[k + 1].toInt() == 0 && bytes[k] in '0'.code.toByte()..'9'.code.toByte()) {
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

    /** 把卷号改成 [targetVolume]，保持文件长度不变。 */
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
            // 旧数字更长时用 NUL 补齐，保证字符串在这里结束、且不改变总长度
            while (pos < start + oldDigits * 2) {
                out[pos] = 0
                out[pos + 1] = 0
                pos += 2
            }
            notes.add("${item.text} → $PREFIX$targetVolume")
        }
        return out to notes
    }

    /** 在「待写入目录」里找 `Boot\BCD`（大小写不敏感，最多找 3 层）。 */
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

    /**
     * 释放流程用：把 staging 里的 BCD 卷号改成本磁盘上的目标分区号。
     *
     * @return 供日志显示的结果说明
     */
    fun fixInStaging(staging: File, targetPartition: Int): String {
        val bcd = bcdFile(staging)
            ?: return "镜像里没有 Boot\\BCD —— 如果 bootmgr 报 0xC000000E，需要用 Win7 安装盘跑「启动修复」/ bcdboot"
        val bytes = runCatching { bcd.readBytes() }.getOrNull()
            ?: return "读不到 ${bcd.absolutePath}"
        val found = scan(bytes)
        if (found.isEmpty()) {
            return "Boot\\BCD 里没有 \\Device\\HarddiskVolumeN 字符串（可能用的是二进制设备项）——" +
                " 如果启动失败请用 Win7 安装盘的「启动修复」重建 BCD"
        }
        val (patched, notes) = rewrite(bytes, targetPartition)
        val ok = runCatching { bcd.writeBytes(patched); true }.getOrDefault(false)
        return if (ok) {
            "BCD 设备已修正为分区 $targetPartition：${notes.joinToString("；")}"
        } else {
            "写入 BCD 失败：${bcd.absolutePath}"
        }
    }

    // ------------------------------------------------------------------ 已有分区：检查 / 修复

    data class Report(val volumeRefs: List<Found>, val notes: List<String>, val summary: String)

    /** 只读检查：从已有 NTFS 分区里取出 BCD 看看它引用了哪个卷。 */
    suspend fun inspectInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit = {},
        onProgress: ((Long) -> Unit)? = null,
    ): Report = withTemporaryRaw(context, image, entry, onLog, onProgress) { raw ->
        val bcd = readFromNtfs(context, raw, onLog) ?: return@withTemporaryRaw Report(
            emptyList(), emptyList(),
            "分区里没有找到 \\Boot\\BCD（用 Win7 安装盘的启动修复来建立）",
        )
        val refs = scan(bcd)
        Report(
            refs, emptyList(),
            if (refs.isEmpty()) {
                "BCD 存在，但没有 \\Device\\HarddiskVolumeN 字符串（可能是二进制设备项）"
            } else {
                "BCD 引用的卷：" + refs.map { it.text }.distinct().joinToString("、") +
                    "（本磁盘分区 ${entry.index} 若要作为系统盘，应为 \\Device\\HarddiskVolume${entry.index}）"
            },
        )
    }

    /** 就地修复：取出 BCD → 改卷号 → 写回（ntfscp 单文件写入，很快）。 */
    suspend fun fixInPartition(
        context: Context,
        image: File,
        entry: PartitionEntry,
        targetVolume: Int,
        onLog: (String) -> Unit = {},
        onProgress: ((Long) -> Unit)? = null,
    ): String = withTemporaryRaw(context, image, entry, onLog, onProgress) { raw ->
        val bcdBytes = readFromNtfs(context, raw, onLog)
            ?: return@withTemporaryRaw "分区里没有 \\Boot\\BCD，无法修复（建议用 Win7 安装盘的「启动修复」）"
        val found = scan(bcdBytes)
        if (found.isEmpty()) {
            return@withTemporaryRaw "BCD 里没有可识别的 \\Device\\HarddiskVolumeN 字符串，未修改"
        }
        val (patched, notes) = rewrite(bcdBytes, targetVolume)
        val tmp = File(AppPaths.tmp, "bcd-patched-${System.currentTimeMillis()}.bin")
        tmp.writeBytes(patched)
        try {
            val write = ToolRunner.run(
                context, NativeTools.NTFSCP,
                listOf(raw.absolutePath, tmp.absolutePath, "Boot/BCD"),
                onLine = onLog,
            )
            if (!write.success) {
                return@withTemporaryRaw "写回 BCD 失败：${write.lines.lastOrNull().orEmpty()}"
            }
        } finally {
            tmp.delete()
        }
        "BCD 已修复：${notes.joinToString("；")}（现在指向 \\Device\\HarddiskVolume$targetVolume）"
    }

    private suspend fun <T> withTemporaryRaw(
        context: Context,
        image: File,
        entry: PartitionEntry,
        onLog: (String) -> Unit,
        onProgress: ((Long) -> Unit)?,
        block: suspend (File) -> T,
    ): T {
        val raw = File(AppPaths.tmp, "bcd-${entry.index}-${System.currentTimeMillis()}.raw")
        try {
            onLog("① 提取分区 ${entry.index}（${Fmt.size(entry.sizeBytes)}，需要一个与分区等大的临时副本）")
            if (!EditOps.extractRange(image, entry.startByte, entry.sizeBytes, raw, onProgress)) {
                @Suppress("UNCHECKED_CAST")
                return "无法提取分区 ${entry.index}（空间不足？）" as T
            }
            val result = block(raw)
            onLog("② 差分回写")
            val written = EditOps.writeBackRange(image, entry.startByte, entry.sizeBytes, raw, onProgress)
            onLog("回写完成：变化 ${written.changedClusters} 个簇")
            return result
        } catch (t: Throwable) {
            @Suppress("UNCHECKED_CAST")
            return "BCD 操作失败：${t.message}" as T
        } finally {
            raw.delete()
        }
    }

    /** 用 ntfscat 把 BCD 取出来（二进制安全：直接重定向到文件）。 */
    private suspend fun readFromNtfs(context: Context, raw: File, onLog: (String) -> Unit): ByteArray? {
        val exe = io.github.xis3794.mirrorbox.core.NativeTools.resolve(context, NativeTools.NTFSCAT) ?: return null
        for (candidate in listOf("Boot/BCD", "boot/bcd", "/Boot/BCD")) {
            val out = File(AppPaths.tmp, "bcd-read-${System.currentTimeMillis()}.bin")
            val ok = runCatching {
                val pb = ProcessBuilder(listOf(exe.absolutePath, raw.absolutePath, candidate))
                pb.redirectOutput(ProcessBuilder.Redirect.to(out))
                pb.redirectErrorStream(false)
                pb.environment().putAll(ToolRunner.environment(context))
                val process = pb.start()
                val code = process.waitFor()
                code == 0
            }.getOrDefault(false)
            if (ok && out.length() > 0) {
                onLog("已读取 $candidate（${Fmt.size(out.length())}）")
                val bytes = out.readBytes()
                out.delete()
                return bytes
            }
            out.delete()
        }
        return null
    }
}