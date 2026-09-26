package io.github.xis3794.mirrorbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ops.BootRecords
import io.github.xis3794.mirrorbox.ops.EditOps
import io.github.xis3794.mirrorbox.ops.PartitionOps
import io.github.xis3794.mirrorbox.ops.ReleaseOps
import io.github.xis3794.mirrorbox.ops.WimOps
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionTableInfo
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「释放 WIM」页：像 DISM++ 那样把一个 Windows 镜像（install.wim / boot.wim / .esd）
 * 展开到目录，再可选地直接写进镜像里的分区（ext4 用 `mke2fs -d`、FAT 用 `mcopy -s`），
 * 最后可选写入内置的 BIOS 引导记录。
 *
 * 全程不挂载、不需要 root；每一步都会把工具输出实时显示在下方日志里。
 */
@Composable
fun WimReleaseScreen(nav: Navigator, imagePath: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(imagePath) { imagePath?.takeIf { it.isNotBlank() }?.let { File(it) } }

    var wimPath by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf(emptyList<File>()) }
    var info by remember { mutableStateOf<WimOps.WimInfo?>(null) }
    var selectedIndex by remember { mutableStateOf(0) }
    var listing by remember { mutableStateOf(emptyList<String>()) }
    var listingNote by remember { mutableStateOf<String?>(null) }
    var targetPartition by remember { mutableStateOf(0) }
    var fsKind by remember { mutableStateOf(EditOps.FsKind.EXT4) }
    var label by remember { mutableStateOf("MIRRORBOX") }
    var writeBoot by remember { mutableStateOf(true) }
    var bootRecord by remember { mutableStateOf(BootRecords.MBR) }
    var stagingPath by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var table by remember { mutableStateOf<PartitionTableInfo?>(null) }

    fun append(line: String) {
        log = (log + line).takeLast(200)
    }

    fun refreshTable() {
        val img = image ?: return
        scope.launch {
            val detected = withContext(Dispatchers.IO) { EditOps.detectPartitionTable(img) }
            table = detected
            if (detected != null && detected.scheme != "none") {
                bootRecord = BootRecords.recommended(detected.scheme)
            }
        }
    }

    LaunchedEffect(imagePath) { refreshTable() }

    val wimFile = remember(wimPath) { wimPath.trim().takeIf { it.isNotBlank() }?.let { File(it) } }
    val partitions = table?.partitions.orEmpty()
    val targetEntry = partitions.firstOrNull { it.index == targetPartition }
    val stagingResult = remember(wimPath, stagingPath) {
        val typed = stagingPath.trim().takeIf { it.isNotBlank() }?.let { File(it) }
        WimOps.safeStagingDir(typed, wimFile?.nameWithoutExtension ?: "wim")
    }
    val stagingDir = stagingResult.first

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "释放 WIM",
            subtitle = image?.name ?: "不挂载 · 直接展开 Windows 镜像",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ---------------------------------------------------------------- 1. 选择镜像文件
            GlassCard(Modifier.fillMaxWidth()) {
                Text("1. 选择 WIM 文件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "支持 .wim / .esd / .swm（DISM++ 同款引擎 wimlib-imagex）。文件可以放在 /sdcard 任意位置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wimPath,
                    onValueChange = { wimPath = it },
                    singleLine = true,
                    label = { Text("路径（如 /sdcard/Download/install.wim）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("扫描常见位置", enabled = !busy) {
                        val roots = listOf(
                            File("/sdcard/Download"),
                            File("/sdcard/Documents"),
                            AppPaths.externalRoot(),
                            AppPaths.imports(),
                        )
                        val found = withFoundWims(roots)
                        candidates = found
                        status = null
                        if (found.isEmpty()) {
                            status = "没有在常见位置找到 .wim/.esd/.swm：请手动填写路径"
                        } else {
                            wimPath = found.first().absolutePath
                            status = "找到 ${found.size} 个镜像文件"
                        }
                    }
                    GlassButton("读取镜像信息", enabled = !busy && wimFile != null) {
                        val file = wimFile
                        if (file == null) {
                            status = "请先填写 WIM 路径"
                        } else {
                            scope.launch {
                                busy = true
                                info = null
                                selectedIndex = 0
                                log = emptyList()
                                val parsed = withContext(Dispatchers.IO) {
                                    runCatching { WimOps.info(context, file) { append(it) } }
                                        .getOrElse { WimOps.WimInfo(emptyList(), "", "", 0, "", emptyList()) }
                                }
                                info = parsed
                                selectedIndex = parsed.images.firstOrNull()?.index ?: 0
                                if (parsed.images.isEmpty()) {
                                    status = "读取失败：请确认文件是有效的 WIM/ESD（工具输出见下方日志）"
                                } else {
                                    status = "共 ${parsed.images.size} 个索引（压缩 ${parsed.compression.ifBlank { "?" }}）"
                                }
                                busy = false
                            }
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("候选文件（点选）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    candidates.take(5).forEach { candidate ->
                        GlassButton(
                            text = "${candidate.name} · ${Fmt.size(candidate.length())}",
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            wimPath = candidate.absolutePath
                            info = null
                            listing = emptyList()
                            status = "已选择 ${candidate.name}，点「读取镜像信息」"
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // ---------------------------------------------------------------- 2. 索引
            val currentInfo = info
            if (currentInfo != null && currentInfo.images.isNotEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("2. 选择一个索引", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("压缩", currentInfo.compression.ifBlank { "-" })
                    if (currentInfo.chunkSize.isNotBlank()) InfoRow("块大小", currentInfo.chunkSize)
                    if (currentInfo.bootIndex > 0) InfoRow("引导索引", currentInfo.bootIndex.toString())
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        currentInfo.images.take(8).forEach { img ->
                            GlassButton(
                                text = "${img.index}. ${img.title}",
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                            ) {
                                selectedIndex = img.index
                                listing = emptyList()
                                status = "已选择 ${img.subtitle}"
                            }
                        }
                    }
                    selectedImage(currentInfo, selectedIndex)?.let { img ->
                        Spacer(Modifier.height(6.dp))
                        InfoRow("已选", img.title)
                        InfoRow("细节", img.subtitle)
                    }
                }
            }

            // ---------------------------------------------------------------- 3. 预览
            if (currentInfo != null && selectedIndex > 0) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("3. 预览内容", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    GlassButton("列出根目录", enabled = !busy) {
                        scope.launch {
                            busy = true
                            val (lines, truncated) = withContext(Dispatchers.IO) {
                                runCatching { WimOps.dir(context, wimFile!!, selectedIndex, "/", 300) }
                                    .getOrDefault(emptyList<String>() to false)
                            }
                            listing = lines
                            listingNote = if (truncated) "仅显示前 300 行（镜像里还有更多）" else null
                            status = if (lines.isEmpty()) "列目录失败或目录为空" else "已列出 ${lines.size} 行"
                            busy = false
                        }
                    }
                    listingNote?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (listing.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(Modifier.fillMaxWidth()) {
                            listing.take(120).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (listing.size > 120) {
                                Text("…（其余 ${listing.size - 120} 行已省略）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- 4. 释放目标
            GlassCard(Modifier.fillMaxWidth()) {
                Text("4. 释放到哪里", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "先展开到目录（必须），再可选地把目录写进镜像里的某个分区。" +
                        "写入走稀疏轨：不复制分区旧数据，只把 mkfs/mcopy 真正写过的数据段回写进 qcow2，" +
                        "临时占用与分区大小无关（通常几十 MB 起）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stagingPath,
                    onValueChange = { stagingPath = it },
                    singleLine = true,
                    label = { Text("展开目录（留空用默认；必须在应用内部存储）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                InfoRow("实际目录", stagingDir.absolutePath)
                InfoRow("目录体积", Fmt.size(AppPaths.sizeOfTree(stagingDir)))
                stagingResult.second?.let { note ->
                    Spacer(Modifier.height(4.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Windows 镜像里有符号链接（如 Documents and Settings）和大量硬链接，" +
                        "共享存储（/sdcard、Android/data）不允许应用创建链接，所以展开目录必须在应用内部存储。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (image == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("当前没有打开镜像：只能释放到目录。从「镜像 → 编辑 → 释放 WIM」进入即可写入分区。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text("目标分区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassSelectChip("仅目录", targetPartition == 0, { targetPartition = 0 })
                        partitions.take(4).forEach { entry ->
                            GlassSelectChip("分区 ${entry.index}", targetPartition == entry.index, { targetPartition = entry.index })
                        }
                    }
                    if (partitions.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("镜像里还没有分区：先到「分区编辑器」创建并应用分区表。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (targetEntry != null) {
                        Spacer(Modifier.height(8.dp))
                        InfoRow("分区大小", Fmt.size(targetEntry.sizeBytes))
                        val treeBytes = AppPaths.sizeOfTree(stagingDir)
                        if (treeBytes > 0) {
                            val check = ReleaseOps.checkSparseWrite(targetEntry.sizeBytes, treeBytes)
                            InfoRow("空间预检", check.text + if (check.enough) " · 充足" else " · 不足")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("文件系统", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            EditOps.FsKind.entries.forEach { kind ->
                                GlassSelectChip(
                                    if (ReleaseOps.supported(kind)) kind.label else "${kind.label}（暂不支持）",
                                    fsKind == kind,
                                    { fsKind = kind },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            singleLine = true,
                            label = { Text("卷标") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!ReleaseOps.supported(fsKind)) {
                            Spacer(Modifier.height(4.dp))
                            Text(ReleaseOps.unsupportedReason(fsKind),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- 5. BIOS 引导
            if (image != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("5. BIOS 引导（可选）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "内置 syslinux 引导记录，开箱即用：写入 MBR 前 440 字节，分区表与 0x55AA 不变；" +
                            "没有活动分区时会自动把第一个分区标记为活动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassSelectChip("释放后写入", writeBoot, { writeBoot = !writeBoot })
                        GlassSelectChip("跳过", !writeBoot, { writeBoot = false })
                    }
                    Spacer(Modifier.height(8.dp))
                    BootRecords.MBR_RECORDS.forEach { record ->
                        val present = BootRecords.available(context, record)
                        GlassButton(
                            text = if (present) "${record.title}（${record.sizeBytes} B）" else "${record.title}（缺失）",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = present && !busy,
                        ) {
                            bootRecord = record
                            status = "引导记录：${record.title} —— ${record.detail}"
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    InfoRow("当前选择", bootRecord.title)
                    Spacer(Modifier.height(8.dp))
                    GlassButton("查看当前引导代码", enabled = !busy) {
                        val code = PartitionOps.readBootCode(image)
                        status = if (code == null) {
                            "无法读取 MBR"
                        } else {
                            val nonZero = code.count { it.toInt() != 0 }
                            val hex = code.take(16).joinToString(" ") { "%02X".format(it) }
                            "前 16 字节：$hex（非零 $nonZero/440，已含系统的引导代码？）"
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- 6. 执行
            GlassCard(Modifier.fillMaxWidth()) {
                Text("6. 开始释放", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                val canRun = !busy && wimFile != null && selectedIndex > 0
                GlassButton("展开到目录", enabled = canRun) {
                    val file = wimFile
                    if (file == null || selectedIndex <= 0) {
                        status = "请先选择 WIM 与索引"
                    } else {
                        scope.launch {
                            busy = true
                            log = emptyList()
                            status = "正在释放镜像 $selectedIndex …"
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    stagingDir.deleteRecursively()
                                    val result = WimOps.apply(context, file, selectedIndex, stagingDir) { append(it) }
                                    val (bytes, count) = WimOps.treeStats(stagingDir)
                                    if (result.success) {
                                        "已展开到 ${stagingDir.absolutePath}（$count 个文件，${Fmt.size(bytes)}）"
                                    } else {
                                        "释放失败（退出码 ${result.exitCode}）：${result.lines.lastOrNull().orEmpty()}"
                                    }
                                }.getOrElse { "释放异常：${it.message}" }
                            }
                            status = outcome
                            busy = false
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                GlassButton(
                    text = if (targetEntry == null) "仅展开到目录" else "展开并写入分区 ${targetEntry.index}",
                    enabled = canRun && targetEntry != null && ReleaseOps.supported(fsKind),
                ) {
                    val file = wimFile
                    val entry = targetEntry
                    val img = image
                    if (file == null || entry == null || img == null) {
                        status = "请选择 WIM、索引与目标分区"
                    } else {
                        scope.launch {
                            busy = true
                            log = emptyList()
                            status = "正在释放镜像 $selectedIndex → 分区 ${entry.index} …"
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    val treeBytes = AppPaths.sizeOfTree(stagingDir)
                                    val check = ReleaseOps.checkSpace(entry.sizeBytes, treeBytes)
                                    append("空间预检：${check.text}")
                                    stagingDir.deleteRecursively()
                                    val applied = WimOps.apply(context, file, selectedIndex, stagingDir) { append(it) }
                                    if (!applied.success) {
                                        return@runCatching "展开失败（退出码 ${applied.exitCode}）：${applied.lines.lastOrNull().orEmpty()}"
                                    }
                                    val (bytes, count) = WimOps.treeStats(stagingDir)
                                    append("展开完成：$count 个文件，${Fmt.size(bytes)}")
                                    val release = ReleaseOps.releaseDirectory(
                                        context, img, entry, stagingDir, fsKind, label,
                                        onLog = { append(it) },
                                        onProgress = { done ->
                                            if (done % (64L * 1024 * 1024) < 65536L) append("  回写 ${Fmt.size(done)}/${Fmt.size(entry.sizeBytes)}")
                                        },
                                    )
                                    if (!release.ok) return@runCatching release.message
                                    if (writeBoot) {
                                        val boot = BootRecords.install(context, img, bootRecord)
                                        append(boot.message)
                                        release.message + " · " + boot.message
                                    } else {
                                        release.message
                                    }
                                }.getOrElse { "写入分区异常：${it.message}" }
                            }
                            status = outcome
                            refreshTable()
                            busy = false
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "提示：写入会把目标分区原有文件系统整个替换；请先确认分区没选错。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status?.let {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("状态", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (log.isNotEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("日志（工具实时输出）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    log.takeLast(60).forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun selectedImage(info: WimOps.WimInfo, index: Int): WimOps.WimImage? =
    info.images.firstOrNull { it.index == index }

/** 在若干目录（含一级子目录）里找 .wim/.esd/.swm，按体积从大到小排序。 */
private fun withFoundWims(roots: List<File>): List<File> {
    val out = ArrayList<File>()
    for (root in roots) {
        if (!root.isDirectory) continue
        val level1 = root.listFiles().orEmpty()
        for (entry in level1) {
            if (entry.isFile && entry.hasWimExtension()) out.add(entry)
            if (entry.isDirectory) {
                entry.listFiles().orEmpty().forEach { child ->
                    if (child.isFile && child.hasWimExtension()) out.add(child)
                }
            }
        }
        if (out.size >= 12) break
    }
    return out.sortedByDescending { it.length() }.take(12)
}

private fun File.hasWimExtension(): Boolean {
    val n = name.lowercase()
    return n.endsWith(".wim") || n.endsWith(".esd") || n.endsWith(".swm")
}