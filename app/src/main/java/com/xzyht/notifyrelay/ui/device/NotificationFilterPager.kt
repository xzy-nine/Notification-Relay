package com.xzyht.notifyrelay.ui.device

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.TabRow
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
    onFilterSelfChange: (Boolean) -> Unit = {},
    onFilterOngoingChange: (Boolean) -> Unit = {},
    onFilterNoTitleOrTextChange: (Boolean) -> Unit = {},
    onFilterImportanceNoneChange: (Boolean) -> Unit = {},
    foregroundKeywords: List<String> = emptyList(),
    onAddKeyword: (String) -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {}
) {
    var newKeyword by remember { mutableStateOf("") }
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
            Spacer(Modifier.height(16.dp))
            Text("文本关键词过滤(黑名单)：", Modifier.padding(bottom = 8.dp))
            // 关键词列表
            foregroundKeywords.forEach { keyword ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text(keyword, Modifier.weight(1f))
                    Button(onClick = { onRemoveKeyword(keyword) }) {
                        Text("删除")
                    }
                }
            }
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
                        if (trimmed.isNotEmpty()) {
                            onAddKeyword(trimmed)
                            newKeyword = ""
                        }
                    }
                ) {
                    Text("添加")
                }
            }
        }
    }
}
