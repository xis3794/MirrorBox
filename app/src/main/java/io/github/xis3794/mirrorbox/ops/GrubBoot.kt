package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.Qcow2Image
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionTableInfo
import java.io.File

/**
 * GRUB（BIOS）引导安装。
 *
 * 背景：syslinux 的 `mbr.bin` 只是「跳到活动分区的引导扇区」，分区里没有引导器时 BIOS 会直接报
 * “no bootable device / 找不到硬盘” —— 这就是「写入 BIOS 引导后 QEMU 仍启动不了」的真正原因。
 * 要让新建的磁盘真的能启动，必须装一个带 stage2 的引导器：
 *
 *   LBA 0      : `boot.img` 的 0..445 字节写进 MBR 代码区（分区表与 0x55AA 保持原样）
 *   LBA 1 起   : `core.img`（约 100~300 KiB，内嵌 biosdisk/part_msdos/fat/ext2/chain/linux 等模块）
 *   分区内      : `/boot/grub/grub.cfg`（由「格式化分区 / 释放 WIM」流程注入，找不到内核时也给菜单+命令行）
 *
 * core.img 里的早期配置会用 `search --file /boot/grub/grub.cfg` 定位引导分区，所以引导分区不一定是第一个。
 *
 * 这两个文件由 CI 用 `grub-mkimage` 生成，并且**在 QEMU/SeaBIOS 里实测启动通过**才算数
 * （见 `native/scripts/build-bios-blobs.sh` 与产物里的 `qemu.log`）。
 */
object GrubBoot {

    const val ASSET_DIR = "grub"
    const val MBR_CODE_BYTES = 446
    const val CORE_LBA = 1L

    /** 我们的布局里第一个分区从 LBA 2048 开始，core.img 必须落在它前面。 */
    const val MIN_PARTITION_START_LBA = 2048L

    fun read(context: Context, name: String): ByteArray? =
        runCatching { context.assets.open("$ASSET_DIR/$name").use { it.readBytes() } }.getOrNull()

    fun available(context: Context): Boolean =
        (read(context, "core.img")?.size ?: 0) > 0 && (read(context, "boot.img")?.size ?: 0) >= MBR_CODE_BYTES

    fun coreSize(context: Context): Int = read(context, "core.img")?.size ?: 0

    /** 检查 core.img 有没有地方放；返回 null 表示可以安装，否则是原因。 */
    fun checkSpace(table: PartitionTableInfo?, coreBytes: Int): String? {
        if (table == null || table.scheme != "MBR") {
            return "GRUB 的 BIOS 版本需要 MBR 分区表（当前：${table?.scheme ?: "无"}）"
        }
        if (coreBytes <= 0) return "缺少内置 core.img（请重装完整 APK）"
        val first = table.partitions.minByOrNull { it.startLba } ?: return "磁盘上还没有分区：先应用分区表"
        val needSectors = (coreBytes + 511L) / 512L + CORE_LBA
        return if (first.startLba < needSectors) {
            "第一个分区从 LBA ${first.startLba} 开始，放不下 core.img（需要 ${needSectors} 扇区）——" +
                " 请把第一个分区起始设为 ≥ ${MIN_PARTITION_START_LBA}（1 MiB 对齐）"
        } else {
            null
        }
    }

    /** 安装 GRUB 的 BIOS 引导：MBR 代码 + core.img + 激活第一个分区。 */
    fun install(
        context: Context,
        image: File,
        setFirstActive: Boolean = true,
    ): PartitionOps.PartitionResult = runCatching {
        val boot = read(context, "boot.img")
            ?: return PartitionOps.PartitionResult(false, "缺少内置 boot.img（请重装完整 APK）")
        val core = read(context, "core.img")
            ?: return PartitionOps.PartitionResult(false, "缺少内置 core.img（请重装完整 APK）")
        val table = EditOps.detectPartitionTable(image)
        checkSpace(table, core.size)?.let { return PartitionOps.PartitionResult(false, it) }

        Qcow2Image.open(image, writable = true).use { img ->
            val mbr = img.readBytes(0L, 512)
            System.arraycopy(boot, 0, mbr, 0, MBR_CODE_BYTES)
            mbr[510] = 0x55
            mbr[511] = 0xAA.toByte()
            if (setFirstActive) {
                val hasActive = (0 until 4).any { mbr[446 + it * 16].toInt() and 0xff == 0x80 }
                if (!hasActive) {
                    for (i in 0 until 4) {
                        val off = 446 + i * 16
                        if (mbr[off + 4].toInt() and 0xff != 0) {
                            mbr[off] = 0x80.toByte()
                            break
                        }
                    }
                }
            }
            img.write(0L, mbr)
            img.write(CORE_LBA * 512, core)
            img.flush()
        }
        PartitionOps.PartitionResult(
            true,
            "已安装 GRUB BIOS 引导（core.img ${core.size / 1024} KiB @ LBA $CORE_LBA，分区表与签名未改动）",
        )
    }.getOrElse { PartitionOps.PartitionResult(false, "安装 GRUB 失败：${it.message}") }

