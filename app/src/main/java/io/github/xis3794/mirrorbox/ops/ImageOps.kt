package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.NativeTool
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.core.TaskItem
import io.github.xis3794.mirrorbox.core.TaskManager
import io.github.xis3794.mirrorbox.core.ToolRunner
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Everything MirrorBox can do with `qemu-img`, expressed as data the UI can render. */
object ImageOps {

    val OUTPUT_FORMATS = listOf(
        ImageFormat("qcow2", "qcow2（QEMU，推荐）"),
        ImageFormat("raw", "raw（原始磁盘）"),
        ImageFormat("vmdk", "vmdk（VMware）"),
        ImageFormat("vhdx", "vhdx（Hyper-V）"),
        ImageFormat("vdi", "vdi（VirtualBox）"),
        ImageFormat("vpc", "vpc（Virtual PC）"),
        ImageFormat("qcow", "qcow（旧版 QEMU）"),
        ImageFormat("qed", "qed（已弃用）"),
    )

    data class ImageFormat(val id: String, val label: String)

    data class ConvertOptions(
        val targetFormat: String = "qcow2",
        val compress: Boolean = false,
        val clusterSize: Long? = null,
        val preallocation: String? = null,
        val vmdkSubformat: String = "monolithicSparse",
        val vhdxSubformat: String = "dynamic",
        val rawSparse: Boolean = true,
    )

    data class ImageInfo(
        val format: String,
        val virtualSize: Long,
        val actualSize: Long,
        val clusterSize: Long?,
        val snapshotCount: Int,
        val backingFile: String?,
        val encrypted: Boolean,
        val rawJson: String,
    )

    fun parsePercent(line: String): Int? = TaskManager.parsePercent(line)

    // ------------------------------------------------------------------ quick queries

    suspend fun info(context: Context, file: File): ImageInfo? {
        val result = ToolRunner.run(context, NativeTools.QEMU_IMG, listOf("info", "--output=json", file.absolutePath))
        if (!result.success) return null
        return try {
            val json = JSONObject(result.output.substring(result.output.indexOf('{')))
            ImageInfo(
                format = json.optString("format", "?"),
                virtualSize = json.optLong("virtual-size", 0L),
                actualSize = json.optLong("actual-size", 0L),
                clusterSize = if (json.has("cluster-size")) json.optLong("cluster-size") else null,
                snapshotCount = json.optJSONArray("snapshots")?.length() ?: 0,
                backingFile = json.optString("backing-filename", "").ifBlank { null },
                encrypted = json.optBoolean("encrypted", false),
                rawJson = json.toString(2),
            )
        } catch (t: Throwable) {
            null
        }
    }

    data class MapEntry(val start: Long, val length: Long, val data: Boolean, val zero: Boolean, val depth: Int)

