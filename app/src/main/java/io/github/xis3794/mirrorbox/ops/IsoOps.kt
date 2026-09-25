package io.github.xis3794.mirrorbox.ops

import android.content.Context
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