    /** 把 `/boot/grub/grub.cfg` 注入到「待写入目录」，随格式化/释放一起落盘。 */
    fun injectConfig(
        context: Context,
        staging: File,
        fsKind: EditOps.FsKind? = null,
        @Suppress("UNUSED_PARAMETER") label: String? = null,
    ): String? {
        val config = buildConfigFromDirectory(context, staging, fsKind)
        val dir = File(staging, "boot/grub")
        dir.mkdirs()
        return runCatching {
            File(dir, "grub.cfg").writeText(config)
            "已写入 /boot/grub/grub.cfg（按目录里实际存在的 bootmgr/vmlinuz 生成菜单" +
                if (fsKind == EditOps.FsKind.NTFS) "；NTFS 上 Windows 走 ntldr 加载 bootmgr）" else "）"
        }.getOrNull()
    }

    /**
     * 生成 `/boot/grub/grub.cfg`。
     *
     * 关键点（针对真实踩到的坑）：
     *  - 第一项永远是 `chainloader +1`（引导本分区引导扇区）——**必定可用**，等价于传统 MBR 启动；
     *  - `bootmgr` / `vmlinuz` 这些项只有在**探测到文件存在**时才写进去，并且用 `--force`
     *    绕开 GRUB 的 0x55AA 校验（否则就会出现 “error: invalid signature.”）；
     *  - 最后一项是带提示的命令行，方便用户在 `grub>` 里自己 `ls` 排查。
     */
    fun buildConfig(
        context: Context,
        available: Collection<String>,
        fsKind: EditOps.FsKind? = null,
    ): String {
        fun has(name: String) = available.any { it.equals(name, ignoreCase = true) }
        val kernel = listOf("vmlinuz", "bzImage", "kernel").firstOrNull { has(it) }
        val initrd = listOf("initrd.img", "initrd", "initramfs.img").firstOrNull { has(it) }
        val bootmgr = has("bootmgr") || has("BOOTMGR")
        val efi = available.any { it.contains("bootx64.efi", ignoreCase = true) }
        // NTFS 分区上 mkntfs 写的 VBR 只是"这不是启动盘"，绝不能用 chainloader +1；
        // Windows 必须走 GRUB 的 ntldr 命令，由它自己解析 NTFS 并加载 bootmgr。
        val windowsViaNtldr = bootmgr && fsKind == EditOps.FsKind.NTFS

        return buildString {
            // 菜单标题刻意用英文：core.img 里没有 unicode.pf2 字体，中文在 VGA/串口下会变成方框。
            appendLine("# MirrorBox 生成的 GRUB 配置（BIOS）—— 标题用英文，避免缺字体时显示成方框")
            appendLine("insmod part_msdos")
            appendLine("insmod fat")
            appendLine("insmod ext2")
            appendLine("insmod ntfs")
            appendLine("insmod chain")
            appendLine("insmod ntldr")
            appendLine("set timeout=15")
            appendLine("set default=0")
            appendLine()
            if (windowsViaNtldr) {
                appendLine("menuentry \"(1) Windows bootmgr via ntldr  [BIOS, NTFS]\" {")
                appendLine("  ntldr /bootmgr")
                appendLine("}")
                appendLine()
            }
            appendLine("menuentry \"(2) Boot this partition's VBR  [chainloader +1]\" {")
            appendLine("  chainloader +1")
            appendLine("}")
            appendLine()
            if (bootmgr && !windowsViaNtldr) {
                appendLine("menuentry \"(3) Windows bootmgr  [chainloader --force /bootmgr]\" {")
                appendLine("  chainloader --force /bootmgr")
                appendLine("}")
                appendLine()
            }
            if (kernel != null) {
                appendLine("menuentry \"(4) Linux kernel  [/$kernel]\" {")
                appendLine("  linux /$kernel")
                if (initrd != null) appendLine("  initrd /$initrd")
                appendLine("}")
                appendLine()
            }
            appendLine("menuentry \"(5) GRUB command line (diagnostics)\" {")
            appendLine("  echo \"ls = list disks ; ls (hd0,msdos1)/ = list partition ; cat (hd0,msdos1)/boot/grub/grub.cfg\"")
            appendLine("}")
            if (efi) {
                appendLine()
                appendLine("# 提示：分区里检测到 EFI/ 目录。Windows 在现代设备上走 UEFI 更可靠：")
                appendLine("#      用 FAT32 分区 + EFI/Boot/bootx64.efi，并在模拟器里选择 UEFI 固件（OVMF）。")
            }
        }
    }