    suspend fun map(context: Context, file: File): List<MapEntry> {
        val result = ToolRunner.run(context, NativeTools.QEMU_IMG, listOf("map", "--output=json", file.absolutePath))
        if (!result.success) return emptyList()
        return try {
            val text = result.output.substring(result.output.indexOf('['))
            val array = org.json.JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                MapEntry(
                    start = o.optLong("start"),
                    length = o.optLong("length"),
                    data = o.optBoolean("data"),
                    zero = o.optBoolean("zero"),
                    depth = o.optInt("depth"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    suspend fun snapshotList(context: Context, file: File): List<String> {
        if (!Prefs.autoCheckAfterWrite) { /* keep lint calm: prefs used elsewhere */ }
        val result = ToolRunner.run(context, NativeTools.QEMU_IMG, listOf("snapshot", "-l", file.absolutePath))
        return result.lines.filter { it.isNotBlank() }
    }

    suspend fun check(context: Context, file: File, repair: Boolean = false): ToolResultSnapshot {
        val args = mutableListOf("check")
        if (repair) {
            args.add("-r")
            args.add("all")
        }
        args.add(file.absolutePath)
        val result = ToolRunner.run(context, NativeTools.QEMU_IMG, args)
        return ToolResultSnapshot(result.exitCode, result.lines)
    }

    data class ToolResultSnapshot(val exitCode: Int, val lines: List<String>) {
        val success: Boolean get() = exitCode == 0
        val summary: String
            get() = lines.lastOrNull { it.contains("errors") || it.contains("No errors") || it.contains("corrupt") }
                ?: lines.lastOrNull().orEmpty()
    }

    // ------------------------------------------------------------------ argument builders

    fun buildCreateArgs(file: File, sizeBytes: Long, clusterSize: Long?, preallocation: String?, lazyRefcounts: Boolean = true): List<String> {
        val args = mutableListOf("create", "-f", "qcow2")
        val options = mutableListOf<String>()
        clusterSize?.let { options.add("cluster_size=$it") }
        preallocation?.takeIf { it.isNotBlank() && it != "off" }?.let { options.add("preallocation=$it") }
        if (lazyRefcounts) options.add("lazy_refcounts=on")
        if (options.isNotEmpty()) {
            args.add("-o")
            args.add(options.joinToString(","))
        }
        args.add(file.absolutePath)
        args.add(sizeBytes.toString())
        return args
    }

    fun buildConvertArgs(source: File, target: File, options: ConvertOptions): List<String> {
        val args = mutableListOf("convert", "-p")
        if (options.compress) args.add("-c")
        args.add("-O")
        args.add(options.targetFormat)
        val sub = mutableListOf<String>()
        when (options.targetFormat) {
            "qcow2" -> {
                options.clusterSize?.let { sub.add("cluster_size=$it") }
                options.preallocation?.takeIf { it.isNotBlank() && it != "off" }?.let { sub.add("preallocation=$it") }
                if (options.compress) sub.add("compression_type=zlib")
            }
            "vmdk" -> sub.add("subformat=${options.vmdkSubformat}")
            "vhdx" -> sub.add("subformat=${options.vhdxSubformat}")
        }
        if (sub.isNotEmpty()) {
            args.add("-o")
            args.add(sub.joinToString(","))
        }
        if (options.targetFormat == "raw" && options.rawSparse) {
            args.add("-S")
            args.add("4096")
        }
        args.add(source.absolutePath)
        args.add(target.absolutePath)
        return args
    }

    fun buildResizeArgs(file: File, newSizeBytes: Long, shrink: Boolean): List<String> =
        listOf("resize", file.absolutePath, (if (shrink) "" else "+") + newSizeBytes.toString())

    /** Adds `+size` to the current virtual size. */
    fun buildGrowArgs(file: File, deltaBytes: Long): List<String> =
        listOf("resize", file.absolutePath, "+$deltaBytes")

    fun buildSnapshotArgs(action: String, name: String?, file: File): List<String> = when (action) {
        "create" -> listOf("snapshot", "-c", name ?: "snap", file.absolutePath)
        "delete" -> listOf("snapshot", "-d", name ?: "", file.absolutePath)
        "apply" -> listOf("snapshot", "-a", name ?: "", file.absolutePath)
        else -> listOf("snapshot", "-l", file.absolutePath)
    }

    fun buildCompressArgs(source: File, target: File): List<String> =
        listOf("convert", "-p", "-c", "-O", "qcow2", source.absolutePath, target.absolutePath)

    fun buildExportRawArgs(source: File, target: File): List<String> =
        listOf("convert", "-p", "-O", "raw", "-S", "4096", source.absolutePath, target.absolutePath)

    fun buildAmendArgs(file: File, options: Map<String, String>): List<String> {
        val args = mutableListOf("amend")
        if (options.isNotEmpty()) {
            args.add("-o")
            args.add(options.entries.joinToString(",") { "${it.key}=${it.value}" })
        }
        args.add(file.absolutePath)
        return args
    }

    // ------------------------------------------------------------------ task submission

    fun submitCommand(
        title: String,
        detail: String,
        tool: NativeTool = NativeTools.QEMU_IMG,
        args: List<String>,
        cwd: File? = null,
        onFinish: ((TaskItem) -> Unit)? = null,
    ): Long = TaskManager.submit(title, detail, tool, args, cwd, ImageOps::parsePercent, onFinish)

    fun submitCreate(file: File, sizeBytes: Long, clusterSize: Long?, preallocation: String?, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand(
            "创建镜像",
            "${file.name} · ${humanSize(sizeBytes)}",
            args = buildCreateArgs(file, sizeBytes, clusterSize, preallocation),
            onFinish = onFinish,
        )

    fun submitConvert(source: File, target: File, options: ConvertOptions, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand(
            "格式转换",
            "${source.name} → ${target.name} (${options.targetFormat})",
            args = buildConvertArgs(source, target, options),
            onFinish = onFinish,
        )

    fun submitCheck(file: File, repair: Boolean, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand(
            if (repair) "检查并修复" else "一致性检查",
            file.name,
            args = if (repair) listOf("check", "-r", "all", file.absolutePath) else listOf("check", file.absolutePath),
            onFinish = onFinish,
        )

    fun submitResize(file: File, deltaBytes: Long, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand("调整容量", "${file.name} +${humanSize(deltaBytes)}", args = buildGrowArgs(file, deltaBytes), onFinish = onFinish)

    fun submitSnapshot(action: String, name: String?, file: File, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand(
            when (action) {
                "create" -> "创建快照"
                "delete" -> "删除快照"
                "apply" -> "回滚到快照"
                else -> "快照列表"
            },
            "${file.name}${if (name != null) " · $name" else ""}",
            args = buildSnapshotArgs(action, name, file),
            onFinish = onFinish,
        )

    fun submitCompress(file: File, target: File, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand("压缩瘦身", "${file.name} → ${target.name}", args = buildCompressArgs(file, target), onFinish = onFinish)

    fun submitExportRaw(file: File, target: File, onFinish: ((TaskItem) -> Unit)? = null): Long =
        submitCommand("导出 raw", "${file.name} → ${target.name}", args = buildExportRawArgs(file, target), onFinish = onFinish)

    fun humanSize(bytes: Long): String = String.format(Locale.US, "%.2f GB", bytes.toDouble() / 1024 / 1024 / 1024)
}