package io.github.xis3794.mirrorbox.core

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ToolCategory { QEMU, FILESYSTEM, ISO, OTHER }

data class NativeTool(
    val id: String,
    val displayName: String,
    val packagedName: String,
    val category: ToolCategory,
    val description: String,
    val versionFlag: List<String> = listOf("--version"),
)

/**
 * Resolves the native tools that are bundled inside the APK as `jniLibs/lib*.so` and executed from
 * `nativeLibraryDir` (the only writable-free, executable location available to modern target SDKs).
 *
 * An external directory can be configured in Settings (e.g. tools installed by Termux) as a fallback.
 */
object NativeTools {

    val QEMU_IMG = NativeTool(
        "qemu-img", "qemu-img", "libqemu-img.so", ToolCategory.QEMU,
        "创建 / 转换 / 检查 / 快照 / 调整大小 / 映射",
    )
    val QEMU_IO = NativeTool(
        "qemu-io", "qemu-io", "libqemu-io.so", ToolCategory.QEMU,
        "扇区级读写与检查（高级）",
    )

    val MKE2FS = NativeTool("mke2fs", "mke2fs", "libmke2fs.so", ToolCategory.FILESYSTEM, "创建 ext2/3/4", listOf("-V"))
    val E2FSCK = NativeTool("e2fsck", "e2fsck", "libe2fsck.so", ToolCategory.FILESYSTEM, "检查/修复 ext 文件系统", listOf("-V"))
    val DEBUGFS = NativeTool("debugfs", "debugfs", "libdebugfs.so", ToolCategory.FILESYSTEM, "ext 文件浏览与写入", listOf("-V"))
    val DUMPE2FS = NativeTool("dumpe2fs", "dumpe2fs", "libdumpe2fs.so", ToolCategory.FILESYSTEM, "ext 超级块信息", listOf("-V"))
    val RESIZE2FS = NativeTool("resize2fs", "resize2fs", "libresize2fs.so", ToolCategory.FILESYSTEM, "ext 文件系统扩容", listOf("-V"))
    val TUNE2FS = NativeTool("tune2fs", "tune2fs", "libtune2fs.so", ToolCategory.FILESYSTEM, "ext 参数调整", listOf("-V"))

    val MKFS_FAT = NativeTool("mkfs.fat", "mkfs.fat", "libmkfs-fat.so", ToolCategory.FILESYSTEM, "创建 FAT16/32", listOf("--help"))
    val FSCK_FAT = NativeTool("fsck.fat", "fsck.fat", "libfsck-fat.so", ToolCategory.FILESYSTEM, "检查/修复 FAT", listOf("--help"))
    val MCOPY = NativeTool("mcopy", "mcopy", "libmcopy.so", ToolCategory.FILESYSTEM, "复制文件到 FAT 镜像", listOf("-V"))
    val MDIR = NativeTool("mdir", "mdir", "libmdir.so", ToolCategory.FILESYSTEM, "列出 FAT 内容", listOf("-V"))
    val MDEL = NativeTool("mdel", "mdel", "libmdel.so", ToolCategory.FILESYSTEM, "删除 FAT 文件", listOf("-V"))
    val MMD = NativeTool("mmd", "mmd", "libmmd.so", ToolCategory.FILESYSTEM, "创建 FAT 目录", listOf("-V"))

    val MKNTFS = NativeTool("mkntfs", "mkntfs", "libmkntfs.so", ToolCategory.FILESYSTEM, "创建 NTFS", listOf("--version"))
    val NTFSLS = NativeTool("ntfsls", "ntfsls", "libntfsls.so", ToolCategory.FILESYSTEM, "列出 NTFS 内容", listOf("--version"))
    val NTFSCAT = NativeTool("ntfscat", "ntfscat", "libntfscat.so", ToolCategory.FILESYSTEM, "读取 NTFS 文件", listOf("--version"))
    val NTFSCP = NativeTool("ntfscp", "ntfscp", "libntfscp.so", ToolCategory.FILESYSTEM, "写入 NTFS 文件", listOf("--version"))
    val NTFSFIX = NativeTool("ntfsfix", "ntfsfix", "libntfsfix.so", ToolCategory.FILESYSTEM, "NTFS 一致性修复", listOf("--version"))

    val XORRISO = NativeTool("xorriso", "xorriso", "libxorriso.so", ToolCategory.ISO, "ISO 制作 / 提取 / 编辑", listOf("-version"))

    /**
     * WIM engine for the DISM++-style "释放 WIM" flow: `info` (list images), `dir`, `extract` and
     * `apply` (unpack an image into a directory without mounting anything).
     */
    val WIMLIB = NativeTool("wimlib-imagex", "wimlib-imagex", "libwimlib-imagex.so", ToolCategory.OTHER, "WIM 释放 / 提取 / 捕获", listOf("--version"))

    val ALL: List<NativeTool> = listOf(
        QEMU_IMG, QEMU_IO,
        MKE2FS, E2FSCK, DEBUGFS, DUMPE2FS, RESIZE2FS, TUNE2FS,
        MKFS_FAT, FSCK_FAT, MCOPY, MDIR, MDEL, MMD,
        MKNTFS, NTFSLS, NTFSCAT, NTFSCP, NTFSFIX,
        XORRISO,
        WIMLIB,
    )

    private val cache = ConcurrentHashMap<String, File?>()

    fun nativeLibraryDir(context: Context): File? =
        context.applicationInfo?.nativeLibraryDir?.let { File(it) }

    fun resolve(context: Context, tool: NativeTool): File? {
        cache[tool.id]?.let { return it }
        val candidates = ArrayList<File>(6)
        nativeLibraryDir(context)?.let {
            candidates.add(File(it, tool.packagedName))
            candidates.add(File(it, tool.id))
        }
        Prefs.externalToolDir?.let { dir ->
            val d = File(dir)
            candidates.add(File(d, tool.id))
            candidates.add(File(d, tool.packagedName))
        }
        val bundledDir = File(AppPaths.root, "tools")
        candidates.add(File(bundledDir, tool.id))
        candidates.add(File(bundledDir, tool.packagedName))

        for (candidate in candidates) {
            if (candidate.isFile && candidate.canExecute()) {
                cache[tool.id] = candidate
                return candidate
            }
        }
        return null
    }

    fun isAvailable(context: Context, tool: NativeTool): Boolean = resolve(context, tool) != null

    fun availableCount(context: Context): Int = ALL.count { isAvailable(context, it) }

    fun clearCache() {
        cache.clear()
    }
}