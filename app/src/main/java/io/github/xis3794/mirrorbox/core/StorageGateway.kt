package io.github.xis3794.mirrorbox.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Workspace scanning plus SAF import / export bridges. */
object StorageGateway {

    private val IMAGE_EXTENSIONS = listOf(
        ".qcow2", ".qcow", ".img", ".raw", ".vmdk", ".vhdx", ".vhd", ".vdi", ".vpc", ".qed", ".iso",
    )

    data class ImageFile(
        val file: File,
        val size: Long,
        val modifiedAt: Long,
        val extension: String,
    ) {
        val name: String get() = file.name
        val prettySize: String get() = Fmt.size(size)
        val prettyDate: String get() = Fmt.dateTime(modifiedAt)
    }

    fun isImageName(name: String): Boolean =
        IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    fun listImages(): List<ImageFile> {
        val out = ArrayList<ImageFile>()
        for (root in listOf(AppPaths.images, AppPaths.iso, AppPaths.work, AppPaths.externalRoot(), AppPaths.imports())) {
            collect(root, out, 0)
        }
        return out.distinctBy { it.file.absolutePath }.sortedByDescending { it.modifiedAt }
    }

    private fun collect(dir: File, out: MutableList<ImageFile>, depth: Int) {
        if (depth > 2) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isDirectory) {
                collect(f, out, depth + 1)
            } else if (isImageName(f.name)) {
                out.add(ImageFile(f, f.length(), f.lastModified(), f.name.substringAfterLast('.', "").uppercase()))
            }
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun querySize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Copies a SAF picked file into [targetDir]; returns the created file. */
    fun importFromUri(
        context: Context,
        uri: Uri,
        targetDir: File,
        onProgress: ((Long) -> Unit)? = null,
    ): File? {
        val displayName = queryDisplayName(context, uri) ?: "imported.img"
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "img")
        val target = AppPaths.uniqueFile(targetDir, base, ".$ext")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return try {
            input.use { source ->
                FileOutputStream(target).use { sink ->
                    copyStream(source, sink, onProgress)
                }
            }
            target
        } catch (t: Throwable) {
            target.delete()
            null
        }
    }

    /** Copies one of our files to a SAF destination uri. */
    fun exportToUri(context: Context, file: File, uri: Uri, onProgress: ((Long) -> Unit)? = null): Boolean {
        val output = context.contentResolver.openOutputStream(uri) ?: return false
        return try {
            file.inputStream().use { source ->
                output.use { sink -> copyStream(source, sink, onProgress) }
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun copyStream(source: InputStream, sink: OutputStream, onProgress: ((Long) -> Unit)? = null): Long {
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            val n = source.read(buf)
            if (n <= 0) break
            sink.write(buf, 0, n)
            total += n
            onProgress?.invoke(total)
        }
        sink.flush()
        return total
    }

    fun delete(file: File): Boolean = file.delete()

    /**
     * 递归把一个 SAF 目录树（`OpenDocumentTree` 返回的 tree uri）拷贝进 [destDir]。
     *
     * 用于「ISO 源目录」：xorriso 只能读真实路径，所以选中的文件夹要先落到应用目录里。
     * @return 拷贝的文件数 to 字节数；失败返回 null
     */
    fun importTreeFromUri(context: Context, treeUri: Uri, destDir: File): Pair<Int, Long>? = runCatching {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri) ?: return null
        destDir.mkdirs()
        var files = 0
        var bytes = 0L

        fun copyInto(dir: androidx.documentfile.provider.DocumentFile, target: File) {
            target.mkdirs()
            for (child in dir.listFiles()) {
                val name = child.name ?: continue
                if (name.startsWith(".")) continue
                val out = File(target, name)
                if (child.isDirectory) {
                    copyInto(child, out)
                } else {
                    val stream = context.contentResolver.openInputStream(child.uri) ?: continue
                    stream.use { input ->
                        out.outputStream().use { sink -> bytes += copyStream(input, sink) }
                    }
                    files++
                }
            }
        }

        copyInto(root, destDir)
        files to bytes
    }.getOrNull()

    fun rename(file: File, newName: String): File? {
        val target = File(file.parentFile, newName)
        return if (file.renameTo(target)) target else null
    }

    /**
     * 工作区占用与可用空间。
     *
     * 注意：这里用**带上限**的统计（`AppPaths.workspaceUsage`）—— 释放 WIM 之后暂存目录可能有
     * 几十万个文件，无上限遍历会让首页一直卡在"正在检测"。
     */
    fun diskUsage(): Pair<Long, Long> {
        val (main, staging, _) = AppPaths.workspaceUsage()
        val stat = android.os.StatFs(AppPaths.root.absolutePath)
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return (main + staging) to free
    }
}