package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.TaskItem
import io.github.xis3794.mirrorbox.core.TaskManager
import io.github.xis3794.mirrorbox.core.ToolRunner
import java.io.File

/** ISO authoring / extraction / editing built on xorriso (mkisofs emulation mode). */
object IsoOps {

    data class IsoOptions(
        val volumeLabel: String = "MIRRORBOX",
        val joliet: Boolean = true,
        val rockRidge: Boolean = true,
        val bootImage: File? = null,
        val efiBootImage: File? = null,
        val hybrid: Boolean = true,
        val bootLoadSize: Int = 4,
        /** isohybrid 用的 MBR（内置 assets/boot/isohdpfx.bin），让 ISO 能从 U 盘以 BIOS 方式启动。 */
        val hybridMbr: File? = null,
    )

    fun buildCreateArgs(sourceDir: File, output: File, options: IsoOptions): List<String> {
        val args = mutableListOf("-as", "mkisofs", "-o", output.absolutePath)
        if (options.joliet) {
            args.add("-J")
            args.add("-joliet-long")
        }
        if (options.rockRidge) args.add("-R")
        if (options.volumeLabel.isNotBlank()) {
            args.add("-V")
            args.add(options.volumeLabel.take(32))
        }
        // -isohybrid-mbr 让光盘镜像同时也是一块可启动的 U 盘（BIOS）。必须出现在 -b 之前。
        options.hybridMbr?.let { mbr ->
            args.add("-isohybrid-mbr")
            args.add(mbr.absolutePath)
        }
        options.bootImage?.let { boot ->
            args.add("-b")
            args.add(boot.name)
            args.add("-c")
            args.add("boot.catalog")
            args.add("-no-emul-boot")
            args.add("-boot-load-size")
            args.add(options.bootLoadSize.toString())
            args.add("-boot-info-table")
        }
        options.efiBootImage?.let { efi ->
            args.add("-eltorito-alt-boot")
            args.add("-e")
            args.add(efi.name)
            args.add("-no-emul-boot")
        }
        if (options.hybrid && options.efiBootImage != null) {
            args.add("-isohybrid-gpt-basdat")
        }
        args.add(sourceDir.absolutePath)
        return args
    }

    fun buildListArgs(iso: File): List<String> = listOf("-indev", iso.absolutePath, "-ls", "/")

    /**
     * 把内置的 ISOLINUX（BIOS 引导）装进源目录：复制 isolinux.bin + ldlinux.c32，并在缺少
     * isolinux.cfg 时生成一份（若目录里已有内核文件就自动填好 KERNEL/APPEND）。
     *
     * @return 供 UI 显示的结果说明
     */
    fun installBuiltInBiosBoot(context: Context, sourceDir: File): String {
        sourceDir.mkdirs()
        val copied = ArrayList<String>()
        listOf(BootRecords.ISOLINUX, BootRecords.LDLINUX).forEach { record ->
            val file = BootRecords.materialise(context, record.asset, sourceDir)
            if (file != null) copied.add(record.asset)
        }
        if (copied.isEmpty()) return "内置引导文件缺失（请重新安装完整 APK）"

        val config = File(sourceDir, "isolinux.cfg")
        var configNote = "已存在 isolinux.cfg，未改动"
        if (!config.exists()) {
            val kernel = listOf("vmlinuz", "bzImage", "kernel", "kernel.img")
                .map { File(sourceDir, it) }.firstOrNull { it.isFile }
            val initrd = listOf("initrd.img", "initrd", "initramfs.img")
                .map { File(sourceDir, it) }.firstOrNull { it.isFile }
            val text = buildString {
                appendLine("DEFAULT boot")
                appendLine("PROMPT 0")
                appendLine("TIMEOUT 100")
                appendLine("LABEL boot")
                if (kernel != null) {
                    appendLine("  KERNEL /${kernel.name}")
                    if (initrd != null) appendLine("  APPEND initrd=/${initrd.name}")
                } else {
                    appendLine("  # 源目录里没有找到内核，请把下面两行改成你的内核/initrd")
                    appendLine("  KERNEL /vmlinuz")
                    appendLine("  APPEND initrd=/initrd.img")
                }
            }
            config.writeText(text)
            configNote = if (kernel != null) "已生成 isolinux.cfg（内核 ${kernel.name}）" else "已生成 isolinux.cfg（需要你填入内核）"
        }
        return "已复制 ${copied.joinToString("、")}；$configNote"
    }

    /** 把内置的 isohybrid MBR 落盘到工作目录，供 [IsoOptions.hybridMbr] 使用。 */
    fun builtInHybridMbr(context: Context): File? =
        BootRecords.materialise(context, BootRecords.ISOHDPFX.asset, AppPaths.work)

    fun buildExtractArgs(iso: File, outDir: File): List<String> =
        listOf("-osirrox", "on", "-indev", iso.absolutePath, "-extract", "/", outDir.absolutePath)

    /** Rewrites [iso] into [target] while replacing/adding mapped files. */
    fun buildEditArgs(iso: File, target: File, mappings: List<Pair<File, String>>, removals: List<String>): List<String> {
        val args = mutableListOf("-indev", iso.absolutePath, "-outdev", target.absolutePath)
        for ((source, isoPath) in mappings) {
            args.add("-map")
            args.add(source.absolutePath)
            args.add(isoPath)
        }
        for (path in removals) {
            args.add("-rm")
            args.add(path)
        }
        return args
    }

    suspend fun list(context: Context, iso: File): List<String> {
        val result = ToolRunner.run(context, NativeTools.XORRISO, buildListArgs(iso))
        return result.lines.filter { it.isNotBlank() }
    }

    fun submitCreate(sourceDir: File, output: File, options: IsoOptions, onFinish: ((TaskItem) -> Unit)? = null): Long =
        TaskManager.submit(
            "制作 ISO",
            "${output.name} ← ${sourceDir.name}",
            NativeTools.XORRISO,
            buildCreateArgs(sourceDir, output, options),
            sourceDir.parentFile,
            ImageOps::parsePercent,
            onFinish,
        )

    fun submitExtract(iso: File, outDir: File, onFinish: ((TaskItem) -> Unit)? = null): Long =
        TaskManager.submit(
            "提取 ISO",
            "${iso.name} → ${outDir.name}",
            NativeTools.XORRISO,
            buildExtractArgs(iso, outDir),
            outDir.parentFile,
            ImageOps::parsePercent,
            onFinish,
        )

    fun submitEdit(iso: File, target: File, mappings: List<Pair<File, String>>, removals: List<String>, onFinish: ((TaskItem) -> Unit)? = null): Long =
        TaskManager.submit(
            "编辑 ISO",
            "${iso.name} → ${target.name}",
            NativeTools.XORRISO,
            buildEditArgs(iso, target, mappings, removals),
            iso.parentFile,
            ImageOps::parsePercent,
            onFinish,
        )
}