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

/**
 * UI本机通知过滤设置
 * 提供本机通知过滤的UI界面
 */
@Composable
fun UILocalFilter(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 状态管理
    var filterSelf by remember { mutableStateOf(BackendLocalFilter.filterSelf) }
    var filterOngoing by remember { mutableStateOf(BackendLocalFilter.filterOngoing) }
    var filterNoTitleOrText by remember { mutableStateOf(BackendLocalFilter.filterNoTitleOrText) }
    var filterImportanceNone by remember { mutableStateOf(BackendLocalFilter.filterImportanceNone) }
    var filterMiPushGroupSummary by remember { mutableStateOf(BackendLocalFilter.filterMiPushGroupSummary) }
    var filterSensitiveHidden by remember { mutableStateOf(BackendLocalFilter.filterSensitiveHidden) }

    // 关键词相关状态
    var keywordList by remember { mutableStateOf(BackendLocalFilter.getForegroundKeywords(context).toList()) }
    var enabledKeywords by remember { mutableStateOf(BackendLocalFilter.getEnabledForegroundKeywords(context)) }
    var newKeyword by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "本机通知过滤设置",
                style = MaterialTheme.typography.headlineSmall
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

        // 过滤mipush群组引导消息
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤mipush群组引导消息(新消息/你有一条新消息)",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterMiPushGroupSummary,
                    onCheckedChange = {
                        filterMiPushGroupSummary = it
                        BackendLocalFilter.filterMiPushGroupSummary = it
                    }
                )
            }
        }

        // 过滤敏感内容被隐藏的通知
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "过滤敏感内容被隐藏的通知",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = filterSensitiveHidden,
                    onCheckedChange = {
                        filterSensitiveHidden = it
                        BackendLocalFilter.filterSensitiveHidden = it
                    }
                )
            }
        }

        // 关键词过滤设置
        item {
            Text(
                text = "文本关键词过滤(黑名单)：",
                style = MaterialTheme.typography.titleMedium,
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
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    label = { Text("新关键词") },
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
                    Text("添加")
                }
            }
        }

        // 关键词列表
        items(keywordList.size) { index ->
            val keyword = keywordList[index]
            val isEnabled = enabledKeywords.contains(keyword)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = keyword,
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
                    Text("删除")
                }
            }
        }
    }
}
