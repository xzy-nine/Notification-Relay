package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig

/**
 * UI接收通知过滤设置
 * 提供接收远程通知过滤的UI界面
 */
@Composable
fun UIRemoteFilter(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // 状态管理
    var enablePackageGroupMapping by remember { mutableStateOf(RemoteFilterConfig.enablePackageGroupMapping) }
    var enableDeduplication by remember { mutableStateOf(RemoteFilterConfig.enableDeduplication) }
    var enablePeerMode by remember { mutableStateOf(RemoteFilterConfig.enablePeerMode) }
    var filterMode by remember { mutableStateOf(RemoteFilterConfig.filterMode) }

    // 包名组状态
    var defaultGroupEnabled by remember { mutableStateOf(RemoteFilterConfig.defaultGroupEnabled.toList()) }
    var customPackageGroups by remember { mutableStateOf(RemoteFilterConfig.customPackageGroups.toList()) }
    var customGroupEnabled by remember { mutableStateOf(RemoteFilterConfig.customGroupEnabled.toList()) }

    // 黑白名单状态
    var filterList by remember { mutableStateOf(RemoteFilterConfig.filterList.toList()) }
    var newFilterPkg by remember { mutableStateOf("") }
    var newFilterKeyword by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "接收通知过滤设置",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        // 包名等价映射开关
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启用包名等价映射",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enablePackageGroupMapping,
                    onCheckedChange = {
                        enablePackageGroupMapping = it
                        RemoteFilterConfig.enablePackageGroupMapping = it
                    }
                )
            }
        }

        // 默认包名等价组
        if (enablePackageGroupMapping) {
            item {
                Text(
                    text = "默认包名等价组：",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(RemoteFilterConfig.defaultPackageGroups.size) { index ->
                val group = RemoteFilterConfig.defaultPackageGroups[index]
                val isEnabled = defaultGroupEnabled.getOrNull(index) ?: true

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.joinToString(" | "),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { enabled ->
                            val newList = defaultGroupEnabled.toMutableList()
                            if (index < newList.size) {
                                newList[index] = enabled
                            } else {
                                newList.add(enabled)
                            }
                            defaultGroupEnabled = newList
                            RemoteFilterConfig.defaultGroupEnabled = newList.toMutableList()
                        }
                    )
                }
            }
        }

        // 去重开关
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "启用去重",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enableDeduplication,
                    onCheckedChange = {
                        enableDeduplication = it
                        RemoteFilterConfig.enableDeduplication = it
                    }
                )
            }
        }

        // 对等模式开关
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "对等模式（仅转发本机已安装应用的通知）",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enablePeerMode,
                    onCheckedChange = {
                        enablePeerMode = it
                        RemoteFilterConfig.enablePeerMode = it
                    }
                )
            }
        }

        // 黑白名单模式选择
        item {
            Text(
                text = "黑白名单模式：",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = filterMode == mode.value,
                            onClick = {
                                filterMode = mode.value
                                RemoteFilterConfig.filterMode = mode.value
                            }
                        )
                        Text(text = mode.displayName)
                    }
                }
            }
        }

        // 黑白名单内容
        if (filterMode == "black" || filterMode == "white") {
            item {
                Text(
                    text = "${if (filterMode == "black") "黑名单" else "白名单"}内容：",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // 添加新过滤项
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newFilterPkg,
                        onValueChange = { newFilterPkg = it },
                        label = { Text("包名") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newFilterKeyword,
                        onValueChange = { newFilterKeyword = it },
                        label = { Text("关键词(可选)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newFilterPkg.isNotBlank()) {
                                val newList = filterList.toMutableList()
                                newList.add(newFilterPkg.trim() to newFilterKeyword.takeIf { it.isNotBlank() })
                                filterList = newList
                                RemoteFilterConfig.filterList = newList
                                newFilterPkg = ""
                                newFilterKeyword = ""
                            }
                        }
                    ) {
                        Text("添加")
                    }
                }
            }

            // 过滤列表
            items(filterList.size) { index ->
                val (pkg, keyword) = filterList[index]

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$pkg${keyword?.let { " ($it)" } ?: ""}",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val newList = filterList.toMutableList()
                            newList.removeAt(index)
                            filterList = newList
                            RemoteFilterConfig.filterList = newList
                        }
                    ) {
                        Text("删除")
                    }
                }
            }
        }
    }
}

/**
 * 过滤模式枚举
 */
enum class FilterMode(val value: String, val displayName: String) {
    NONE("none", "无"),
    BLACK("black", "黑名单"),
    WHITE("white", "白名单"),
    PEER("peer", "对等")
}
