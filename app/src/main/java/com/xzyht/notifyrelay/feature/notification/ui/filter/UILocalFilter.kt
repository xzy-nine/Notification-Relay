package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

        // 关键词相关状态
        var keywordList by remember { mutableStateOf(BackendLocalFilter.getForegroundKeywords(context).toList()) }
        var enabledKeywords by remember { mutableStateOf(BackendLocalFilter.getEnabledForegroundKeywords(context)) }
        var newKeyword by remember { mutableStateOf("") }
        var builtinKeywordsExpanded by remember { mutableStateOf(false) }
        var customKeywordsExpanded by remember { mutableStateOf(false) }
        
        // 包名过滤相关状态
        var packageFilterList by remember { mutableStateOf(BackendLocalFilter.getPackageFilterList(context).toList()) }
        var enabledPackages by remember { mutableStateOf(BackendLocalFilter.getEnabledPackageFilters(context)) }
        var showAppPickerDialog by remember { mutableStateOf(false) }
        var builtinPackagesExpanded by remember { mutableStateOf(false) }
        var customPackagesExpanded by remember { mutableStateOf(false) }
        
        val builtinKeywords = remember { BackendLocalFilter.getBuiltinKeywords() }
        val customKeywords = remember(keywordList, builtinKeywords) { 
            keywordList.filter { !builtinKeywords.contains(it) } 
        }

        val builtinPackages = remember { BackendLocalFilter.getDefaultPackageFilters() }
        val customPackages = remember(packageFilterList, builtinPackages) { 
            packageFilterList.filter { !builtinPackages.contains(it) } 
        }

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

        // 关键词过滤设置
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "文本关键词过滤(黑名单)",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    label = "新关键词",
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
                    textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurfaceContainerHighest),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        if (newKeyword.isNotBlank()) {
                            BackendLocalFilter.addForegroundKeyword(context, newKeyword.trim())
                            keywordList = BackendLocalFilter.getForegroundKeywords(context).toList()
                            enabledKeywords = BackendLocalFilter.getEnabledForegroundKeywords(context)
                            newKeyword = ""
                        }
                    },
                    enabled = newKeyword.isNotBlank(),
                    colors = if (newKeyword.isNotBlank()) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()
                ) {
                    Text("添加")
                }
            }
        }

        // 默认关键词
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "默认关键词 (${builtinKeywords.size})",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { builtinKeywordsExpanded = !builtinKeywordsExpanded }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Useful.More,
                        contentDescription = if (builtinKeywordsExpanded) "折叠" else "展开",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }

        if (builtinKeywordsExpanded) {
            builtinKeywords.forEach { keyword ->
                item {
                    val isEnabled = enabledKeywords.contains(keyword)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = keyword,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { enabled ->
                                BackendLocalFilter.setKeywordEnabled(context, keyword, enabled)
                                enabledKeywords = BackendLocalFilter.getEnabledForegroundKeywords(context)
                            }
                        )
                    }
                }
            }
        }

        // 自定义关键词
        if (customKeywords.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义关键词 (${customKeywords.size})",
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { customKeywordsExpanded = !customKeywordsExpanded }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Useful.More,
                            contentDescription = if (customKeywordsExpanded) "折叠" else "展开",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            if (customKeywordsExpanded) {
                customKeywords.forEach { keyword ->
                    item {
                        val isEnabled = enabledKeywords.contains(keyword)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = keyword,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { enabled ->
                                    BackendLocalFilter.setKeywordEnabled(context, keyword, enabled)
                                    enabledKeywords = BackendLocalFilter.getEnabledForegroundKeywords(context)
                                }
                            )
                            IconButton(
                                onClick = {
                                    BackendLocalFilter.removeForegroundKeyword(context, keyword)
                                    keywordList = BackendLocalFilter.getForegroundKeywords(context).toList()
                                    enabledKeywords = BackendLocalFilter.getEnabledForegroundKeywords(context)
                                }
                            ) {
                                Text("删除", color = MiuixTheme.colorScheme.onBackground)
                            }
                        }
                    }
                }
            }
        }

        // 包名过滤设置
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "应用包名过滤(黑名单)",
                    style = MiuixTheme.textStyles.title3,
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { showAppPickerDialog = true },
                    enabled = true,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("添加")
                }
            }
        }

        // 默认包名
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "默认包名 (${builtinPackages.size})",
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { builtinPackagesExpanded = !builtinPackagesExpanded }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Useful.More,
                        contentDescription = if (builtinPackagesExpanded) "折叠" else "展开",
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
            }
        }

        if (builtinPackagesExpanded) {
            builtinPackages.forEach { pkg ->
                item {
                    val isEnabled = enabledPackages.contains(pkg)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pkg,
                            color = MiuixTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { enabled ->
                                BackendLocalFilter.setPackageEnabled(context, pkg, enabled)
                                enabledPackages = BackendLocalFilter.getEnabledPackageFilters(context)
                            }
                        )
                    }
                }
            }
        }

        // 自定义包名
        if (customPackages.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "自定义包名 (${customPackages.size})",
                        color = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { customPackagesExpanded = !customPackagesExpanded }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Useful.More,
                            contentDescription = if (customPackagesExpanded) "折叠" else "展开",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            if (customPackagesExpanded) {
                customPackages.forEach { pkg ->
                    item {
                        val isEnabled = enabledPackages.contains(pkg)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pkg,
                                color = MiuixTheme.colorScheme.onBackground,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { enabled ->
                                    BackendLocalFilter.setPackageEnabled(context, pkg, enabled)
                                    enabledPackages = BackendLocalFilter.getEnabledPackageFilters(context)
                                }
                            )
                            IconButton(
                                onClick = {
                                    BackendLocalFilter.removePackageFilter(context, pkg)
                                    packageFilterList = BackendLocalFilter.getPackageFilterList(context).toList()
                                    enabledPackages = BackendLocalFilter.getEnabledPackageFilters(context)
                                }
                            ) {
                                Text("删除", color = MiuixTheme.colorScheme.onBackground)
                            }
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
            BackendLocalFilter.addPackageFilter(context, packageName)
            packageFilterList = BackendLocalFilter.getPackageFilterList(context).toList()
            enabledPackages = BackendLocalFilter.getEnabledPackageFilters(context)
        },
        title = "选择要过滤的应用"
    )
}
}
