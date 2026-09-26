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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.xis3794.mirrorbox.core.AppPaths
import io.github.xis3794.mirrorbox.core.Fmt
import io.github.xis3794.mirrorbox.core.StorageGateway
import io.github.xis3794.mirrorbox.nav.Navigator
import io.github.xis3794.mirrorbox.ops.IsoOps
import io.github.xis3794.mirrorbox.ui.components.GlassSelectChip
import io.github.xis3794.mirrorbox.ui.components.InfoRow
import io.github.xis3794.mirrorbox.ui.components.ScreenHeader
import io.github.xis3794.mirrorbox.ui.glass.GlassButton
import io.github.xis3794.mirrorbox.ui.glass.GlassCard
import io.github.xis3794.mirrorbox.ui.glass.GlassChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun IsoStudioScreen(nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sourceDir = remember { File(AppPaths.externalRoot(), "iso-src").apply { mkdirs() } }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var label by remember { mutableStateOf("MIRRORBOX") }
    var joliet by remember { mutableStateOf(true) }
    var rockRidge by remember { mutableStateOf(true) }
    var bootImage by remember { mutableStateOf("") }
    var efiImage by remember { mutableStateOf("") }
    var hybridIso by remember { mutableStateOf(true) }
    var outputName by remember { mutableStateOf("mirrorbox") }
    var isos by remember { mutableStateOf(emptyList<StorageGateway.ImageFile>()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val src = withContext(Dispatchers.IO) {
                sourceDir.listFiles()?.toList()?.sortedBy { it.name } ?: emptyList()
            }
            val list = withContext(Dispatchers.IO) {
                StorageGateway.listImages().filter { it.extension == "ISO" }
            }
            files = src
            isos = list
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri -> StorageGateway.importFromUri(context, uri, sourceDir) }
                }
                message = "已导入 ${uris.size} 个文件到源目录"
                refresh()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "ISO 工作室",
            subtitle = "用 xorriso 制作 / 提取 / 编辑 ISO，可配置 BIOS 与 EFI 引导",
            onBack = { nav.pop() },
        )

        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("源目录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    sourceDir.absolutePath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("导入文件", icon = Icons.Filled.Add) { importer.launch(arrayOf("*/*")) }
                    GlassButton("刷新", icon = Icons.Filled.PlayArrow) { refresh() }
                }
                Spacer(Modifier.height(10.dp))
                if (files.isEmpty()) {
                    Text(
                        "目录为空：把要打包的文件放进来（或用文件管理器直接拷贝到该路径）。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    files.take(12).forEach { file ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            GlassChip(Fmt.size(file.length()))
                        }
                    }
                    if (files.size > 12) {
                        Text(
                            "… 共 ${files.size} 个文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = outputName,
                    onValueChange = { outputName = it },
                    singleLine = true,
                    label = { Text("ISO 文件名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    label = { Text("卷标 (Volume Label)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Joliet 长文件名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = joliet, onCheckedChange = { joliet = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Rock Ridge (Unix 权限)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = rockRidge, onCheckedChange = { rockRidge = it })
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Text("引导配置（可选）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "填写源目录中的文件名即可生成可引导 ISO：BIOS (El Torito) 与 EFI (efi.img)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bootImage,
                    onValueChange = { bootImage = it },
                    singleLine = true,
                    label = { Text("BIOS 引导镜像（如 isolinux.bin）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = efiImage,
                    onValueChange = { efiImage = it },
                    singleLine = true,
                    label = { Text("EFI 引导镜像（如 efi.img）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "内置引导（开箱即用）：把 syslinux 的 isolinux.bin + ldlinux.c32 放进源目录并生成 isolinux.cfg。" +
                        "勾选「U 盘可启动」会额外用内置 isohybrid MBR 写入镜像头。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("装入内置 ISOLINUX（BIOS）") {
                        message = IsoOps.installBuiltInBiosBoot(context, sourceDir)
                        bootImage = "isolinux.bin"
                        refresh()
                    }
                    GlassSelectChip("U 盘可启动", hybridIso, { hybridIso = !hybridIso })
                }
            }

            message?.let {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }

            GlassButton(
                text = "制作 ISO",
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
                enabled = outputName.isNotBlank(),
                onClick = {
                    val target = AppPaths.uniqueFile(AppPaths.iso, outputName, ".iso")
                    IsoOps.submitCreate(
                        sourceDir = sourceDir,
                        output = target,
                        options = IsoOps.IsoOptions(
                            volumeLabel = label,
                            joliet = joliet,
                            rockRidge = rockRidge,
                            bootImage = bootImage.ifBlank { null }?.let { File(sourceDir, it) },
                            efiBootImage = efiImage.ifBlank { null }?.let { File(sourceDir, it) },
                            hybridMbr = if (hybridIso && bootImage.isNotBlank()) IsoOps.builtInHybridMbr(context) else null,
                        ),
                    )
                    message = "已提交任务：${target.name}"
                },
            )

            GlassCard(Modifier.fillMaxWidth()) {
                Text("已有 ISO", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                if (isos.isEmpty()) {
                    Text(
                        "还没有 ISO 文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    isos.forEach { iso ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(iso.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(iso.prettySize, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            GlassButton("提取", icon = Icons.Filled.PlayArrow) {
                                val outDir = File(AppPaths.iso, iso.name.substringBeforeLast('.'))
                                outDir.mkdirs()
                                IsoOps.submitExtract(iso.file, outDir)
                                message = "已提交任务：提取 ${iso.name}"
                            }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                InfoRow("输出目录", AppPaths.iso.absolutePath)
                InfoRow("临时目录", AppPaths.tmp.absolutePath)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}