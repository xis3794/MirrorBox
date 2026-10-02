package io.github.xis3794.mirrorbox.ops

import android.content.Context
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry
import java.io.File

/**
 * 「客户机文件浏览」：不挂载、不需要 root 地读写分区里的文件系统。
 *
 * 会话模型（[Session]）：
 *  1. `open()` 把分区提取成一个临时 raw 文件（[EditOps.extractRange]）
 *  2. 之后用 e2fsprogs / mtools / ntfsprogs 在这个 raw 上列目录、复制、删除、建目录
 *  3. `commit()` 只把变化的簇差分写回 qcow2（[EditOps.writeBackRange]）
 *
 * 代价：临时 raw 与分区一样大，所以打开前必须做空间预检。
 */
object GuestFsOps {

    data class Entry(
        val name: String,
        val isDir: Boolean,
        val sizeBytes: Long,
        val detail: String,
    ) {
        val display: String get() = if (isDir) "$name/" else name
    }

    /** 根据分区类型猜文件系统（用户仍可在界面上改）。 */
    fun guessKind(entry: PartitionEntry): EditOps.FsKind {
        val type = entry.typeId.lowercase()
        val name = entry.typeName.lowercase()
        return when {
            name.contains("fat") || name.contains("efi") -> EditOps.FsKind.FAT32
            name.contains("ntfs") || name.contains("exfat") -> EditOps.FsKind.NTFS
            type.contains("0x0b") || type.contains("0x0c") || type.contains("0x01") ||
                type.contains("0x04") || type.contains("0x06") || type.contains("0x0e") -> EditOps.FsKind.FAT32
            type.contains("0x07") -> EditOps.FsKind.NTFS
            else -> EditOps.FsKind.EXT4
        }
    }

