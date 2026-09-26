package io.github.xis3794.mirrorbox

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import io.github.xis3794.mirrorbox.core.TaskManager
import io.github.xis3794.mirrorbox.core.TaskStatus
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.AppBackground
import io.github.xis3794.mirrorbox.ui.glass.FloatingGlassBar
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassFab
import io.github.xis3794.mirrorbox.ui.glass.GlassSurface
import io.github.xis3794.mirrorbox.ui.glass.NavDestination
import io.github.xis3794.mirrorbox.ui.icons.MbIcons
import io.github.xis3794.mirrorbox.ui.screens.ConvertScreen
import io.github.xis3794.mirrorbox.ui.screens.CreateScreen
import io.github.xis3794.mirrorbox.ui.screens.EditorScreen
import io.github.xis3794.mirrorbox.ui.screens.HomeScreen
import io.github.xis3794.mirrorbox.ui.screens.InspectorScreen
import io.github.xis3794.mirrorbox.ui.screens.IsoStudioScreen
import io.github.xis3794.mirrorbox.ui.screens.LibraryScreen
import io.github.xis3794.mirrorbox.ui.screens.PartitionEditorScreen
import io.github.xis3794.mirrorbox.ui.screens.SelfCheckScreen
import io.github.xis3794.mirrorbox.ui.screens.SettingsScreen
import io.github.xis3794.mirrorbox.ui.screens.TasksScreen
import io.github.xis3794.mirrorbox.ui.screens.ToolsScreen
import io.github.xis3794.mirrorbox.ui.screens.WimReleaseScreen

@Composable
fun MirrorBoxApp() {
    val nav = remember { Navigator(Screen.Home) }
    val tasks by TaskManager.tasks.collectAsState()
    val runningTasks = tasks.count { it.status == TaskStatus.RUNNING }
    var showQuickActions by remember { mutableStateOf(false) }

    val tabs = listOf(
        NavDestination("首页", Icons.Filled.Home),
        NavDestination("镜像", MbIcons.Disk),
        NavDestination("任务", MbIcons.Terminal),
        NavDestination("设置", Icons.Filled.Settings),
    )
    val tabScreens = listOf(Screen.Home, Screen.Library, Screen.Tasks, Screen.Settings)
    val current = nav.current
    val isTabScreen = tabScreens.any { it == current }
    val selectedTab = tabScreens.indexOfFirst { it == current }.coerceAtLeast(0)

    AppBackground {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            AnimatedContent(targetState = current, label = "screen") { screen ->
                when (screen) {
                    is Screen.Home -> HomeScreen(nav)
                    is Screen.Library -> LibraryScreen(nav)
                    is Screen.Tools -> ToolsScreen(nav)
                    is Screen.Tasks -> TasksScreen(nav)
                    is Screen.Settings -> SettingsScreen(nav)
                    is Screen.Create -> CreateScreen(nav)
                    is Screen.Convert -> ConvertScreen(nav)
                    is Screen.IsoStudio -> IsoStudioScreen(nav)
                    is Screen.Inspector -> InspectorScreen(nav, screen.path)
                    is Screen.Editor -> EditorScreen(nav, screen.path)
                    is Screen.Partitions -> PartitionEditorScreen(nav, screen.path)
                    is Screen.WimRelease -> WimReleaseScreen(nav, screen.path)
                    is Screen.SelfCheck -> SelfCheckScreen(nav)
                }
            }

            if (isTabScreen) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        FloatingGlassBar(
                            destinations = tabs,
                            selectedIndex = selectedTab,
                            onSelect = { index ->
                                val target = tabScreens[index]
                                if (target == current) return@FloatingGlassBar
                                if (nav.canGoBack && tabScreens.any { it == nav.stack.first() }) {
                                    nav.resetTo(target)
                                } else {
                                    nav.resetTo(target)
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        GlassFab(
                            icon = Icons.Filled.Add,
                            onClick = { showQuickActions = true },
                            contentDescription = "创建",
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 0.dp),
                        )
                    }
                    if (runningTasks > 0) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GlassSurface(
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "$runningTasks 个任务运行中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (showQuickActions) {
                QuickActionOverlay(
                    onDismiss = { showQuickActions = false },
                    onAction = { screen ->
                        showQuickActions = false
                        nav.push(screen)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickActionOverlay(onDismiss: () -> Unit, onAction: (Screen) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader(title = "快速创建", subtitle = "选择要执行的操作")
            QuickActionItem("新建 qcow2 镜像", "创建空白磁盘，可选簇大小与预分配") { onAction(Screen.Create) }
            QuickActionItem("格式转换", "qcow2 ↔ vmdk / vhdx / vdi / raw …") { onAction(Screen.Convert) }
            QuickActionItem("制作 ISO", "从文件夹生成 ISO，可配置引导") { onAction(Screen.IsoStudio) }
            QuickActionItem("释放 WIM", "DISM++ 式展开 Windows 镜像，可直接写入分区") { onAction(Screen.WimRelease()) }
            QuickActionItem("导入镜像文件", "从系统文件选择器导入到工作区") { onAction(Screen.Library) }
        }
    }
}

@Composable
private fun QuickActionItem(title: String, subtitle: String, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MbIcons.Disk, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}