    /** 从「待写入目录」生成配置（释放 WIM / 格式化分区时用）。 */
    fun buildConfigFromDirectory(
        context: Context,
        staging: File,
        fsKind: EditOps.FsKind? = null,
    ): String {
        val names = ArrayList<String>()
        runCatching {
            staging.listFiles()?.forEach { names.add(it.name) }
            File(staging, "EFI/Boot").listFiles()?.forEach { names.add("EFI/Boot/${it.name}") }
        }
        return buildConfig(context, names, fsKind)
    }

    private fun fallbackConfig(): String = """
        |# MirrorBox: 没有探测到内核/bootmgr —— 用最保守的菜单（chainloader +1 一定可用）。
        |set timeout=15
        |set default=0
        |
        |menuentry "① 启动本分区引导扇区（chainloader +1）" {
        |  chainloader +1
        |}
        |
        |menuentry "② GRUB 命令行（排查用）" {
        |  echo "ls 列磁盘；ls (hd0,msdos1)/ 列分区"
        |}
    """.trimMargin()

    /**
     * 把 `/boot/grub/grub.cfg` 写进**已经存在**的分区（FAT32 / ext4）。
     *
     * 走安全轨：提取分区 → 建目录 + 拷文件 → 差分回写（需要与分区等大的临时空间，
     * 因为要读到分区原有内容）。NTFS 不支持（建议用「释放 WIM」流程，那里会随镜像一起写好）。
     */
    suspend fun installConfigIntoPartition(
        context: Context,
        image: File,
        entry: io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry,
        kind: EditOps.FsKind,
        onLog: (String) -> Unit = {},
        onProgress: ((Long) -> Unit)? = null,
    ): String {
        if (kind == EditOps.FsKind.NTFS) {
            // NTFS 也支持：用我们自己的写入器把 /boot/grub/grub.cfg 灌进提取出来的 raw。
            return installConfigIntoNtfs(context, image, entry, onLog, onProgress)
        }
        val stamp = System.currentTimeMillis()
        val tmp = File(io.github.xis3794.mirrorbox.core.AppPaths.tmp, "grubcfg-p${entry.index}-$stamp.raw")
        val cfgFile = File(io.github.xis3794.mirrorbox.core.AppPaths.tmp, "grub-$stamp.cfg")
        try {
            onLog("① 提取分区 ${entry.index}（需要一个与分区等大的临时副本）")
            if (!EditOps.extractRange(image, entry.startByte, entry.sizeBytes, tmp, onProgress)) {
                return "无法提取分区 ${entry.index}（空间不足？）"
            }
            cfgFile.writeText(buildConfig(context, probePartition(context, tmp, kind, onLog), kind))
            onLog("② 写入 /boot/grub/grub.cfg")
            when (kind) {
                EditOps.FsKind.FAT32 -> {
                    // 目录可能已存在：mmd 失败无所谓，继续拷贝。
                    ToolRunner.run(context, NativeTools.MMD, listOf("-i", tmp.absolutePath, "::/boot"), onLine = onLog)
                    ToolRunner.run(context, NativeTools.MMD, listOf("-i", tmp.absolutePath, "::/boot/grub"), onLine = onLog)
                    val copy = ToolRunner.run(
                        context, NativeTools.MCOPY,
                        listOf("-o", "-i", tmp.absolutePath, cfgFile.absolutePath, "::/boot/grub/grub.cfg"),
                        onLine = onLog,
                    )
                    if (!copy.success) return "mcopy 写入失败：${copy.lines.lastOrNull().orEmpty()}"
                }
                EditOps.FsKind.EXT4 -> {
                    ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-w", "-R", "mkdir /boot", tmp.absolutePath), onLine = onLog)
                    ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-w", "-R", "mkdir /boot/grub", tmp.absolutePath), onLine = onLog)
                    val write = ToolRunner.run(
                        context, NativeTools.DEBUGFS,
                        listOf("-w", "-R", "write ${cfgFile.absolutePath} /boot/grub/grub.cfg", tmp.absolutePath),
                        onLine = onLog,
                    )
                    if (write.lines.any { it.contains("Error", ignoreCase = true) }) {
                        return "debugfs 写入失败：${write.lines.lastOrNull().orEmpty()}"
                    }
                }
                EditOps.FsKind.NTFS -> return "NTFS 分支已在上方处理"
            }
            onLog("③ 差分回写")
            val written = EditOps.writeBackRange(image, entry.startByte, entry.sizeBytes, tmp, onProgress)
            return "已写入分区 ${entry.index} 的 /boot/grub/grub.cfg（变化 ${written.changedClusters} 个簇）"
        } catch (t: Throwable) {
            return "写入 grub.cfg 失败：${t.message}"
        } finally {
            tmp.delete()
            cfgFile.delete()
        }
    }

