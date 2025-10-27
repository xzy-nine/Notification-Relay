package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

import com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AddKeywordDialog
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AppPickerDialog
import com.xzyht.notifyrelay.core.repository.AppRepository
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
/**
 * 将 DeviceForwardFragment 中原有的远程过滤内联实现移动到这里：
 * 该组件负责读取/写入 RemoteFilterConfig 并提供完整的远程过滤 UI
 */
@Composable
fun UIRemoteFilter() {
    val context = LocalContext.current

    // 确保 RemoteFilterConfig 已加载
    if (!RemoteFilterConfig.isLoaded) {
        RemoteFilterConfig.load(context)
        RemoteFilterConfig.isLoaded = true
    }

    // 前端状态 - 直接从 RemoteFilterConfig 初始化
    var filterMode by remember { mutableStateOf(RemoteFilterConfig.filterMode) }
    var enableDedup by remember { mutableStateOf(RemoteFilterConfig.enableDeduplication) }
    var enablePackageGroupMapping by remember { mutableStateOf(RemoteFilterConfig.enablePackageGroupMapping) }
    var allGroups by remember { mutableStateOf<List<MutableList<String>>>(
        (RemoteFilterConfig.defaultPackageGroups.map { it.toMutableList() } +
                RemoteFilterConfig.customPackageGroups.map { it.toMutableList() }).toMutableList()
    ) }
    var allGroupEnabled by remember { mutableStateOf<List<Boolean>>(
        (RemoteFilterConfig.defaultGroupEnabled + RemoteFilterConfig.customGroupEnabled).toMutableList()
    ) }
    var filterListText by remember { mutableStateOf(
        RemoteFilterConfig.filterList.joinToString("\n") { it.first + (it.second?.let { k-> ","+k } ?: "") }
    ) }
    var enableLockScreenOnly by remember { mutableStateOf(RemoteFilterConfig.enableLockScreenOnly) }

    var showAppPickerForGroup by remember { mutableStateOf<Pair<Boolean, Int>>(false to -1) }

    // UI: 使用滚动列承载全部设置项
    val scrollState = rememberScrollState()

    MiuixTheme {
        val colorScheme = MiuixTheme.colorScheme
        val textStyles = MiuixTheme.textStyles

        Column(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(top = 12.dp)
        ) {
        // 包名等价功能总开关
            Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Text("启用包名等价映射", style = textStyles.body2, color = colorScheme.onSurface)
            Spacer(modifier = androidx.compose.ui.Modifier.width(16.dp))
                top.yukonga.miuix.kmp.basic.Switch(
                checked = enablePackageGroupMapping,
                onCheckedChange = {
                    RemoteFilterConfig.enablePackageGroupMapping = it
                    RemoteFilterConfig.save(context)
                    enablePackageGroupMapping = it
                },
                modifier = androidx.compose.ui.Modifier.size(width = 24.dp, height = 12.dp)
            )
        }

        // 包名组配置（懒加载限高）
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        ) {
            items(allGroups.size) { idx ->
                val group = allGroups[idx]
                val groupEnabled = enablePackageGroupMapping
                top.yukonga.miuix.kmp.basic.Card(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .then(
                            if (groupEnabled) androidx.compose.ui.Modifier.clickable {
                                allGroupEnabled = allGroupEnabled.toMutableList().apply { set(idx, !allGroupEnabled[idx]) }
                            } else androidx.compose.ui.Modifier
                        )
                ) {
                    Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                                top.yukonga.miuix.kmp.basic.Checkbox(
                                checked = allGroupEnabled[idx],
                                onCheckedChange = { v ->
                                    val newEnabled = allGroupEnabled.toMutableList().apply { set(idx, v) }
                                    allGroupEnabled = newEnabled
                                    // 更新后端状态
                                    val defaultSize = RemoteFilterConfig.defaultPackageGroups.size
                                    RemoteFilterConfig.defaultGroupEnabled = newEnabled.take(defaultSize).toMutableList()
                                    RemoteFilterConfig.customGroupEnabled = newEnabled.drop(defaultSize).toMutableList()
                                    RemoteFilterConfig.save(context)
                                },
                                modifier = androidx.compose.ui.Modifier.size(20.dp),
                                enabled = enablePackageGroupMapping
                            )
                                Text(
                                if (idx < RemoteFilterConfig.defaultPackageGroups.size) "默认组${idx+1}" else "自定义组${idx+1-RemoteFilterConfig.defaultPackageGroups.size}",
                                style = textStyles.body2, color = colorScheme.onSurface, modifier = androidx.compose.ui.Modifier.padding(end = 4.dp)
                            )
                            Spacer(Modifier.weight(1f))
                            if (idx >= RemoteFilterConfig.defaultPackageGroups.size) {
                                top.yukonga.miuix.kmp.basic.Button(
                                    onClick = { showAppPickerForGroup = true to idx },
                                    modifier = androidx.compose.ui.Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
                                    enabled = enablePackageGroupMapping
                                ) {
                                    top.yukonga.miuix.kmp.basic.Text("+")
                                }
                                top.yukonga.miuix.kmp.basic.Button(
                                    onClick = {
                                        val newGroups = allGroups.toMutableList().apply { removeAt(idx) }
                                        val newEnabled = allGroupEnabled.toMutableList().apply { removeAt(idx) }
                                        allGroups = newGroups
                                        allGroupEnabled = newEnabled
                                        // 更新后端状态
                                        val defaultSize = RemoteFilterConfig.defaultPackageGroups.size
                                        RemoteFilterConfig.customPackageGroups = newGroups.drop(defaultSize).map { it.toMutableList() }.toMutableList()
                                        RemoteFilterConfig.customGroupEnabled = newEnabled.drop(defaultSize).toMutableList()
                                        RemoteFilterConfig.save(context)
                                    },
                                    modifier = androidx.compose.ui.Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).padding(start = 2.dp),
                                    enabled = enablePackageGroupMapping
                                ) {
                                    top.yukonga.miuix.kmp.basic.Text("×")
                                }
                            }
                        }
                        // 包名自动换行显示
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val installedPkgs = remember { AppRepository.getInstalledPackageNamesSync(context) }
                            group.forEach { pkg ->
                                val isInstalled = installedPkgs.contains(pkg)
                                val iconBitmap = AppRepository.getAppIconSync(context, pkg)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = androidx.compose.ui.Modifier.padding(end = 8.dp)) {
                                    if (iconBitmap != null) {
                                        Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = null, modifier = androidx.compose.ui.Modifier.size(18.dp))
                                    }
                                    Text(pkg, style = textStyles.body2, color = if (isInstalled) colorScheme.primary else colorScheme.onSurface, modifier = androidx.compose.ui.Modifier.padding(start = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // 添加新组按钮
        top.yukonga.miuix.kmp.basic.Button(
            onClick = {
                val newGroups = allGroups.toMutableList().apply { add(mutableListOf()) }
                val newEnabled = allGroupEnabled.toMutableList().apply { add(true) }
                allGroups = newGroups
                allGroupEnabled = newEnabled
                // 更新后端状态
                val defaultSize = RemoteFilterConfig.defaultPackageGroups.size
                RemoteFilterConfig.customPackageGroups = newGroups.drop(defaultSize).map { it.toMutableList() }.toMutableList()
                RemoteFilterConfig.customGroupEnabled = newEnabled.drop(defaultSize).toMutableList()
                RemoteFilterConfig.save(context)
            },
            modifier = androidx.compose.ui.Modifier.padding(vertical = 2.dp),
            enabled = enablePackageGroupMapping
        ) {
            top.yukonga.miuix.kmp.basic.Text("添加新组")
        }

        // 应用选择弹窗（封装组件调用）
        if (showAppPickerForGroup.first) {
            val groupIdx = showAppPickerForGroup.second
            AppPickerDialog(
                visible = true,
                onDismiss = { showAppPickerForGroup = false to -1 },
                onAppSelected = { pkg: String ->
                    val newGroups = allGroups.toMutableList().apply {
                        if (groupIdx in indices && !this[groupIdx].contains(pkg)) {
                            this[groupIdx] = (this[groupIdx] + pkg).toMutableList()
                        }
                    }
                    allGroups = newGroups
                    // 更新后端状态
                    val defaultSize = RemoteFilterConfig.defaultPackageGroups.size
                    RemoteFilterConfig.customPackageGroups = newGroups.drop(defaultSize).map { it.toMutableList() }.toMutableList()
                    RemoteFilterConfig.save(context)
                    showAppPickerForGroup = false to -1
                },
                title = "选择应用"
            )
        }

        // 过滤模式选择与黑白名单编辑
                Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("过滤模式:", style = textStyles.body2, color = colorScheme.onSurface)
            val modes = listOf("none" to "无", "black" to "黑名单", "white" to "白名单", "peer" to "对等")
            modes.forEach { (value, label) ->
                top.yukonga.miuix.kmp.basic.Button(
                    onClick = {
                        RemoteFilterConfig.filterMode = value
                        RemoteFilterConfig.enablePeerMode = (value == "peer")
                        RemoteFilterConfig.save(context)
                        filterMode = value
                    },
                    modifier = Modifier.padding(horizontal = 2.dp),
                    colors = if (filterMode == value) top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary() else top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColors()
                ) {
                    top.yukonga.miuix.kmp.basic.Text(label)
                }
            }
        }

        if (filterMode == "black" || filterMode == "white") {
            var showFilterAppPicker by remember { mutableStateOf(false) }
            var pendingFilterPkg by remember { mutableStateOf<String?>(null) }
            var pendingKeyword by remember { mutableStateOf("") }
            Text(
                "${if (filterMode=="black")"黑" else "白"}名单(每行:包名,可选关键词):",
                style = textStyles.body2,
                color = colorScheme.onSurface
            )
                Row(androidx.compose.ui.Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                top.yukonga.miuix.kmp.basic.TextField(
                    value = filterListText,
                    onValueChange = {
                        filterListText = it
                        RemoteFilterConfig.filterList = it.lines().filter { line -> line.isNotBlank() }.map { line ->
                            val arr = line.split(",", limit=2)
                            arr[0].trim() to arr.getOrNull(1)?.trim().takeIf { k->!k.isNullOrBlank() }
                        }
                        RemoteFilterConfig.save(context)
                    },
                    modifier = androidx.compose.ui.Modifier.weight(1f),
                    label = "com.a,关键字\ncom.b"
                )
                top.yukonga.miuix.kmp.basic.Button(
                    onClick = { showFilterAppPicker = true },
                    modifier = androidx.compose.ui.Modifier.padding(start = 6.dp)
                ) {
                    top.yukonga.miuix.kmp.basic.Text("添加包名")
                }
            }
            if (showFilterAppPicker) {
                AppPickerDialog(
                    visible = true,
                    onDismiss = { showFilterAppPicker = false },
                    onAppSelected = { pkg: String ->
                        pendingFilterPkg = pkg
                        pendingKeyword = ""
                        showFilterAppPicker = false
                    },
                    title = "选择包名"
                )
            }
            if (pendingFilterPkg != null) {
                val showKeywordDialog = remember { mutableStateOf(true) }
                AddKeywordDialog(
                    showDialog = showKeywordDialog,
                    packageName = pendingFilterPkg!!,
                    initialKeyword = pendingKeyword,
                    onConfirm = { keyword ->
                        val line = if (keyword.isBlank()) pendingFilterPkg!! else pendingFilterPkg!! + "," + keyword.trim()
                        val newFilterListText = if (filterListText.isBlank()) line else filterListText.trimEnd() + "\n" + line
                        filterListText = newFilterListText
                        // 更新后端状态
                        RemoteFilterConfig.filterList = newFilterListText.lines().filter { it.isNotBlank() }.map { it ->
                            val arr = it.split(",", limit=2)
                            arr[0].trim() to arr.getOrNull(1)?.trim().takeIf { k->!k.isNullOrBlank() }
                        }
                        RemoteFilterConfig.save(context)
                        showKeywordDialog.value = false
                        pendingFilterPkg = null
                    },
                    onDismiss = {
                        showKeywordDialog.value = false
                        pendingFilterPkg = null
                    }
                )
            }
        }

        // 延迟去重
                Row(verticalAlignment = Alignment.CenterVertically) {
            top.yukonga.miuix.kmp.basic.Switch(
                checked = enableDedup,
                onCheckedChange = {
                    RemoteFilterConfig.enableDeduplication = it
                    RemoteFilterConfig.save(context)
                    enableDedup = it
                },
                modifier = androidx.compose.ui.Modifier.padding(end = 4.dp)
            )
            Text("智能去重（先发送后撤回机制）", style = textStyles.body2, color = colorScheme.onSurface)
        }

        // 锁屏通知过滤
        Row(verticalAlignment = Alignment.CenterVertically) {
            top.yukonga.miuix.kmp.basic.Switch(
                checked = enableLockScreenOnly,
                onCheckedChange = {
                    RemoteFilterConfig.enableLockScreenOnly = it
                    RemoteFilterConfig.save(context)
                    enableLockScreenOnly = it
                },
                modifier = androidx.compose.ui.Modifier.padding(end = 4.dp)
            )
            Text("仅复刻锁屏通知（非锁屏通知仅存储不复刻）", style = textStyles.body2, color = colorScheme.onSurface)
        }

        // 应用按钮
        top.yukonga.miuix.kmp.basic.Button(
            onClick = {
                // 所有状态已经实时同步到后端，这里只需要确保最终保存
                RemoteFilterConfig.save(context)
            },
            modifier = androidx.compose.ui.Modifier.padding(top = 8.dp)
        ) {
            top.yukonga.miuix.kmp.basic.Text("应用设置")
        }
    }
    }
}

// 复用简单的枚举定义（老实现仍可用）
enum class FilterMode(val value: String, val displayName: String) {
    NONE("none", "无"),
    BLACK("black", "黑名单"),
    WHITE("white", "白名单"),
    PEER("peer", "对等")
}
