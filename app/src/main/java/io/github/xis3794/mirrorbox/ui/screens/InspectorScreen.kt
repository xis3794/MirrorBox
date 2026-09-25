package io.github.xis3794.mirrorbox.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ops.EditOps
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.qcow2.ClusterStateMap
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val INSPECT_TABS = listOf("概览", "热图", "L1/L2", "快照", "分区", "Hex")

@Composable
fun InspectorScreen(nav: Navigator, path: String) {
    val file = remember(path) { File(path) }
    var tab by remember { mutableStateOf(0) }
    var structure by remember { mutableStateOf<EditOps.StructureSnapshot?>(null) }
    var info by remember { mutableStateOf<ImageOps.ImageInfo?>(null) }
    var partitions by remember { mutableStateOf<List<io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry>>(emptyList()) }
    var scheme by remember { mutableStateOf("—") }
    var l1Index by remember { mutableStateOf(0L) }
    var l2Entries by remember { mutableStateOf<LongArray?>(null) }
    var hexOffset by remember { mutableStateOf(0L) }
    var hexDump by remember { mutableStateOf("") }

    val appContext = androidx.compose.ui.platform.LocalContext.current

    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            val snapshot = EditOps.structure(file)
            val meta = ImageOps.info(appContext, file)
            val table = EditOps.detectPartitionTable(file)
            Triple(snapshot, meta, table)
        }
        structure = result.first
        info = result.second
        scheme = result.third?.scheme ?: "—"
        partitions = result.third?.partitions ?: emptyList()
    }

    LaunchedEffect(path) { refresh() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = file.name,
            subtitle = "结构预览 · ${Fmt.size(file.length())}",
            onBack = { nav.pop() },
            actions = {
                GlassButton("编辑", icon = Icons.Filled.Info) { nav.push(Screen.Editor(path)) }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            INSPECT_TABS.forEachIndexed { index, label ->
                GlassSelectChip(label, tab == index, { tab = index })
            }
        }
        Spacer(Modifier.height(12.dp))

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> OverviewTab(file, structure, info)
                1 -> HeatmapTab(structure?.states)
                2 -> L1L2Tab(
                    file = file,
                    l1Count = structure?.header?.l1Size ?: 0L,
                    l1Index = l1Index,
                    onL1Change = { index ->
                        l1Index = index
                        l2Entries = runCatching {
                            io.github.xis3794.mirrorbox.qcow2.Qcow2Image.open(file).use { img ->
                                img.l2TableEntries(index)
                            }
                        }.getOrNull()
                    },
                    l2Entries = l2Entries,
                    structure = structure,
                )
                3 -> SnapshotTab(file, structure)
                4 -> PartitionTab(scheme, partitions)
                else -> HexTab(
                    file = file,
                    offset = hexOffset,
                    dump = hexDump,
                    onOffsetChange = { hexOffset = it },
                    onLoad = {
                        val text = withContext(Dispatchers.IO) {
                            EditOps.readHex(file, hexOffset, 512)
                                ?.let { EditOps.toHexDump(it, hexOffset) }
                                ?: "读取失败"
                        }
                        hexDump = text
                        text
                    },
                    onWrite = { text ->
                        val bytes = EditOps.parseHexText(text)
                        if (bytes == null) {
                            "十六进制格式不合法"
                        } else {
                            val ok = withContext(Dispatchers.IO) { EditOps.writeHex(file, hexOffset, bytes) }
                            if (ok) "已写入 ${bytes.size} 字节" else "写入失败"
                        }
                    },
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun OverviewTab(
    file: File,
    structure: EditOps.StructureSnapshot?,
    info: ImageOps.ImageInfo?,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("镜像信息", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        if (structure == null) {
            Text("无法解析该文件（可能不是 qcow2）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        } else {
            val header = structure.header
            InfoRow("格式版本", "qcow2 v${header.version}")
            InfoRow("虚拟容量", Fmt.size(header.virtualSize))
            InfoRow("簇大小", Fmt.size(header.clusterSize.toLong()))
            InfoRow("L1 表项", header.l1Size.toString())
            InfoRow("快照数量", structure.snapshots.size.toString())
            InfoRow("已分配簇", "${structure.stats.allocatedClusters} / ${structure.stats.totalClusters}")
            InfoRow("压缩簇", structure.stats.compressedClusters.toString())
            InfoRow("宿主占用", Fmt.size(structure.stats.usedHostBytes))
            InfoRow("实际文件", Fmt.size(file.length()))
            if (structure.backingFileName != null) {
                InfoRow("backing file", structure.backingFileName)
            }
        }
    }
    if (info != null) {
        GlassCard(Modifier.fillMaxWidth()) {
            Text("qemu-img 报告", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            InfoRow("format", info.format)
            InfoRow("virtual-size", Fmt.size(info.virtualSize))
            InfoRow("actual-size", Fmt.size(info.actualSize))
            InfoRow("snapshots", info.snapshotCount.toString())
            info.backingFile?.let { InfoRow("backing", it) }
        }
    }
}

@Composable
private fun HeatmapTab(states: ClusterStateMap?) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    GlassCard(Modifier.fillMaxWidth()) {
        Text("簇分配热图", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            if (states == null) "不可用" else "共 ${states.cellCount} 格 · 每格 ${states.stepClusters} 个簇",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (states != null) {
            val columns = 48
            val rows = ((states.cellCount + columns - 1) / columns).coerceAtMost(140)
            val step = if (states.cellCount > columns * rows) {
                (states.cellCount / (columns * rows)).coerceAtLeast(1)
            } else {
                1
            }
            Canvas(Modifier.fillMaxWidth().height((rows * 4).dp.coerceAtMost(320.dp))) {
                val cellW = size.width / columns
                val cellH = size.height / rows
                var cell = 0
                while (cell < states.cellCount) {
                    val row = cell / columns
                    val col = cell % columns
                    if (row >= rows) break
                    val color = when (states.stateAt(cell)) {
                        1 -> primary
                        2 -> tertiary.copy(alpha = 0.55f)
                        3 -> secondary
                        else -> empty
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(col * cellW, row * cellH),
                        size = Size(cellW - 0.6f, cellH - 0.6f),
                    )
                    cell += step
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LegendDot(primary, "已分配")
                LegendDot(tertiary.copy(alpha = 0.55f), "零簇")
                LegendDot(secondary, "压缩")
                LegendDot(empty, "未分配")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(Modifier.height(10.dp)) {
            drawRect(color = color, topLeft = Offset.Zero, size = Size(10f, 10f))
        }
        Spacer(Modifier.height(0.dp))
        Text("  $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun L1L2Tab(
    file: File,
    l1Count: Long,
    l1Index: Long,
    onL1Change: (Long) -> Unit,
    l2Entries: LongArray?,
    structure: EditOps.StructureSnapshot?,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("L1 / L2 表", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "L1 共 $l1Count 项，点击任一项查看对应 L2 表（L2 表包含 ${structure?.header?.let { it.clusterSize / 8 } ?: 0} 个簇映射）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0L, 1L, 2L, 3L, 4L, 8L).filter { it < (l1Count.coerceAtLeast(1L)) }.forEach { index ->
                GlassSelectChip("L1[$index]", l1Index == index, { onL1Change(index) })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("L2 表项（前 40 项）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (l2Entries == null) {
            Text(
                "该 L1 项未分配 L2 表（稀疏区域）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            l2Entries.take(40).forEachIndexed { index, entry ->
                InfoRow(
                    label = "L2[$index]",
                    value = if (entry == 0L) "未分配" else "0x" + java.lang.Long.toHexString(entry),
                    mono = true,
                )
            }
        }
    }
}

@Composable
private fun SnapshotTab(file: File, structure: EditOps.StructureSnapshot?) {
    var name by remember { mutableStateOf("snapshot1") }

    GlassCard(Modifier.fillMaxWidth()) {
        Text("内部快照", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        val snapshots = structure?.snapshots ?: emptyList()
        if (snapshots.isEmpty()) {
            Text(
                "当前没有快照。创建快照后，编辑操作会通过 refcount 写时复制天然保护旧数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            snapshots.forEach { snapshot ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        snapshot.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "vsize ${Fmt.size(snapshot.virtualSize)} · ${Fmt.dateTime(snapshot.dateMillis)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("回滚", icon = Icons.Filled.PlayArrow) {
                            ImageOps.submitSnapshot("apply", snapshot.name, file)
                        }
                        GlassButton("删除", icon = Icons.Filled.Delete, accent = MaterialTheme.colorScheme.error) {
                            ImageOps.submitSnapshot("delete", snapshot.name, file)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("新快照名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GlassButton("创建快照", icon = Icons.Filled.Refresh) {
            ImageOps.submitSnapshot("create", name, file)
        }
    }
}

@Composable
private fun PartitionTab(scheme: String, partitions: List<io.github.xis3794.mirrorbox.qcow2.disk.PartitionEntry>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text("分区表：$scheme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        if (partitions.isEmpty()) {
            Text(
                "未检测到分区表（可能是空白磁盘或整盘文件系统）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            partitions.forEach { entry ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "#${entry.index} ${entry.typeName}${if (entry.name.isNotBlank()) " · ${entry.name}" else ""}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "起始 ${Fmt.size(entry.startByte)} · 大小 ${Fmt.size(entry.sizeBytes)} · 类型 ${entry.typeId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HexTab(
    file: File,
    offset: Long,
    dump: String,
    onOffsetChange: (Long) -> Unit,
    onLoad: suspend () -> String,
    onWrite: suspend (String) -> String,
) {
    var offsetText by remember { mutableStateOf(offset.toString()) }
    var patchText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    GlassCard(Modifier.fillMaxWidth()) {
        Text("十六进制查看 / 编辑", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "基于内置 qcow2 引擎，直接读取虚拟磁盘地址空间（无需挂载、无需 root）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = offsetText,
            onValueChange = {
                offsetText = it
                it.toLongOrNull()?.let { value -> onOffsetChange(value) }
            },
            singleLine = true,
            label = { Text("偏移（十进制或 0x 十六进制）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("读取 512 字节", icon = Icons.Filled.Refresh) {
                scope.launch { status = onLoad() }
            }
            GlassButton("重新解析", icon = Icons.Filled.Info) {
                scope.launch { status = onLoad() }
            }
        }
        if (dump.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                dump,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = patchText,
            onValueChange = { patchText = it },
            singleLine = true,
            label = { Text("写入字节（十六进制，如 00 FF 1A 2B）") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        GlassButton("写入", icon = Icons.Filled.PlayArrow, accent = MaterialTheme.colorScheme.error) {
            scope.launch {
                status = onWrite(patchText)
                patchText = ""
            }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}