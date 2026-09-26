package io.github.xis3794.mirrorbox.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.StorageGateway
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.components.SectionTitle
import io.github.xis3794.mirrorbox.ui.components.StatTile
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import io.github.xis3794.mirrorbox.ui.icons.MbIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var toolCount by remember { mutableStateOf(-1) }
    var imageCount by remember { mutableStateOf(0) }
    var recent by remember { mutableStateOf(emptyList<StorageGateway.ImageFile>()) }
    var usageText by remember { mutableStateOf("—") }
    var freeText by remember { mutableStateOf("—") }
    var allFilesAccess by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val snapshot = withContext(Dispatchers.IO) {
            // 兜底超时：任何一次检测卡住也不会让首页永远停在"正在检测…"。
            kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                val tools = NativeTools.availableCount(context)
                val images = StorageGateway.listImages()
                val (used, free) = StorageGateway.diskUsage()
                Data(tools, images, used, free)
            }
        }
        if (snapshot == null) {
            toolCount = 0
            usageText = "—"
            freeText = "—"
            return@LaunchedEffect
        }
        toolCount = snapshot.toolCount
        imageCount = snapshot.images.size
        recent = snapshot.images.take(3)
        usageText = Fmt.size(snapshot.used)
        freeText = Fmt.size(snapshot.free)
        allFilesAccess = AppPaths.hasAllFilesAccess()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 200.dp),
    ) {
        item {
            ScreenHeader(
                title = "镜像匣",
                subtitle = "离线 qcow2 工具箱 · 不启动虚拟机即可预览 / 编辑 / 生成",
            )
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            MbIcons.Disk,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "原生工具链",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                when {
                                    toolCount < 0 -> "正在检测…"
                                    toolCount == 0 -> "未打包（Debug 构建或缺少 CI 产物）"
                                    else -> "已就绪 $toolCount 个工具"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (toolCount > 0) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        GlassButton(text = "自检", icon = Icons.Filled.Info) { nav.push(Screen.SelfCheck()) }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile("镜像数量", imageCount.toString(), Modifier.weight(1f))
                StatTile("工作区占用", usageText, Modifier.weight(1f), accent = MaterialTheme.colorScheme.secondary)
                StatTile("可用空间", freeText, Modifier.weight(1f), accent = MaterialTheme.colorScheme.tertiary)
            }
        }

        item { SectionTitle("快速操作") }

        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("新建镜像", modifier = Modifier.weight(1f), icon = MbIcons.Disk) {
                        nav.push(Screen.Create)
                    }
                    GlassButton("格式转换", modifier = Modifier.weight(1f), icon = Icons.Filled.PlayArrow) {
                        nav.push(Screen.Convert)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("制作 ISO", modifier = Modifier.weight(1f), icon = MbIcons.Iso) {
                        nav.push(Screen.IsoStudio)
                    }
                    GlassButton("工具箱", modifier = Modifier.weight(1f), icon = MbIcons.Grid) {
                        nav.push(Screen.Tools)
                    }
                }
            }
        }

        item { SectionTitle("最近镜像") }

        if (recent.isEmpty()) {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "还没有镜像。点击中间的 + 新建一个 qcow2，或从「镜像库」导入现有文件。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(recent) { image ->
                Box(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { nav.push(Screen.Inspector(image.file.absolutePath)) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    image.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${image.prettySize} · ${image.prettyDate}",
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

        item { SectionTitle("环境") }

        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("存储模式", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (allFilesAccess) {
                            "已开启「所有文件访问」：可直接原位操作 /sdcard 上的大镜像"
                        } else {
                            "沙盒模式：镜像放在应用专属目录，也可通过 SAF 导入导出"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class Data(
    val toolCount: Int,
    val images: List<StorageGateway.ImageFile>,
    val used: Long,
    val free: Long,
)