    /** 一个分区的浏览会话。 */
    class Session(
        private val context: Context,
        private val image: File,
        val entry: PartitionEntry,
        val kind: EditOps.FsKind,
        private val raw: File,
    ) {
        val rawPath: String get() = raw.absolutePath
        val sizeText: String get() = Fmt.size(entry.sizeBytes)
        private var opened = false

        fun isOpen(): Boolean = opened && raw.isFile

        suspend fun open(onProgress: ((Long) -> Unit)? = null, onLog: (String) -> Unit = {}): String? {
            raw.parentFile?.mkdirs()
            onLog("提取分区 ${entry.index}（$sizeText）→ ${raw.name}")
            if (!EditOps.extractRange(image, entry.startByte, entry.sizeBytes, raw, onProgress)) {
                return "无法提取分区 ${entry.index}"
            }
            opened = true
            return null
        }

        /** 把改动写回镜像（没有打开过则返回 null 表示无事可做）。 */
        suspend fun commit(onProgress: ((Long) -> Unit)? = null, onLog: (String) -> Unit = {}): EditOps.WriteBackResult? {
            if (!isOpen()) return null
            onLog("差分回写 ${raw.name} → 镜像")
            return EditOps.writeBackRange(image, entry.startByte, entry.sizeBytes, raw, onProgress)
        }

        fun discard() {
            opened = false
            raw.delete()
        }

        // ---------------------------------------------------------------- listing

        suspend fun list(path: String, onLine: (String) -> Unit = {}): List<Entry> = when (kind) {
            EditOps.FsKind.FAT32 -> listFat(path, onLine)
            EditOps.FsKind.EXT4 -> listExt(path, onLine)
            EditOps.FsKind.NTFS -> listNtfs(path, onLine)
        }

        private suspend fun listFat(path: String, onLine: (String) -> Unit): List<Entry> {
            val mount = "::" + normalise(path)
            val result = ToolRunner.run(context, NativeTools.MDIR, listOf("-i", rawPath, "-b", mount), onLine = onLine)
            if (!result.success && result.lines.isEmpty()) return emptyList()
            val base = mount.trimEnd('/')
            val out = ArrayList<Entry>()
            for (line in result.lines) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("::")) continue
                val isDir = trimmed.endsWith("/")
                val relative = trimmed.removePrefix("::").trimEnd('/')
                val parent = base.removePrefix("::").trimEnd('/')
                if (relative == parent) continue
                val suffix = relative.removePrefix(parent).trimStart('/')
                if (suffix.isEmpty() || suffix.contains('/')) continue // 只看直接子项
                out.add(Entry(suffix, isDir, 0L, if (isDir) "目录" else "文件"))
            }
            return out.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        }

        private suspend fun listExt(path: String, onLine: (String) -> Unit): List<Entry> {
            val result = ToolRunner.run(
                context, NativeTools.DEBUGFS,
                listOf("-R", "ls -l ${normalise(path)}", rawPath),
                onLine = onLine,
            )
            val out = ArrayList<Entry>()
            for (line in result.lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("debugfs")) continue
                val name = trimmed.substringAfterLast(' ')
                if (name == "." || name == ".." || name.startsWith("(")) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size < 8) continue
                val mode = parts[1].toIntOrNull(8) ?: continue
                val size = parts[5].toLongOrNull() ?: 0L
                val isDir = (mode and 0xF000) == 0x4000
                out.add(Entry(name, isDir, if (isDir) 0L else size, if (isDir) "目录" else Fmt.size(size)))
            }
            return out.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        }

        private suspend fun listNtfs(path: String, onLine: (String) -> Unit): List<Entry> =
            GuestFsOps.listNtfsRaw(context, rawPath, normalise(path), onLine)

        // ---------------------------------------------------------------- mutations

        suspend fun makeDirectory(path: String, onLine: (String) -> Unit = {}): String? = when (kind) {
            EditOps.FsKind.FAT32 -> {
                val r = ToolRunner.run(context, NativeTools.MMD, listOf("-i", rawPath, "::" + normalise(path)), onLine = onLine)
                if (r.success) null else r.lines.lastOrNull() ?: "mmd 失败"
            }
            EditOps.FsKind.EXT4 -> {
                val r = ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-w", "-R", "mkdir ${normalise(path)}", rawPath), onLine = onLine)
                if (r.lines.any { it.contains("ext2fs_mkdir", ignoreCase = true) || it.contains("already exists", ignoreCase = true) }) {
                    "目录可能已存在：${r.lines.lastOrNull().orEmpty()}"
                } else {
                    null
                }
            }
            EditOps.FsKind.NTFS -> "NTFS 暂不支持新建目录（未内置 ntfsmkdir）"
        }

        suspend fun delete(path: String, onLine: (String) -> Unit = {}): String? = when (kind) {
            EditOps.FsKind.FAT32 -> {
                val r = ToolRunner.run(context, NativeTools.MDEL, listOf("-i", rawPath, "::" + normalise(path)), onLine = onLine)
                if (r.success) null else r.lines.lastOrNull() ?: "mdel 失败"
            }
            EditOps.FsKind.EXT4 -> {
                ToolRunner.run(context, NativeTools.DEBUGFS, listOf("-w", "-R", "rm ${normalise(path)}", rawPath), onLine = onLine)
                null
            }
            EditOps.FsKind.NTFS -> "NTFS 暂不支持删除（未内置 ntfsrm）"
        }

        /** 把客户机里的文件复制到宿主目录。 */
        suspend fun copyOut(guestPath: String, destDir: File, onLine: (String) -> Unit = {}): String? {
            destDir.mkdirs()
            val guest = normalise(guestPath)
            val target = File(destDir, File(guest).name)
            return when (kind) {
                EditOps.FsKind.FAT32 -> {
                    val r = ToolRunner.run(
                        context, NativeTools.MCOPY,
                        listOf("-s", "-i", rawPath, "::" + guest, target.absolutePath), onLine = onLine,
                    )
                    if (r.success && target.exists()) null else r.lines.lastOrNull() ?: "mcopy 导出失败"
                }
                EditOps.FsKind.EXT4 -> {
                    val r = ToolRunner.run(
                        context, NativeTools.DEBUGFS,
                        listOf("-R", "dump $guest ${target.absolutePath}", rawPath), onLine = onLine,
                    )
                    if (target.exists()) null else r.lines.lastOrNull() ?: "debugfs dump 失败"
                }
                EditOps.FsKind.NTFS -> {
                    // ntfscat 把文件写到 stdout；文本管道会破坏二进制，所以直接把它接到目标文件。
                    val tool = NativeTools.resolve(context, NativeTools.NTFSCAT)
                        ?: return "未找到 ntfscat"
                    val ok = runToFile(tool, listOf(rawPath, guest), target)
                    if (ok && target.exists()) null else "ntfscat 导出失败"
                }
            }
        }

        /** 把宿主文件复制进客户机目录。 */
        suspend fun copyIn(hostFile: File, guestDir: String, onLine: (String) -> Unit = {}): String? {
            if (!hostFile.isFile) return "源文件不存在：${hostFile.absolutePath}"
            val guest = normalise(guestDir)
            return when (kind) {
                EditOps.FsKind.FAT32 -> {
                    val r = ToolRunner.run(
                        context, NativeTools.MCOPY,
                        listOf("-i", rawPath, hostFile.absolutePath, "::" + guest), onLine = onLine,
                    )
                    if (r.success) null else r.lines.lastOrNull() ?: "mcopy 写入失败"
                }
                EditOps.FsKind.EXT4 -> {
                    val r = ToolRunner.run(
                        context, NativeTools.DEBUGFS,
                        listOf("-w", "-R", "write ${hostFile.absolutePath} $guest/${hostFile.name}", rawPath), onLine = onLine,
                    )
                    if (r.lines.any { it.contains("error", ignoreCase = true) }) {
                        r.lines.lastOrNull()
                    } else {
                        null
                    }
                }
                EditOps.FsKind.NTFS -> {
                    val r = ToolRunner.run(
                        context, NativeTools.NTFSCP,
                        listOf(rawPath, hostFile.absolutePath, "$guest/${hostFile.name}"), onLine = onLine,
                    )
                    if (r.success) null else r.lines.lastOrNull() ?: "ntfscp 写入失败"
                }
            }
        }

        private fun runToFile(executable: File, args: List<String>, out: File): Boolean = runCatching {
            val pb = ProcessBuilder(listOf(executable.absolutePath) + args)
            pb.redirectErrorStream(false)
            pb.redirectOutput(ProcessBuilder.Redirect.to(out))
            pb.environment().putAll(ToolRunner.environment(context))
            val process = pb.start()
            val code = process.waitFor()
            code == 0
        }.getOrDefault(false)
    }

    /** 宿主同步目录（导出文件的默认位置，应用专属外部目录，无需权限）。 */
    fun exportDir(): File = File(io.github.xis3794.mirrorbox.core.AppPaths.externalRoot(), "extracted").apply { mkdirs() }

    /**
     * 文件管理器直接能看到的导出目录（`/sdcard/Download/MirrorBox`）。
     *
     * 只有拿到「所有文件访问」权限时才可用，否则返回 null —— Android 11+ 起
     * `Android/data/<包名>` 对其他应用不可见，导出到这里的文件用户很难找到。
     */
    fun publicExportDir(): File? {
        if (!io.github.xis3794.mirrorbox.core.AppPaths.hasAllFilesAccess()) return null
        val dir = File("/sdcard/Download/MirrorBox")
        if (!dir.exists() && !dir.mkdirs()) return null
        return if (dir.canWrite()) dir else null
    }

    fun newRawFile(entry: PartitionEntry): File = File(
        io.github.xis3794.mirrorbox.core.AppPaths.tmp,
        "guest-p${entry.index}-${System.currentTimeMillis()}.raw",
    )

    fun normalise(path: String): String {
        val trimmed = path.trim().ifBlank { "/" }
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return withSlash.replace(Regex("/+"), "/").ifEmpty { "/" }
    }

    fun childPath(parent: String, name: String): String {
        val base = normalise(parent).trimEnd('/')
        return if (base.isEmpty()) "/$name" else "$base/$name"
    }

    fun parentPath(path: String): String {
        val normalised = normalise(path).trimEnd('/')
        if (normalised.isEmpty() || normalised == "/") return "/"
        val cut = normalised.substringBeforeLast('/', "")
        return cut.ifEmpty { "/" }
    }

    // ---------------------------------------------------------------- NTFS 静态读取 / 体检

    /**
     * `ntfsls -F -l -p <dir> <raw>` 的一行长这样（大小右对齐 9 列，日期 4 段，最后是名字）：
     *
     * `       0 Oct  2 18:32 2026 $Extend/`
     *
     * 目录靠 `-F` 在名字末尾追加 `/` 来区分。旧版本按 `ls -l`（首列是模式字符）解析 NTFS，
     * 于是每一行都被跳过 —— 界面上就显示"目录为空，或读取失败"。这就是"文件读不到"。
     */
    private val NTFS_LONG = Regex("^\\s*(\\d+)\\s+\\S+\\s+\\d+\\s+\\S+\\s+\\d+\\s+(.+?)\\s*$")

    fun parseNtfsLong(line: String): Entry? {
        val m = NTFS_LONG.matchEntire(line.trimEnd()) ?: return null
        var name = m.groupValues[2].trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        val isDir = name.endsWith("/")
        if (isDir) name = name.dropLast(1).trim()
        if (name.isEmpty()) return null
        val size = m.groupValues[1].toLongOrNull() ?: 0L
        return Entry(name, isDir, if (isDir) 0L else size, if (isDir) "目录" else Fmt.size(size))
    }

    /** 对任意 NTFS raw（镜像提取出来的分区 / 释放时的临时文件）列目录。 */
    suspend fun listNtfsRaw(
        context: Context,
        rawPath: String,
        path: String,
        onLine: (String) -> Unit = {},
    ): List<Entry> {
        val result = ToolRunner.run(
            context, NativeTools.NTFSLS,
            listOf("-F", "-l", "-p", normalise(path), rawPath),
            onLine = onLine,
        )
        val out = ArrayList<Entry>()
        for (line in result.lines) parseNtfsLong(line)?.let { out.add(it) }
        return out.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    data class AuditReport(val ok: Boolean, val lines: List<String>) {
        val text: String get() = lines.joinToString("\n")
    }

    /**
     * Windows 驱动库体检。
     *
     * 直接对着（提取出来的分区 / 释放时的临时 NTFS）检查 in-box 驱动文件是不是 0 字节 ——
     * 这正是"设备全都找不到驱动"的根因：硬链接回退失败时只留下了空文件，而释放流程却报成功。
     */
    suspend fun auditWindowsRaw(
        context: Context,
        rawPath: String,
        onLog: (String) -> Unit = {},
    ): AuditReport {
        val lines = ArrayList<String>()
        var ok = true
        fun say(s: String) {
            lines.add(s)
            onLog(s)
        }

        if (NativeTools.resolve(context, NativeTools.NTFSLS) == null) {
            say("（未内置 ntfsls，跳过驱动库体检）")
            return AuditReport(true, lines)
        }

        suspend fun ls(dir: String): List<Entry> =
            runCatching { listNtfsRaw(context, rawPath, dir) }.getOrDefault(emptyList())

        // 1) 内核
        val sys32 = ls("/Windows/System32")
        val kernel = sys32.firstOrNull { it.name.equals("ntoskrnl.exe", ignoreCase = true) }
        when {
            kernel == null -> {
                ok = false
                say("✗ 找不到 Windows\\System32\\ntoskrnl.exe")
            }
            kernel.sizeBytes <= 0L -> {
                ok = false
                say("✗ ntoskrnl.exe 是 0 字节")
            }
            else -> say("✓ ntoskrnl.exe ${Fmt.size(kernel.sizeBytes)}")
        }

        // 2) Windows\inf —— in-box 驱动的 INF 全在这儿
        val inf = ls("/Windows/inf")
        val infFiles = inf.filter { it.name.lowercase().endsWith(".inf") }
        val infZero = infFiles.filter { it.sizeBytes <= 0L }
        when {
            infFiles.isEmpty() -> {
                ok = false
                say("✗ Windows\\inf 读不到或没有 .inf（共 ${inf.size} 项）")
            }
            infZero.isNotEmpty() -> {
                ok = false
                say(
                    "✗ Windows\\inf：${infFiles.size} 个 .inf，其中 ${infZero.size} 个是 0 字节（" +
                        infZero.take(5).joinToString { it.name } + "）"
                )
            }
            else -> say("✓ Windows\\inf：${infFiles.size} 个 .inf，全部非空")
        }

        // 3) System32\drivers
        val drivers = ls("/Windows/System32/drivers")
        val sys = drivers.filter { it.name.lowercase().endsWith(".sys") }
        val sysZero = sys.filter { it.sizeBytes <= 0L }
        when {
            sys.isEmpty() -> {
                ok = false
                say("✗ Windows\\System32\\drivers 读不到或没有 .sys")
            }
            sysZero.isNotEmpty() -> {
                ok = false
                say(
                    "✗ drivers：${sys.size} 个 .sys，其中 ${sysZero.size} 个是 0 字节（" +
                        sysZero.take(5).joinToString { it.name } + "）"
                )
            }
            else -> say("✓ drivers：${sys.size} 个 .sys，全部非空")
        }

        // 4) DriverStore：包数量 + 抽查关键 in-box 包的内容
        val storeDir = "/Windows/System32/DriverStore/FileRepository"
        val packages = ls(storeDir).filter { it.isDir }
        if (packages.isEmpty()) {
            ok = false
            say("✗ $storeDir 读不到或为空")
        } else {
            say("✓ DriverStore：${packages.size} 个驱动包")
            val wants = listOf(
                "machine.inf", "mshdc.inf", "pci.inf", "disk.inf",
                "usb.inf", "usbport.inf", "netrtl64.inf", "net8187.inf", "display.inf",
            )
            var bad = 0
            for (want in wants) {
                val pkg = packages.firstOrNull { it.name.lowercase().startsWith("${want}_") }
                    ?: packages.firstOrNull { it.name.equals(want, ignoreCase = true) }
                    ?: continue
                val files = ls("$storeDir/${pkg.name}")
                val zero = files.filter { !it.isDir && it.sizeBytes <= 0L }
                if (zero.isNotEmpty()) {
                    bad++
                    say("  ✗ ${pkg.name}：${zero.size} 个 0 字节（${zero.take(3).joinToString { it.name }}）")
                } else {
                    say("  ✓ ${pkg.name}：${files.size} 项，全部非空")
                }
            }
            if (bad > 0) ok = false
        }

        return AuditReport(ok, lines)
    }
}