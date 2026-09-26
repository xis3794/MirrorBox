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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ops.EditOps
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry
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
 * Safe-mode editor: extract a partition to a raw file, edit it with the bundled filesystem tools,
 * then write back only the changed clusters and let qemu-img verify the result.
 */
@Composable
fun EditorScreen(nav: Navigator, path: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { File(path) }

    var partitions by remember { mutableStateOf(emptyList<PartitionEntry>()) }
    var selected by remember { mutableStateOf<PartitionEntry?>(null) }
    var workFile by remember { mutableStateOf<File?>(null) }
    var fsKind by remember { mutableStateOf(EditOps.FsKind.EXT4) }
    var label by remember { mutableStateOf("MIRRORBOX") }
    var status by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        partitions = withContext(Dispatchers.IO) {
            EditOps.detectPartitionTable(file)?.partitions ?: emptyList()
        }
    }

    fun run(block: suspend () -> String) {
        scope.launch {
            busy = true
            status = "处理中…"
            status = runCatching { block() }.getOrElse { "失败：${it.message}" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "安全模式编辑",
            subtitle = "${file.name} · 提取 → 工具编辑 → 差异回写",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("流程说明", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "镜像不会整体解密或挂载：只有目标分区会被提取成临时 raw 文件，用 e2fsprogs / mtools / ntfsprogs 修改后，" +
                        "引擎按簇对拍，只把真正变化的簇写回 qcow2（节省大量写入与空间）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("分区表", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "创建 / 删除 / 调整分区，格式化分区，写入 MBR 引导代码（BIOS 引导）——全部在应用内完成，不需要 root 或 loop 设备。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("打开分区编辑器") { nav.push(Screen.Partitions(path)) }
                Spacer(Modifier.height(8.dp))
                GlassButton("释放 WIM 到分区（DISM++ 式）") { nav.push(Screen.WimRelease(path)) }
                Spacer(Modifier.height(8.dp))
                GlassButton("客户机文件浏览") { nav.push(Screen.GuestFiles(path)) }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("分区", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                if (partitions.isEmpty()) {
                    Text(
                        "未检测到分区表。可以先用工具箱里的“分区与格式化”向导创建带分区的磁盘。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    partitions.forEach { entry ->
                        val isSelected = selected?.index == entry.index
                        GlassCard(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            onClick = { selected = entry },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        ) {
                            Text(
                                "#${entry.index} ${entry.typeName}",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "起始 ${Fmt.size(entry.startByte)} · 大小 ${Fmt.size(entry.sizeBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("提取分区", icon = Icons.Filled.Refresh, enabled = selected != null) {
                        val entry = selected ?: return@GlassButton
                        run {
                            val target = File(AppPaths.work, "${file.nameWithoutExtension}-part${entry.index}.img")
                            val ok = EditOps.extractRange(file, entry.startByte, entry.sizeBytes, target)
                            if (ok) {
                                workFile = target
                                "已提取到 ${target.name}（${Fmt.size(target.length())}）"
                            } else {
                                "提取失败"
                            }
                        }
                    }
                    GlassButton("回写修改", icon = Icons.Filled.Check, enabled = selected != null && workFile != null) {
                        val entry = selected ?: return@GlassButton
                        val copy = workFile ?: return@GlassButton
                        run {
                            val result = EditOps.writeBackRange(file, entry.startByte, entry.sizeBytes, copy)
                            ImageOps.submitCheck(file, repair = false)
                            "回写完成：${result.changedClusters}/${result.scannedClusters} 个簇发生变化（${Fmt.size(result.bytesWritten)}）"
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("文件系统工具（作用于提取出的副本）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditOps.FsKind.entries.forEach { kind ->
                        GlassSelectChip(kind.label, fsKind == kind, { fsKind = kind })
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("卷标") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("格式化副本", enabled = workFile != null) {
                        val copy = workFile ?: return@GlassButton
                        run {
                            val result = EditOps.format(context, fsKind, copy, label)
                            if (result.success) "已在副本上创建 ${fsKind.label}（记得回写）" else "格式化失败：${result.summary}"
                        }
                    }
                    GlassButton("列出根目录", enabled = workFile != null) {
                        val copy = workFile ?: return@GlassButton
                        run {
                            val lines = EditOps.listFiles(context, fsKind, copy, 0L, "/")
                            listing = lines.take(40).joinToString("\n")
                            if (lines.isEmpty()) "没有输出（文件系统工具可能未打包）" else "已列出 ${lines.size} 行"
                        }
                    }
                }
                if (listing.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        listing,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                InfoRow("临时目录", AppPaths.work.absolutePath)
                InfoRow("当前副本", workFile?.name ?: "—")
                InfoRow("状态", if (busy) "处理中" else "空闲")
            }

            status?.let {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}