package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AppPickerDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.More

/**
 * UI本机通知过滤设置
 * 提供本机通知过滤的UI界面
 */
@Composable
fun UILocalFilter(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = if (isDarkTheme) top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()

    MiuixTheme(colors = colors) {
        // 状态管理
        var filterSelf by remember { mutableStateOf(BackendLocalFilter.filterSelf) }
        var filterOngoing by remember { mutableStateOf(BackendLocalFilter.filterOngoing) }
        var filterNoTitleOrText by remember { mutableStateOf(BackendLocalFilter.filterNoTitleOrText) }
        var filterImportanceNone by remember { mutableStateOf(BackendLocalFilter.filterImportanceNone) }

        // 过滤条目相关状态（统一管理关键词+包名）
        var allEntries by remember { mutableStateOf(BackendLocalFilter.getFilterEntries(context).toList()) }
        var enabledEntries by remember { mutableStateOf(BackendLocalFilter.getEnabledFilterEntries(context)) }
        var newKeyword by remember { mutableStateOf("") }
        var newPackage by remember { mutableStateOf("") }
        var newPackageIcon by remember { mutableStateOf<ImageBitmap?>(null) }
        val coroutineScope = rememberCoroutineScope()
        val pm = context.packageManager
        val defaultAppIconBitmap = remember {
            val drawable = try { pm.getDefaultActivityIcon() } catch (_: Exception) { null }
            if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap.asImageBitmap()
            } else {
                // convert other drawables to a small placeholder bitmap
                val width = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: 48
                val height = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: 48
                val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable?.setBounds(0, 0, width, height)
                drawable?.draw(canvas)
                bmp.asImageBitmap()
            }
        }
        var entryIcons by remember { mutableStateOf<Map<BackendLocalFilter.FilterEntry, ImageBitmap?>>(emptyMap()) }
    var showAppPickerDialog by remember { mutableStateOf(false) }
    var builtinDefaultsExpanded by remember { mutableStateOf(false) }

        val builtinKeywords = remember { BackendLocalFilter.getBuiltinKeywords() }
        val builtinPackages = remember { BackendLocalFilter.getDefaultPackageFilters() }
        val builtinDefaultEntries = remember(allEntries, builtinKeywords, builtinPackages) {
            allEntries.filter { entry -> (entry.keyword.isNotBlank() && builtinKeywords.contains(entry.keyword)) || (entry.packageName.isNotBlank() && builtinPackages.contains(entry.packageName)) }
        }

        // 分组：内置关键词条目、内置包名条目、自定义条目
        val builtinKeywordEntries = remember(allEntries, builtinKeywords) { allEntries.filter { it.packageName.isBlank() && builtinKeywords.contains(it.keyword) } }
        val builtinPackageEntries = remember(allEntries, builtinPackages) { allEntries.filter { it.keyword.isBlank() && builtinPackages.contains(it.packageName) } }
        val customEntries = remember(allEntries, builtinKeywordEntries, builtinPackageEntries) { allEntries.filter { !builtinKeywordEntries.contains(it) && !builtinPackageEntries.contains(it) } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "本机通知过滤设置",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onBackground
            )
        }

        // 过滤本应用通知
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤本应用通知",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterSelf,
                    onCheckedChange = {
                        filterSelf = it
                        BackendLocalFilter.filterSelf = it
                    }
                )
            }
        }

        // 过滤持久化通知
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤持久化通知",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterOngoing,
                    onCheckedChange = {
                        filterOngoing = it
                        BackendLocalFilter.filterOngoing = it
                    }
                )
            }
        }

        // 过滤无标题或无内容
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤无标题或无内容",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterNoTitleOrText,
                    onCheckedChange = {
                        filterNoTitleOrText = it
                        BackendLocalFilter.filterNoTitleOrText = it
                    }
                )
            }
        }

        // 过滤优先级为无的通知
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤优先级为无的通知",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterImportanceNone,
                    onCheckedChange = {
                        filterImportanceNone = it
                        BackendLocalFilter.filterImportanceNone = it
                    }
                )
            }
        }

        // 统一过滤条目设置：标题行
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤条目(文本 + 应用包名)",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 文本输入独占一行，避免被按钮压缩
        item {
            TextField(
                value = newKeyword,
                onValueChange = { newKeyword = it },
                label = "关键词(可空)",
                backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurfaceContainerHighest),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        // 按钮行：选择应用与添加
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showAppPickerDialog = true },
                    colors = ButtonDefaults.buttonColors()
                ) {
                    if (newPackage.isBlank()) {
                        Text("选择应用(可空)")
                    } else {
                        // 显示真实应用图标（如果加载到的话）
                        newPackageIcon?.let { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "已选应用图标",
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("已选")
                    }
                }

                Button(
                    onClick = {
                        BackendLocalFilter.addFilterEntry(context, newKeyword.trim(), newPackage.trim())
                        allEntries = BackendLocalFilter.getFilterEntries(context).toList()
                        enabledEntries = BackendLocalFilter.getEnabledFilterEntries(context)
                        newKeyword = ""
                        newPackage = ""
                        newPackageIcon = null
                    },
                    enabled = newKeyword.isNotBlank() || newPackage.isNotBlank(),
                    colors = if (newKeyword.isNotBlank() || newPackage.isNotBlank()) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                ) {
                    Text("添加")
                }
            }
        }

        // 默认黑名单（内置文本关键词 + 内置包名）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "默认黑名单 (${builtinDefaultEntries.size})",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { builtinDefaultsExpanded = !builtinDefaultsExpanded }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Useful.More,
                        contentDescription = if (builtinDefaultsExpanded) "折叠" else "展开",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }

        if (builtinDefaultsExpanded) {
            builtinDefaultEntries.forEach { entry ->
                item {
                    val isEnabled = enabledEntries.contains(entry)
                    LaunchedEffect(entry.packageName) {
                        if (entry.packageName.isNotBlank() && entryIcons[entry] == null) {
                            try {
                                val bmp = com.xzyht.notifyrelay.core.repository.AppRepository.getAppIconAsync(context, entry.packageName)
                                entryIcons = entryIcons + (entry to (bmp?.asImageBitmap() ?: defaultAppIconBitmap))
                            } catch (_: Exception) {
                                entryIcons = entryIcons + (entry to defaultAppIconBitmap)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (entry.keyword.isNotBlank()) {
                                Text(
                                    text = entry.keyword,
                                    color = MiuixTheme.colorScheme.onBackground
                                )
                                if (entry.packageName.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    entryIcons[entry]?.let { bmp ->
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = "应用图标",
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = entry.packageName,
                                        color = MiuixTheme.colorScheme.onBackground
                                    )
                                }
                            } else if (entry.packageName.isNotBlank()) {
                                entryIcons[entry]?.let { bmp ->
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "应用图标",
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = entry.packageName,
                                    color = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { enabled ->
                                BackendLocalFilter.setFilterEntryEnabled(context, entry, enabled)
                                enabledEntries = BackendLocalFilter.getEnabledFilterEntries(context)
                            }
                        )
                    }
                }
            }
        }

        // 自定义条目
        if (customEntries.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义条目 (${customEntries.size})",
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            customEntries.forEach { entry ->
                item {
                    val isEnabled = enabledEntries.contains(entry)
                    LaunchedEffect(entry.packageName) {
                        if (entry.packageName.isNotBlank() && entryIcons[entry] == null) {
                            try {
                                val bmp = com.xzyht.notifyrelay.core.repository.AppRepository.getAppIconAsync(context, entry.packageName)
                                entryIcons = entryIcons + (entry to (bmp?.asImageBitmap() ?: defaultAppIconBitmap))
                            } catch (_: Exception) {
                                entryIcons = entryIcons + (entry to defaultAppIconBitmap)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (entry.keyword.isNotBlank()) {
                                Text(
                                    text = entry.keyword,
                                    color = MiuixTheme.colorScheme.onBackground
                                )
                                if (entry.packageName.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    entryIcons[entry]?.let { bmp ->
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = "应用图标",
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = entry.packageName,
                                        color = MiuixTheme.colorScheme.onBackground
                                    )
                                }
                            } else if (entry.packageName.isNotBlank()) {
                                entryIcons[entry]?.let { bmp ->
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = "应用图标",
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = entry.packageName,
                                    color = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { enabled ->
                                BackendLocalFilter.setFilterEntryEnabled(context, entry, enabled)
                                enabledEntries = BackendLocalFilter.getEnabledFilterEntries(context)
                            }
                        )
                        IconButton(
                            onClick = {
                                BackendLocalFilter.removeFilterEntry(context, entry.keyword, entry.packageName)
                                allEntries = BackendLocalFilter.getFilterEntries(context).toList()
                                enabledEntries = BackendLocalFilter.getEnabledFilterEntries(context)
                            }
                        ) {
                            Text("删除", color = MiuixTheme.colorScheme.onBackground)
                        }
                    }
                }
            }
        }
    }

    // 应用选择弹窗
        AppPickerDialog(
        visible = showAppPickerDialog,
        onDismiss = { showAppPickerDialog = false },
        onAppSelected = { packageName ->
            newPackage = packageName
            // 先使用占位图，异步加载真实图标以避免阻塞主线程
            newPackageIcon = defaultAppIconBitmap
            coroutineScope.launch {
                try {
                    val bmp = com.xzyht.notifyrelay.core.repository.AppRepository.getAppIconAsync(context, packageName)
                    newPackageIcon = bmp?.asImageBitmap() ?: defaultAppIconBitmap
                } catch (_: Exception) {
                    newPackageIcon = defaultAppIconBitmap
                }
            }
        },
        title = "选择要过滤的应用"
    )
}
}