    /** NTFS 分区：提取 raw → 用 mirrorbox-ntfs 把 boot/grub/grub.cfg 灌进去 → 差分回写。 */
    private suspend fun installConfigIntoNtfs(
        context: Context,
        image: File,
        entry: io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry,
        onLog: (String) -> Unit,
        onProgress: ((Long) -> Unit)?,
    ): String {
        val stamp = System.currentTimeMillis()
        val tmp = File(io.github.xis3794.mirrorbox.core.AppPaths.tmp, "grubcfg-ntfs-p${entry.index}-$stamp.raw")
        val stage = File(io.github.xis3794.mirrorbox.core.AppPaths.tmp, "grubcfg-stage-$stamp")
        try {
            onLog("① 提取分区 ${entry.index}（NTFS）")
            if (!EditOps.extractRange(image, entry.startByte, entry.sizeBytes, tmp, onProgress)) {
                return "无法提取分区 ${entry.index}（空间不足？）"
            }
            val dir = File(stage, "boot/grub")
            dir.mkdirs()
            File(dir, "grub.cfg").writeText(buildConfig(context, probePartition(context, tmp, EditOps.FsKind.NTFS, onLog), EditOps.FsKind.NTFS))
            onLog("② 用 mirrorbox-ntfs 写入 /boot/grub/grub.cfg")
            val apply = ToolRunner.run(
                context,
                NativeTools.NTFS_APPLY,
                listOf(tmp.absolutePath, stage.absolutePath),
                onLine = onLog,
            )
            if (!apply.success) return "写入 NTFS 失败：${apply.lines.lastOrNull().orEmpty()}"
            onLog("③ 差分回写")
            val written = EditOps.writeBackRange(image, entry.startByte, entry.sizeBytes, tmp, onProgress)
            return "已写入分区 ${entry.index}（NTFS）的 /boot/grub/grub.cfg（变化 ${written.changedClusters} 个簇）"
        } catch (t: Throwable) {
            return "写入 NTFS grub.cfg 失败：${t.message}"
        } finally {
            tmp.delete()
            stage.deleteRecursively()
        }
    }

