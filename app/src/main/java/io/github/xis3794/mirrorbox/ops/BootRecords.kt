package io.github.xis3794.mirrorbox.ops

import android.content.Context
import java.io.File

/**
 * 内置引导记录（开箱即用）。
 *
 * 这些文件都是"纯数据"（x86 机器码），与 Android 本身无关：应用把它们从 assets 里读出来，直接写进
 * qcow2 的 MBR 扇区、或交给 xorriso 做 BIOS 引导，用户不必自己准备 mbr.bin。
 *
 * 来源：Debian/Ubuntu 的 syslinux-common + isolinux 包（syslinux 6.04，GPLv2+，见 assets/boot/NOTICE.txt）
 *  - mbr.bin       /usr/lib/syslinux/mbr/mbr.bin          BIOS MBR，跳转到活动分区
 *  - gptmbr.bin    /usr/lib/syslinux/mbr/gptmbr.bin       GPT 磁盘上的 BIOS 引导（保护性 MBR）
 *  - isohdpfx.bin  /usr/lib/ISOLINUX/isohdpfx.bin         让 ISO9660 镜像可从 U 盘 BIOS 启动
 *  - isolinux.bin  /usr/lib/ISOLINUX/isolinux.bin         El Torito BIOS 引导镜像
 *  - ldlinux.c32   /usr/lib/syslinux/modules/bios/ldlinux.c32  isolinux.bin 的核心模块
 */
object BootRecords {

    enum class Kind { MBR, HYBRID_MBR, ISO_BIOS, ISO_MODULE }

    data class Record(
        val asset: String,
        val title: String,
        val detail: String,
        val kind: Kind,
        val sizeBytes: Int,
    ) {
        /** 只有磁盘 MBR 类记录能写进镜像引导区。 */
        val installableInMbr: Boolean get() = kind == Kind.MBR
    }

    const val ASSET_DIR = "boot"

    val MBR = Record(
        "mbr.bin", "syslinux MBR（BIOS）",
        "写入 MBR 前 440 字节，跳转活动分区；MBR 磁盘首选", Kind.MBR, 440,
    )
    val GPT_MBR = Record(
        "gptmbr.bin", "syslinux GPT MBR（BIOS）",
        "GPT 磁盘上的 BIOS 引导（保护性 MBR，跳转到 EFI/BIOS 引导分区）", Kind.MBR, 440,
    )
    val ISOHDPFX = Record(
        "isohdpfx.bin", "isohybrid MBR",
        "让 ISO9660 镜像可以直接从 U 盘 BIOS 启动", Kind.HYBRID_MBR, 432,
    )
    val ISOLINUX = Record(
        "isolinux.bin", "ISOLINUX 引导镜像",
        "ISO 的 El Torito BIOS 引导条目（配合 -boot-info-table）", Kind.ISO_BIOS, 38912,
    )
    val LDLINUX = Record(
        "ldlinux.c32", "ISOLINUX 核心模块",
        "isolinux.bin 启动时必须位于 ISO 根目录", Kind.ISO_MODULE, 118676,
    )

    val ALL: List<Record> = listOf(MBR, GPT_MBR, ISOHDPFX, ISOLINUX, LDLINUX)

    /** 磁盘 MBR 类记录（可写进镜像的引导区）。 */
    val MBR_RECORDS: List<Record> = ALL.filter { it.installableInMbr }

    fun read(context: Context, asset: String): ByteArray? =
        runCatching { context.assets.open("$ASSET_DIR/$asset").use { it.readBytes() } }.getOrNull()

    fun size(context: Context, asset: String): Int = read(context, asset)?.size ?: 0

    fun available(context: Context, record: Record): Boolean = size(context, record.asset) > 0

    /** 把 asset 落盘到指定目录（给需要真实路径的工具，例如 xorriso -isohybrid-mbr）。 */
    fun materialise(context: Context, asset: String, dir: File): File? {
        val bytes = read(context, asset) ?: return null
        dir.mkdirs()
        val out = File(dir, asset)
        return runCatching { out.writeBytes(bytes); out }.getOrNull()
    }

    /** 按磁盘方案推荐默认记录。 */
    fun recommended(scheme: String): Record =
        if (scheme.equals("GPT", ignoreCase = true)) GPT_MBR else MBR

    /** 把内置引导记录写进镜像的 MBR（分区表与 0x55AA 签名不受影响）。 */
    fun install(
        context: Context,
        image: File,
        record: Record,
        setFirstActive: Boolean = true,
    ): PartitionOps.PartitionResult {
        if (!record.installableInMbr) {
            return PartitionOps.PartitionResult(false, "${record.title} 不能写入 MBR 扇区")
        }
        val blob = read(context, record.asset)
            ?: return PartitionOps.PartitionResult(false, "内置引导记录缺失：${record.asset}（请重新安装完整 APK）")
        return PartitionOps.writeBootCode(image, blob, setFirstActive)
    }

    /** 用户自备的引导记录。 */
    fun installBytes(image: File, blob: ByteArray, setFirstActive: Boolean = true): PartitionOps.PartitionResult =
        PartitionOps.writeBootCode(image, blob, setFirstActive)
}
