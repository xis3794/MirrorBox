package io.github.xis3794.mirrorbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.StorageGateway
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import io.github.xis3794.mirrorbox.ui.icons.MbIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class PickerAction(val title: String) {
    CHECK("一致性检查"),
    REPAIR("检查并修复"),
    COMPRESS("压缩瘦身"),
    EXPORT_RAW("导出 raw"),
    SNAPSHOT("快照管理"),
    INSPECT("结构预览"),
    EDIT("十六进制编辑"),
}

@Composable
fun ToolsScreen(nav: Navigator) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<PickerAction?>(null) }
    var images by remember { mutableStateOf(emptyList<StorageGateway.ImageFile>()) }

    LaunchedEffect(picker) {
        if (picker != null) {
            images = withContext(Dispatchers.IO) { StorageGateway.listImages() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                ScreenHeader(
                    title = "工具箱",
                    subtitle = "全部离线执行 · 写操作可自动 qemu-img check 校验",
                    onBack = { nav.pop() },
                )
            }

            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("新建镜像", "空白 qcow2，可选簇大小", MbIcons.Disk, Modifier.weight(1f)) { nav.push(Screen.Create) }
                        ToolCard("格式转换", "qcow2 ↔ vmdk/vhdx/vdi/raw", Icons.Filled.Refresh, Modifier.weight(1f)) { nav.push(Screen.Convert) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("制作 ISO", "从文件夹生成，可配置引导", MbIcons.Iso, Modifier.weight(1f)) { nav.push(Screen.IsoStudio) }
                        ToolCard("结构预览", "簇热图 / L1L2 / 快照", MbIcons.Grid, Modifier.weight(1f)) { picker = PickerAction.INSPECT }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("一致性检查", "扫描并报告损坏", Icons.Filled.Info, Modifier.weight(1f)) { picker = PickerAction.CHECK }
                        ToolCard("检查并修复", "尽力修复泄漏与错误", Icons.Filled.Refresh, Modifier.weight(1f)) { picker = PickerAction.REPAIR }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("压缩瘦身", "convert -c 压缩未使用数据", MbIcons.Disk, Modifier.weight(1f)) { picker = PickerAction.COMPRESS }
                        ToolCard("导出 raw", "转换为原始磁盘镜像", MbIcons.Disk, Modifier.weight(1f)) { picker = PickerAction.EXPORT_RAW }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("快照管理", "创建 / 删除 / 回滚", MbIcons.Iso, Modifier.weight(1f)) { picker = PickerAction.SNAPSHOT }
                        ToolCard("十六进制编辑", "引擎级扇区读写", MbIcons.Hex, Modifier.weight(1f)) { picker = PickerAction.EDIT }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ToolCard("工具自检", "查看原生工具的版本", MbIcons.Terminal, Modifier.weight(1f)) { nav.push(Screen.SelfCheck()) }
                        ToolCard("任务中心", "运行状态与日志", MbIcons.Terminal, Modifier.weight(1f)) { nav.push(Screen.Tasks) }
                    }
                }
            }
        }

        val action = picker
        if (action != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.42f))
                    .clickable { picker = null },
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "选择镜像 · ${action.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (images.isEmpty()) {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Text(
                                "工作区里还没有镜像，请先新建或导入。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(images) { image ->
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        picker = null
                                        runAction(nav, action, image.file)
                                    },
                                    contentPadding = PaddingValues(12.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                image.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                image.prettySize,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        GlassChip(image.extension)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun runAction(nav: Navigator, action: PickerAction, file: File) {
    when (action) {
        PickerAction.CHECK -> ImageOps.submitCheck(file, repair = false)
        PickerAction.REPAIR -> ImageOps.submitCheck(file, repair = true)
        PickerAction.COMPRESS -> {
            val target = AppPaths.uniqueFile(AppPaths.images, file.nameWithoutExtension + "-compressed", ".qcow2")
            ImageOps.submitCompress(file, target)
        }
        PickerAction.EXPORT_RAW -> {
            val target = AppPaths.uniqueFile(AppPaths.work, file.nameWithoutExtension, ".raw")
            ImageOps.submitExportRaw(file, target)
        }
        PickerAction.SNAPSHOT -> nav.push(Screen.Inspector(file.absolutePath))
        PickerAction.INSPECT -> nav.push(Screen.Inspector(file.absolutePath))
        PickerAction.EDIT -> nav.push(Screen.Editor(file.absolutePath))
    }
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
        contentPadding = PaddingValues(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}