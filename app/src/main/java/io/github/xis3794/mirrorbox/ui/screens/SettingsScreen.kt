package io.github.xis3794.mirrorbox.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.NativeTools
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.nav.Screen
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.components.SectionTitle
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard

@Composable
fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(Prefs.themeMode) }
    var reduceEffects by remember { mutableStateOf(Prefs.reduceEffects) }
    var autoCheck by remember { mutableStateOf(Prefs.autoCheckAfterWrite) }
    var confirmDestructive by remember { mutableStateOf(Prefs.confirmDestructive) }
    var glass by remember { mutableStateOf(Prefs.glassIntensity.toFloat()) }
    var allFilesAccess by remember { mutableStateOf(AppPaths.hasAllFilesAccess()) }
    var toolDir by remember { mutableStateOf(Prefs.externalToolDir.orEmpty()) }
    var version by remember { mutableStateOf("") }

    LaunchedEffectOnce {
        version = withContextIo {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
            }.getOrDefault("0.1.0")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFilesAccess = AppPaths.hasAllFilesAccess()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(title = "设置", subtitle = "外观、存储与工具链")

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("外观", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (mode, label) ->
                        GlassSelectChip(label, themeMode == mode, {
                            themeMode = mode
                            Prefs.themeMode = mode
                        })
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "玻璃强度 ${glass.toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = glass,
                    onValueChange = {
                        glass = it
                        Prefs.glassIntensity = it.toInt()
                    },
                    valueRange = 35f..100f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("性能模式", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "纯色卡片 + 无背景光晕 + 无过渡动画，最流畅省电；低内存设备已自动开启",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = reduceEffects, onCheckedChange = {
                        reduceEffects = it
                        Prefs.performanceMode = it
                    })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("操作安全", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("写入后自动 qemu-img check", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "每次转换 / 编辑 / 回写完成后独立校验镜像",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoCheck, onCheckedChange = {
                        autoCheck = it
                        Prefs.autoCheckAfterWrite = it
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("破坏性操作二次确认", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "删除、清零、收缩容量前弹出确认",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = confirmDestructive, onCheckedChange = {
                        confirmDestructive = it
                        Prefs.confirmDestructive = it
                    })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("存储", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                InfoRow("工作区", AppPaths.root.absolutePath)
                InfoRow("外部目录", AppPaths.externalRoot().absolutePath)
                InfoRow("可用空间", Fmt.size(AppPaths.sizeOfTree(AppPaths.root).let { 0L }))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (allFilesAccess) "已开启「所有文件访问」" else "沙盒模式（SAF 导入导出）",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "开启后可直接原位操作 /sdcard 上的大镜像，避免复制数 GB 文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GlassButton(
                        text = if (allFilesAccess) "已开启" else "去授权",
                        icon = Icons.Filled.Info,
                        enabled = !allFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                runCatching { permissionLauncher.launch(intent) }
                            }
                        },
                    )
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("原生工具链", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "工具以 jniLibs 形式随 APK 分发，并从应用的 nativeLibraryDir 执行；也可以指定一个外部目录（例如 Termux 安装的工具）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = toolDir,
                    onValueChange = {
                        toolDir = it
                        Prefs.externalToolDir = it.ifBlank { null }
                    },
                    singleLine = true,
                    label = { Text("外部工具目录（可留空）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("工具自检", icon = Icons.Filled.Info) { nav.push(Screen.SelfCheck()) }
                    GlassButton("清空工具缓存") {
                        NativeTools.clearCache()
                        nav.push(Screen.SelfCheck())
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("关于", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                InfoRow("版本", version.ifBlank { "—" })
                InfoRow("架构", Build.SUPPORTED_ABIS.joinToString(", "))
                InfoRow("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("许可证", "GPL-3.0（内置 QEMU / xorriso / e2fsprogs 等）")
                Spacer(Modifier.height(6.dp))
                Text(
                    "镜像匣完全离线运行，不申请网络权限、不收集任何数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(200.dp))
        }
    }
}

@Composable
private fun LaunchedEffectOnce(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

private suspend fun <T> withContextIo(block: () -> T): T =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }