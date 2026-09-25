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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.TaskItem
import io.github.xis3794.mirrorbox.core.TaskManager
import io.github.xis3794.mirrorbox.core.TaskStatus
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ui.components.ProgressLine
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip

@Composable
fun TasksScreen(nav: Navigator) {
    val tasks by TaskManager.tasks.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var logTask by remember { mutableStateOf<TaskItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp),
        ) {
            item {
                ScreenHeader(
                    title = "任务中心",
                    subtitle = "所有原生工具的实时进度与日志",
                    actions = {
                        GlassButton("清空", icon = Icons.Filled.Clear) { TaskManager.clearFinished() }
                    },
                )
            }

            if (tasks.isEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Text(
                            "还没有任务。创建、转换、检查镜像时会在这里显示实时进度与日志。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(tasks) { task ->
                val accent = when (task.status) {
                    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    TaskStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
                    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                    TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    task.detail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            GlassChip(
                                text = when (task.status) {
                                    TaskStatus.RUNNING -> "运行中"
                                    TaskStatus.SUCCESS -> "完成"
                                    TaskStatus.FAILED -> "失败"
                                    TaskStatus.CANCELLED -> "已取消"
                                },
                                accent = accent,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        ProgressLine(if (task.status == TaskStatus.RUNNING) task.progress else 100)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${task.toolName} · ${Fmt.dateTime(task.createdAt)}" +
                                    (task.finishedAt?.let { " → ${Fmt.dateTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (task.status == TaskStatus.RUNNING) {
                                GlassButton("取消", accent = MaterialTheme.colorScheme.error) { TaskManager.cancel(task.id) }
                            } else {
                                GlassButton("日志", icon = Icons.Filled.Refresh) { logTask = task }
                            }
                        }
                        task.error?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        val shown = logTask
        if (shown != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { logTask = null },
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(20.dp),
                ) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(
                            "日志 · ${shown.title}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                TaskManager.readLog(shown.logFile).takeLast(8000),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton("关闭") { logTask = null }
                            GlassButton("复制全部", icon = Icons.Filled.Refresh) {
                                val text = TaskManager.readLog(shown.logFile)
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("MirrorBox log", text))
                                logTask = null
                            }
                        }
                    }
                }
            }
        }
    }
}