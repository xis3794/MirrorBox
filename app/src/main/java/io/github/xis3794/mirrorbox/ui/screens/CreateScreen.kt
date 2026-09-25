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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ops.EditOps
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard

@Composable
fun CreateScreen(nav: Navigator) {
    var name by remember { mutableStateOf("disk") }
    var sizeText by remember { mutableStateOf("10G") }
    var cluster by remember { mutableStateOf(65536L) }
    var preallocation by remember { mutableStateOf("off") }
    var useEngine by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val parsedSize = Fmt.parseSize(sizeText, 'G')

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "新建 qcow2",
            subtitle = "创建空白稀疏镜像，写入操作全部在本机完成",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("名称", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("disk") },
                )
                Spacer(Modifier.height(12.dp))
                Text("容量", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = sizeText,
                    onValueChange = { sizeText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("10G") },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1G", "8G", "16G", "64G").forEach { preset ->
                        GlassSelectChip(preset, sizeText == preset, { sizeText = preset })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = parsedSize?.let { "解析为 ${Fmt.size(it)}" } ?: "请输入合法容量，例如 10G / 512M",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (parsedSize != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("簇大小", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4096L to "4K", 65536L to "64K（推荐）", 262144L to "256K").forEach { (value, label) ->
                        GlassSelectChip(label, cluster == value, { cluster = value })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("预分配", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("off" to "关闭", "metadata" to "元数据", "falloc" to "falloc", "full" to "完全").forEach { (value, label) ->
                        GlassSelectChip(label, preallocation == value, { preallocation = value })
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("用内置引擎创建", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "不依赖 qemu-img，直接由 Kotlin 引擎写出 qcow2 v3 元数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useEngine, onCheckedChange = { useEngine = it })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                InfoRow("目标目录", AppPaths.images.absolutePath)
                InfoRow("文件名", "$name.qcow2")
            }

            message?.let {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            GlassButton(
                text = "创建镜像",
                icon = Icons.Filled.Check,
                enabled = parsedSize != null && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val size = parsedSize ?: return@GlassButton
                    val target = AppPaths.uniqueFile(AppPaths.images, name, ".qcow2")
                    if (useEngine) {
                        val ok = EditOps.createImage(target, size, clusterBits = clusterToBits(cluster))
                        message = if (ok) "已用内置引擎创建 ${target.name}" else "创建失败"
                    } else {
                        ImageOps.submitCreate(target, size, cluster, preallocation)
                        message = "已提交任务：创建 ${target.name}"
                    }
                },
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

internal fun clusterToBits(clusterSize: Long): Int {
    var bits = 9
    var value = 512L
    while (value < clusterSize && bits < 21) {
        value *= 2
        bits++
    }
    return bits
}