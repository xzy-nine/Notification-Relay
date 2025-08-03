package com.xzyht.notifyrelay.ui.MainActivityscreen

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Checkbox

/**
 * 通知过滤软编码设置Pager
 */
@Composable
fun NotificationFilterPager(
    filterSelf: Boolean = true,
    filterOngoing: Boolean = true,
    filterNoTitleOrText: Boolean = true,
    filterImportanceNone: Boolean = true,
    filterMiPushGroupSummary: Boolean = true,
    filterSensitiveHidden: Boolean = true,
    onFilterSelfChange: (Boolean) -> Unit = {},
    onFilterOngoingChange: (Boolean) -> Unit = {},
    onFilterNoTitleOrTextChange: (Boolean) -> Unit = {},
    onFilterImportanceNoneChange: (Boolean) -> Unit = {},
    onFilterMiPushGroupSummaryChange: (Boolean) -> Unit = {},
    onFilterSensitiveHiddenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var newKeyword by remember { mutableStateOf("") }
    var keywordList by remember { mutableStateOf(
        com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.getForegroundKeywords(context).toList()
    ) }
    var enabledKeywords by remember { mutableStateOf(
        com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.getEnabledForegroundKeywords(context)
    ) }
    var deleteMode by remember { mutableStateOf(false) }

    fun refreshKeywords() {
        keywordList = com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.getForegroundKeywords(context).toList()
        enabledKeywords = com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.getEnabledForegroundKeywords(context)
    }

    MiuixTheme {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterSelf, onCheckedChange = onFilterSelfChange)
                Text("过滤本应用通知", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterOngoing, onCheckedChange = onFilterOngoingChange)
                Text("过滤持久化通知", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterNoTitleOrText, onCheckedChange = onFilterNoTitleOrTextChange)
                Text("过滤无标题或无内容", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterImportanceNone, onCheckedChange = onFilterImportanceNoneChange)
                Text("过滤优先级为无的通知", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterMiPushGroupSummary, onCheckedChange = onFilterMiPushGroupSummaryChange)
                Text("过滤mipush群组引导消息(新消息/你有一条新消息)", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = filterSensitiveHidden, onCheckedChange = onFilterSensitiveHiddenChange)
                Text("过滤敏感内容被隐藏的通知", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("文本关键词过滤(黑名单)：", Modifier.padding(bottom = 8.dp).weight(1f))
                Button(
                    onClick = { deleteMode = !deleteMode },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!deleteMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                        contentColor = if (!deleteMode) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(if (deleteMode) "完成" else "删除")
                }
            }

            // 获取内置关键词集合（与后端保持单例）
            val builtinKeywords: Set<String> = try {
                val clazz = com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter::class.java
                val field = clazz.getDeclaredField("builtinCustomKeywords")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                field.get(com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter) as? Set<String> ?: emptySet()
            } catch (e: Exception) { emptySet() }

            keywordList.forEach { keyword ->
                val enabled = enabledKeywords.contains(keyword)
                val isBuiltin = builtinKeywords.contains(keyword)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Button(
                        onClick = {
                            if (deleteMode && !isBuiltin) {
                                com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.removeForegroundKeyword(context, keyword)
                                refreshKeywords()
                            } else if (!deleteMode) {
                                com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.setKeywordEnabled(context, keyword, !enabled)
                                refreshKeywords()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                deleteMode && !isBuiltin -> MaterialTheme.colorScheme.error
                                enabled -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = when {
                                deleteMode && !isBuiltin -> MaterialTheme.colorScheme.onError
                                enabled -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(keyword)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    label = { Text("添加关键词") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val trimmed = newKeyword.trim()
                        if (trimmed.isNotEmpty() && !keywordList.contains(trimmed)) {
                            com.xzyht.notifyrelay.service.NotifyRelayNotificationListenerService.DefaultNotificationFilter.addForegroundKeyword(context, trimmed)
                            newKeyword = ""
                            refreshKeywords()
                        }
                    }
                ) {
                    Text("添加")
                }
            }
        }
    }
}
