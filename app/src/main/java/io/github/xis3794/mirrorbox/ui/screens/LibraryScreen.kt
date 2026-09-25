package io.github.xis3794.mirrorbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.StorageGateway
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.ui.components.EmptyState
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import io.github.xis3794.mirrorbox.ui.icons.MbIcons
import io.github.xis3794.mirrorbox.ui.share.shareFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LibraryScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf(emptyList<StorageGateway.ImageFile>()) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        images = withContext(Dispatchers.IO) { StorageGateway.listImages() }
    }

    LaunchedEffect(Unit) { refresh() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                status = "正在导入…"
                val copied = withContext(Dispatchers.IO) {
                    StorageGateway.importFromUri(context, uri, AppPaths.imports())
                }
                status = if (copied != null) "已导入 ${copied.name}" else "导入失败"
                refresh()
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 140.dp),
    ) {
        item {
            ScreenHeader(
                title = "镜像库",
                subtitle = if (status != null) status else "共 ${images.size} 个镜像 · 工作区与外部目录",
                actions = {
                    GlassButton("导入", icon = Icons.Filled.Refresh, onClick = { importer.launch(arrayOf("*/*")) })
                },
            )
        }

        if (images.isEmpty()) {
            item {
                EmptyState(
                    icon = MbIcons.Disk,
                    title = "还没有镜像",
                    message = "点击右上角「导入」选择 qcow2 / vmdk / raw 文件，或用 + 新建一个空白镜像。",
                )
            }
        }

        items(images) { image ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                image.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${image.prettySize} · ${image.prettyDate}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        GlassChip(image.extension)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton(
                            "预览",
                            icon = Icons.Filled.Info,
                            onClick = { nav.push(Screen.Inspector(image.file.absolutePath)) },
                        )
                        GlassButton(
                            "检查",
                            icon = Icons.Filled.Refresh,
                            onClick = { ImageOps.submitCheck(image.file, repair = false) },
                        )
                        GlassButton(icon = Icons.Filled.Share, text = "分享", onClick = { shareFile(context, image.file) })
                        GlassButton(
                            "删除",
                            icon = Icons.Filled.Delete,
                            accent = MaterialTheme.colorScheme.error,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { image.file.delete() }
                                    refresh()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}