package io.github.xis3794.mirrorbox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import io.github.xis3794.mirrorbox.core.NativeTool
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.ToolRunner
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ToolStatus(val tool: NativeTool, val available: Boolean, val version: String?)

@Composable
fun SelfCheckScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf(emptyList<ToolStatus>()) }
    var loading by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            loading = true
            statuses = withContext(Dispatchers.IO) {
                NativeTools.ALL.map { tool ->
                    ToolStatus(tool, NativeTools.isAvailable(context, tool), ToolRunner.version(context, tool))
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            ScreenHeader(
                title = "工具自检",
                subtitle = "共 ${statuses.count { it.available }} / ${NativeTools.ALL.size} 个工具可用",
                onBack = { nav.pop() },
                actions = {
                    GlassButton("重新检测", icon = Icons.Filled.Refresh) { refresh() }
                },
            )
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text("执行位置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        NativeTools.nativeLibraryDir(context)?.absolutePath ?: "（不可用）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (statuses.none { it.available }) {
                            "当前 APK 未包含原生工具链：这是 Debug 构建或 CI 未注入 jniLibs。转换 / ISO / 文件系统功能会提示缺少工具，但内置 qcow2 引擎的预览与直写仍然可用。"
                        } else {
                            "所有工具均在应用进程内执行，无需 root、无需联网。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(statuses) { status ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 5.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                status.tool.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                status.tool.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            status.version?.let {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        GlassChip(
                            text = if (status.available) "可用" else "缺失",
                            accent = if (status.available) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (loading) {
            item {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("检测中…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}