package io.github.xis3794.mirrorbox.ops

import android.content.Context
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
        kernel: String? = null,
        initrd: String? = null,
    ): String? {
        val config = buildConfig(context, staging, kernel, initrd) ?: return null
        val dir = File(staging, "boot/grub")
        dir.mkdirs()
        return runCatching {
            File(dir, "grub.cfg").writeText(config)
            "已写入 /boot/grub/grub.cfg"
        }.getOrNull()
    }

    fun buildConfig(
        context: Context,
        staging: File,
        kernel: String? = null,
        initrd: String? = null,
    ): String? {
        val foundKernel = kernel ?: listOf("vmlinuz", "bzImage", "kernel")
            .firstOrNull { File(staging, it).isFile }
        val foundInitrd = initrd ?: listOf("initrd.img", "initrd", "initramfs.img")
            .firstOrNull { File(staging, it).isFile }
        val hasBootmgr = File(staging, "bootmgr").isFile

        if (foundKernel == null && !hasBootmgr) {
            // 镜像里没有内核也没有 bootmgr：给一个"能进菜单/命令行"的配置，用户自己补文件。
            return runCatching { context.assets.open("$ASSET_DIR/grub.cfg").use { it.bufferedReader().readText() } }
                .getOrNull() ?: fallbackConfig()
        }
        return buildString {
            appendLine("# MirrorBox 生成的 GRUB 配置")
            appendLine("set timeout=5")
            appendLine("set default=0")
            appendLine()
            if (foundKernel != null) {
                appendLine("menuentry \"启动 Linux（/$foundKernel）\" {")
                appendLine("  linux /$foundKernel")
                if (foundInitrd != null) appendLine("  initrd /$foundInitrd")
                appendLine("}")
                appendLine()
            }
            if (hasBootmgr) {
                appendLine("menuentry \"从硬盘引导（Windows bootmgr）\" {")
                appendLine("  insmod chain")
                appendLine("  chainloader /bootmgr")
                appendLine("}")
                appendLine()
            }
            appendLine("menuentry \"GRUB 命令行\" {")
            appendLine("  echo \"用 ls 查看分区，例如: ls (hd0,msdos1)/\"")
            appendLine("}")
        }
    }

    private fun fallbackConfig(): String = """
        |# MirrorBox: 没有找到内核/bootmgr，这里是默认菜单。
        |set timeout=5
        |set default=0
        |
        |menuentry "GRUB 命令行" {
        |  echo "用 ls 查看分区，例如: ls (hd0,msdos1)/"
        |}
    """.trimMargin()

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
        lines.add("提示：MBR 代码只管“跳到分区引导扇区”，分区里必须有引导器（GRUB/bootmgr）才真能启动。")
        return lines
    }
}