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
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ops.EditOps
import io.github.xis3794.mirrorbox.ops.GuestFsOps
import io.github.xis3794.mirrorbox.ops.ReleaseOps
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
 * 客户机文件浏览器：把镜像里的分区提取成临时 raw，然后用 debugfs / mtools / ntfsprogs
 * 浏览、复制、删除文件，最后差分写回镜像。全程不挂载、不需要 root。
 */
@Composable
fun GuestFilesScreen(nav: Navigator, path: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val image = remember(path) { File(path) }

    var table by remember { mutableStateOf<PartitionTableInfo?>(null) }
    var partitionIndex by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf(EditOps.FsKind.EXT4) }
    var session by remember { mutableStateOf<GuestFsOps.Session?>(null) }
    var currentPath by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf(emptyList<GuestFsOps.Entry>()) }
    var selected by remember { mutableStateOf<GuestFsOps.Entry?>(null) }
    var hostPath by remember { mutableStateOf("") }
    var newDirName by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    fun append(line: String) {
        log = (log + line).takeLast(150)
    }

    fun refreshTable() {
        scope.launch {
            val detected = withContext(Dispatchers.IO) { EditOps.detectPartitionTable(image) }
            table = detected
            val first = detected?.partitions?.firstOrNull()
            if (first != null) {
                partitionIndex = first.index
                kind = GuestFsOps.guessKind(first)
            }
        }
    }

    LaunchedEffect(path) { refreshTable() }

    val partitions = table?.partitions.orEmpty()
    val entry = partitions.firstOrNull { it.index == partitionIndex }

    fun loadDirectory(target: String) {
        val active = session ?: return
        scope.launch {
            busy = true
            val listed = withContext(Dispatchers.IO) {
                runCatching { active.list(target) { append(it) } }.getOrElse { emptyList() }
            }
            currentPath = GuestFsOps.normalise(target)
            entries = listed
            selected = null
            if (listed.isEmpty()) status = "目录为空，或读取失败（见日志）"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "客户机文件",
            subtitle = "${image.name} · 不挂载直接读写分区",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            GlassCard(Modifier.fillMaxWidth()) {
                Text("1. 选择分区", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                if (partitions.isEmpty()) {
                    Text("镜像里没有分区表：先到「分区编辑器」创建分区。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        partitions.take(4).forEach { candidate ->
                            GlassSelectChip("分区 ${candidate.index}", partitionIndex == candidate.index, {
                                partitionIndex = candidate.index
                                kind = GuestFsOps.guessKind(candidate)
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        EditOps.FsKind.entries.forEach { k ->
                            GlassSelectChip(k.label, kind == k, { kind = k })
                        }
                    }
                    if (entry != null) {
                        Spacer(Modifier.height(8.dp))
                        InfoRow("分区大小", Fmt.size(entry.sizeBytes))
                        InfoRow("类型", entry.typeName.ifBlank { entry.typeId })
                        val check = ReleaseOps.checkSpace(entry.sizeBytes, 0L)
                        InfoRow("空间需求", "临时副本 ${Fmt.size(entry.sizeBytes)}，可用 ${Fmt.size(check.freeBytes)}")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("打开分区", enabled = !busy && entry != null) {
                            val target = entry
                            if (target == null) {
                                status = "请选择分区"
                            } else {
                                scope.launch {
                                    busy = true
                                    log = emptyList()
                                    session?.discard()
                                    val created = GuestFsOps.Session(context, image, target, kind, GuestFsOps.newRawFile(target))
                                    val error = withContext(Dispatchers.IO) {
                                        created.open(onLog = { append(it) })
                                    }
                                    if (error != null) {
                                        status = error
                                    } else {
                                        session = created
                                        dirty = false
                                        status = "分区 ${target.index} 已打开（${kind.label}）"
                                        loadDirectory("/")
                                    }
                                    busy = false
                                }
                            }
                        }
                        GlassButton("写回镜像", enabled = !busy && session != null && dirty) {
                            val active = session
                            if (active == null) {
                                status = "请先打开分区"
                            } else {
                                scope.launch {
                                    busy = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            active.commit(onLog = { append(it) })?.let {
                                                "已写回：变化 ${it.changedClusters} 个簇（${Fmt.size(it.bytesWritten)}）"
                                            } ?: "没有可写回的内容"
                                        }.getOrElse { "写回失败：${it.message}" }
                                    }
                                    status = result
                                    dirty = false
                                    busy = false
                                }
                            }
                        }
                        GlassButton("丢弃临时副本", enabled = !busy && session != null) {
                            session?.discard()
                            session = null
                            entries = emptyList()
                            dirty = false
                            status = "已丢弃临时副本（镜像未改动）"
                        }
                    }
                }
            }

            val active = session
            if (active != null) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("2. 浏览", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("当前路径", currentPath)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("上级", enabled = !busy) { loadDirectory(GuestFsOps.parentPath(currentPath)) }
                        GlassButton("根目录", enabled = !busy) { loadDirectory("/") }
                        GlassButton("刷新", enabled = !busy) { loadDirectory(currentPath) }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (entries.isEmpty()) {
                        Text("目录为空（或读取失败，见日志）。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    entries.take(80).forEach { item ->
                        GlassButton(
                            text = "${item.display}  ${if (item.isDir) "" else item.detail}",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                        ) {
                            if (item.isDir) {
                                selected = null
                                loadDirectory(GuestFsOps.childPath(currentPath, item.name))
                            } else {
                                selected = item
                                status = "已选择文件 ${item.name}（${item.detail}）"
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (entries.size > 80) {
                        Text("… 还有 ${entries.size - 80} 项，请进入子目录查看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    Text("3. 操作", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("已选", selected?.display ?: "（未选择文件）")
                    InfoRow("导出目录", GuestFsOps.exportDir().absolutePath)
                    InfoRow(
                        "下载目录",
                        GuestFsOps.publicExportDir()?.absolutePath
                            ?: "不可用（去「设置」开启「所有文件访问」后可用 /sdcard/Download/MirrorBox）",
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton("复制出来", enabled = !busy && selected != null) {
                        val item = selected
                        if (item == null) {
                            status = "请先选择一个文件"
                        } else {
                            scope.launch {
                                busy = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val error = active.copyOut(GuestFsOps.childPath(currentPath, item.name), GuestFsOps.exportDir()) { append(it) }
                                        if (error != null) {
                                            "导出失败：$error"
                                        } else {
                                            val exported = File(GuestFsOps.exportDir(), item.name)
                                            val public = GuestFsOps.publicExportDir()
                                            if (public != null) {
                                                val copied = runCatching { exported.copyTo(File(public, item.name), overwrite = true) }.isSuccess
                                                if (copied) {
                                                    "已导出到 ${public.absolutePath}/${item.name}（同时保留在应用目录）"
                                                } else {
                                                    "已导出到 ${exported.absolutePath}（复制到下载目录失败）"
                                                }
                                            } else {
                                                "已导出到 ${exported.absolutePath}（开启「所有文件访问」可直接导出到 /sdcard/Download）"
                                            }
                                        }
                                    }.getOrElse { "导出异常：${it.message}" }
                                }
                                status = result
                                busy = false
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hostPath,
                        onValueChange = { hostPath = it },
                        singleLine = true,
                        label = { Text("要复制的宿主文件（如 /sdcard/Download/hello.txt）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton("复制进 $currentPath", enabled = !busy && hostPath.isNotBlank()) {
                        scope.launch {
                            busy = true
                            val source = File(hostPath.trim())
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val error = active.copyIn(source, currentPath) { append(it) }
                                    if (error == null) {
                                        dirty = true
                                        "已写入 ${source.name} → $currentPath"
                                    } else {
                                        "写入失败：$error"
                                    }
                                }.getOrElse { "写入异常：${it.message}" }
                            }
                            status = result
                            loadDirectory(currentPath)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("删除所选", enabled = !busy && selected != null) {
                            val item = selected
                            if (item == null) {
                                status = "请先选择文件"
                            } else {
                                scope.launch {
                                    busy = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val error = active.delete(GuestFsOps.childPath(currentPath, item.name)) { append(it) }
                                            if (error == null) {
                                                dirty = true
                                                "已删除 ${item.name}（记得写回镜像）"
                                            } else {
                                                "删除失败：$error"
                                            }
                                        }.getOrElse { "删除异常：${it.message}" }
                                    }
                                    status = result
                                    loadDirectory(currentPath)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDirName,
                        onValueChange = { newDirName = it },
                        singleLine = true,
                        label = { Text("新建目录名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    GlassButton("新建目录", enabled = !busy && newDirName.isNotBlank()) {
                        scope.launch {
                            busy = true
                            val target = GuestFsOps.childPath(currentPath, newDirName.trim())
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val error = active.makeDirectory(target) { append(it) }
                                    if (error == null) {
                                        dirty = true
                                        "已创建 $target（记得写回镜像）"
                                    } else {
                                        "创建失败：$error"
                                    }
                                }.getOrElse { "创建异常：${it.message}" }
                            }
                            status = result
                            newDirName = ""
                            loadDirectory(currentPath)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "改动只作用于临时副本，点「写回镜像」才会真正修改 qcow2；写回只覆盖变化的簇。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    Text("工具输出", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    log.takeLast(40).forEach { line ->
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