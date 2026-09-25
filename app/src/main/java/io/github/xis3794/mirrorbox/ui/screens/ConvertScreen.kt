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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.StorageGateway
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ops.ImageOps
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ConvertScreen(nav: Navigator) {
    var sources by remember { mutableStateOf(emptyList<StorageGateway.ImageFile>()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var targetFormat by remember { mutableStateOf("qcow2") }
    var compress by remember { mutableStateOf(false) }
    var vmdkSubformat by remember { mutableStateOf("monolithicSparse") }
    var vhdxSubformat by remember { mutableStateOf("dynamic") }
    var outputName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sources = withContext(Dispatchers.IO) { StorageGateway.listImages() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "格式转换",
            subtitle = "qemu-img convert · 支持 vmdk / vhdx / vdi / raw / qcow2 等",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Text("源镜像", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (sources.isEmpty()) {
                    Text(
                        "工作区里没有镜像",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(sources) { item ->
                                val isSelected = selected?.absolutePath == item.file.absolutePath
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                    onClick = {
                                        selected = item.file
                                        if (outputName.isBlank()) outputName = item.file.nameWithoutExtension
                                    },
                                    contentPadding = PaddingValues(10.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                item.prettySize,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        GlassChip(if (isSelected) "已选择" else item.extension)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("目标格式", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageOps.OUTPUT_FORMATS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { format ->
                                GlassSelectChip(format.id, targetFormat == format.id, { targetFormat = format.id })
                            }
                        }
                    }
                }

                if (targetFormat == "vmdk") {
                    Spacer(Modifier.height(12.dp))
                    Text("vmdk 子格式", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "monolithicSparse" to "monolithicSparse",
                            "streamOptimized" to "streamOptimized",
                            "twoGbMaxExtentSparse" to "2GB 分卷",
                        ).forEach { (value, label) ->
                            GlassSelectChip(label, vmdkSubformat == value, { vmdkSubformat = value })
                        }
                    }
                }

                if (targetFormat == "vhdx") {
                    Spacer(Modifier.height(12.dp))
                    Text("vhdx 子格式", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("dynamic" to "动态扩展", "fixed" to "固定大小").forEach { (value, label) ->
                            GlassSelectChip(label, vhdxSubformat == value, { vhdxSubformat = value })
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("压缩未使用数据 (-c)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "仅对 qcow2 目标生效，可显著减小体积",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = compress, onCheckedChange = { compress = it })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    singleLine = true,
                    label = { Text("输出文件名（不含扩展名）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val source = selected
                InfoRow("输出目录", AppPaths.images.absolutePath)
                InfoRow("输出文件", "${outputName.ifBlank { "output" }}.$targetFormat")
                if (source != null) InfoRow("输入", source.name)
            }

            message?.let {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            GlassButton(
                text = "开始转换",
                icon = Icons.Filled.PlayArrow,
                enabled = selected != null && outputName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val source = selected ?: return@GlassButton
                    val target = AppPaths.uniqueFile(AppPaths.images, outputName, ".$targetFormat")
                    ImageOps.submitConvert(
                        source = source,
                        target = target,
                        options = ImageOps.ConvertOptions(
                            targetFormat = targetFormat,
                            compress = compress,
                            vmdkSubformat = vmdkSubformat,
                            vhdxSubformat = vhdxSubformat,
                        ),
                    )
                    message = "已提交任务：${source.name} → ${target.name}"
                },
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}