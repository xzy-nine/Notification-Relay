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
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Button
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
        
        val builtinKeywords = remember { BackendLocalFilter.getBuiltinKeywords() }
        val customKeywords = remember(keywordList, builtinKeywords) { 
            keywordList.filter { !builtinKeywords.contains(it) } 
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
            Text(
                text = "文本关键词过滤(黑名单)：",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // 默认关键词（折叠显示）
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

        // 默认关键词列表（可折叠）
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
                        // 默认关键词不显示删除按钮
                    }
                }
            }
        }

        // 自定义关键词设置
        item {
            Text(
                text = "自定义关键词：",
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // 添加新关键词
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    }
                ) {
                    Text("添加", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }

        // 自定义关键词列表
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
}