    /** 探测分区根目录里有哪些文件（用来生成"只包含真实存在项"的菜单）。 */
    private suspend fun probePartition(
        context: Context,
        raw: File,
        kind: EditOps.FsKind,
        onLine: (String) -> Unit,
    ): List<String> {
        val names = ArrayList<String>()
        when (kind) {
            EditOps.FsKind.FAT32 -> {
                val result = ToolRunner.run(context, NativeTools.MDIR, listOf("-i", raw.absolutePath, "-b", "::/"), onLine = onLine)
                result.lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("::")) {
                        val cleaned = trimmed.removePrefix("::").trimEnd('/').substringAfterLast('/')
                        if (cleaned.isNotEmpty()) names.add(cleaned)
                    }
                }
            }
            EditOps.FsKind.EXT4 -> {
                val result = ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-R", "ls -l /", raw.absolutePath), onLine = onLine)
                result.lines.forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8) names.add(parts.last())
                }
            }
            EditOps.FsKind.NTFS -> {
                val result = ToolRunner.run(context, NativeTools.NTFSLS, listOf("-p", "/", raw.absolutePath), onLine = onLine)
                result.lines.forEach { if (it.isNotBlank()) names.add(it.trim()) }
            }
        }
        return names
    }

    /** 诊断：到底为什么 BIOS 说"找不到硬盘 / 无法启动"。 */
    fun diagnose(context: Context, image: File): List<String> {
        val lines = ArrayList<String>()
        val table = EditOps.detectPartitionTable(image)
        val mbr = runCatching { Qcow2Image.open(image).use { it.readBytes(0L, 512) } }.getOrNull()
        if (mbr == null) {
            lines.add("✗ 读不到 MBR：镜像不是有效的 qcow2（或已损坏）")
            return lines
        }
        val signatureOk = mbr[510].toInt() and 0xff == 0x55 && mbr[511].toInt() and 0xff == 0xAA
        lines.add(if (signatureOk) "✓ MBR 签名 0x55AA 正常" else "✗ MBR 签名缺失 → BIOS 根本不认这块盘")

        val codeNonZero = (0 until 440).count { mbr[it].toInt() != 0 }
        lines.add("· MBR 引导代码非零字节：$codeNonZero/440")
        if (codeNonZero == 0) lines.add("  ✗ 引导代码是空的 → BIOS 会报 “no bootable device”")

        if (table == null || table.scheme == "none") {
            lines.add("✗ 没有分区表 → 先「一键布局 + 应用分区表」")
            return lines
        }
        lines.add("· 分区表：${table.scheme}，${table.partitions.size} 个分区")
        val active = table.partitions.firstOrNull { it.bootable }
        lines.add(
            if (active != null) "✓ 活动分区：分区 ${active.index}（${active.typeName}）"
            else "✗ 没有活动分区 → BIOS 不会引导任何分区",
        )

        val first = table.partitions.minByOrNull { it.startLba }
        val coreBytes = coreSize(context)
        if (first != null) {
            lines.add(
                "· 第一个分区起始 LBA ${first.startLba}" +
                    if (first.startLba < MIN_PARTITION_START_LBA) "（偏小，core.img 放不下）" else "",
            )
        }
        lines.add(if (coreBytes > 0) "· 内置 core.img：${coreBytes / 1024} KiB" else "✗ 未内置 core.img（请重装完整 APK）")

        val probe = runCatching { Qcow2Image.open(image).use { it.readBytes(CORE_LBA * 512, 8192) } }.getOrNull()
        val looksGrub = probe != null && probe.any { it.toInt() != 0 }
        lines.add(
            if (looksGrub) "✓ LBA $CORE_LBA 起已有引导器数据（core.img）"
            else "✗ LBA $CORE_LBA 起是空的：没有 core.img，BIOS 无法启动",
        )

        // 分区引导扇区（VBR）：GRUB 的 “chainloader +1” 也需要它有 0x55AA 且不是空的。
        val boot = active ?: first
        if (boot != null) {
            val vbr = runCatching { Qcow2Image.open(image).use { it.readBytes(boot.startByte, 512) } }.getOrNull()
            if (vbr == null) {
                lines.add("✗ 读不到分区 ${boot.index} 的引导扇区")
            } else {
                val code = (0 until 510).count { vbr[it].toInt() != 0 }
                val ok = vbr[510].toInt() and 0xff == 0x55 && vbr[511].toInt() and 0xff == 0xAA
                lines.add(
                    "· 分区 ${boot.index} 引导扇区：非零 $code/510，" +
                        if (ok) "签名正常" else "✗ 缺 0x55AA 签名",
                )
                if (!ok || code < 16) {
                    lines.add("  → 该分区里没有引导器（VBR 为空/无效）：GRUB 的 chainloader +1 也会失败。")
                    lines.add("     解决：把系统写进这个分区（释放 WIM 时勾选“安装 GRUB”），或用 UEFI + FAT32(EFI/Boot/bootx64.efi)。")
                }
            }
        }
        lines.add("提示：MBR 代码只管“跳到分区引导扇区”，分区里必须有引导器（GRUB/bootmgr）才真能启动。")
        return lines
    }
}