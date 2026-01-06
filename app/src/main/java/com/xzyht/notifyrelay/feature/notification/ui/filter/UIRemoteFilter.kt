package com.xzyht.notifyrelay.feature.notification.ui.filter

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xzyht.notifyrelay.common.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AddKeywordDialog
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AppPickerDialog
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperCheckbox
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 将 DeviceForwardFragment 中原有的远程过滤内联实现移动到这里：
 * 该组件负责读取/写入 RemoteFilterConfig 并提供完整的远程过滤 UI
 */
@Composable
fun UIRemoteFilter() {
    val context = LocalContext.current

    // 前端状态 - 先使用默认值，然后在配置加载完成后更新
    var filterMode by remember { mutableStateOf("none") }
    var enableDedup by remember { mutableStateOf(true) }
    var enablePackageGroupMapping by remember { mutableStateOf(true) }
    var allGroups by remember { mutableStateOf<List<MutableList<String>>>(
        RemoteFilterConfig.defaultPackageGroups.map { it.toMutableList() }
    ) }
    var allGroupEnabled by remember { mutableStateOf<List<Boolean>>(
        RemoteFilterConfig.defaultGroupEnabled.toMutableList()
    ) }
    var filterListText by remember { mutableStateOf("") }
    var enableLockScreenOnly by remember { mutableStateOf(false) }
    
    // 在LaunchedEffect中异步加载RemoteFilterConfig，避免阻塞UI
    LaunchedEffect(Unit) {
        if (!RemoteFilterConfig.isLoaded) {
            RemoteFilterConfig.load(context)
            RemoteFilterConfig.isLoaded = true
        }
        
        // 配置加载完成后更新前端状态
        filterMode = RemoteFilterConfig.filterMode
        enableDedup = RemoteFilterConfig.enableDeduplication
        enablePackageGroupMapping = RemoteFilterConfig.enablePackageGroupMapping
        allGroups = (RemoteFilterConfig.defaultPackageGroups.map { it.toMutableList() } +
                RemoteFilterConfig.customPackageGroups.map { it.toMutableList() }).toMutableList()
        allGroupEnabled = (RemoteFilterConfig.defaultGroupEnabled + RemoteFilterConfig.customGroupEnabled).toMutableList()
        filterListText = RemoteFilterConfig.filterList.joinToString("\n") { it.first + (it.second?.let { k-> ","+k } ?: "") }
        enableLockScreenOnly = RemoteFilterConfig.enableLockScreenOnly
    }

    var showAppPickerForGroup by remember { mutableStateOf<Pair<Boolean, Int>>(false to -1) }

    // UI: 使用滚动列承载全部设置项
    val scrollState = rememberScrollState()

    MiuixTheme {
        val colorScheme = MiuixTheme.colorScheme
        val textStyles = MiuixTheme.textStyles
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(top = 12.dp)
        ) {
                // 延迟去重
        SuperSwitch(
            title = "智能去重",
            checked = enableDedup,
            summary = "智能去重，避免重复通知",
            onCheckedChange = {
                RemoteFilterConfig.enableDeduplication = it
                RemoteFilterConfig.save(context)
                enableDedup = it
            }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 32.dp))
        
        // 锁屏通知过滤
        SuperSwitch(
            title = "仅复刻锁屏通知到通知栏",
            checked = enableLockScreenOnly,
            summary = "仅复刻锁屏状态的通知到通知栏",
            onCheckedChange = {
                RemoteFilterConfig.enableLockScreenOnly = it
                RemoteFilterConfig.save(context)
                enableLockScreenOnly = it
            }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 32.dp))
        // 过滤模式选择与黑白名单编辑
        val modes = listOf("none" to "无", "black" to "黑名单", "white" to "白名单", "peer" to "对等")
        val modeLabels = modes.map { it.second }
        val selectedModeIndex = modes.indexOfFirst { it.first == filterMode }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 32.dp))
        SuperDropdown(
            title = "过滤模式",
            items = modeLabels,
            selectedIndex = selectedModeIndex,
            onSelectedIndexChange = { index ->
                val (value, _) = modes[index]
                RemoteFilterConfig.filterMode = value
                RemoteFilterConfig.enablePeerMode = (value == "peer")
                RemoteFilterConfig.save(context)
                filterMode = value
            },
            modifier = Modifier.padding(vertical = 4.dp)
        )

        if (filterMode == "black" || filterMode == "white") {
            var showFilterAppPicker by remember { mutableStateOf(false) }
            var pendingFilterPkg by remember { mutableStateOf<String?>(null) }
            var pendingKeyword by remember { mutableStateOf("") }
            Text(
                "${if (filterMode=="black")"黑" else "白"}名单(每行:包名,可选关键词):",
                style = textStyles.body2,
                color = colorScheme.onSurface
            )
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    modifier = Modifier.weight(1f),
                    label = "com.a,关键字\ncom.b"
                )
                top.yukonga.miuix.kmp.basic.Button(
                    onClick = { showFilterAppPicker = true },
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text("添加包名")
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
                        RemoteFilterConfig.filterList = newFilterListText.lines().filter { it.isNotBlank() }.map {
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
        // 包名等价功能总开关
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        ) {
            Text("启用包名等价映射", style = textStyles.body2, color = colorScheme.onSurface)
            Spacer(modifier = Modifier.width(16.dp))
                top.yukonga.miuix.kmp.basic.Switch(
                checked = enablePackageGroupMapping,
                onCheckedChange = {
                    RemoteFilterConfig.enablePackageGroupMapping = it
                    RemoteFilterConfig.save(context)
                    enablePackageGroupMapping = it
                },
                modifier = Modifier.size(width = 24.dp, height = 12.dp)
            )
        }

        // 包名组配置
        Column(modifier = Modifier.fillMaxWidth()) {
            allGroups.forEachIndexed { idx, group ->
                val groupName = if (idx < RemoteFilterConfig.defaultPackageGroups.size) "默认组${idx+1}" else "自定义组${idx+1-RemoteFilterConfig.defaultPackageGroups.size}"
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 将SuperCheckbox和操作按钮放在同一行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // SuperCheckbox占据大部分空间
                        SuperCheckbox(
                            title = groupName,
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
                            enabled = enablePackageGroupMapping,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // 右侧操作按钮组
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (idx >= RemoteFilterConfig.defaultPackageGroups.size) {
                                top.yukonga.miuix.kmp.basic.Button(
                                    onClick = { showAppPickerForGroup = true to idx },
                                    modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp),
                                    enabled = enablePackageGroupMapping
                                ) {
                                    Text("+")
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
                                    modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp).padding(start = 2.dp),
                                    enabled = enablePackageGroupMapping
                                ) {
                                    Text("×")
                                }
                            }
                        }
                    }
                    
                    // 包名自动换行显示
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(start = 60.dp, top = 0.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // 监听AppRepository的状态，确保数据已加载
                        val installedPkgs by remember { AppRepository.apps }.collectAsState()
                        val installedPkgSet = installedPkgs.map { it.packageName }.toSet()
                        
                        // 确保应用列表已加载
                        LaunchedEffect(Unit) {
                            if (!AppRepository.isDataLoaded()) {
                                AppRepository.loadApps(context)
                            }
                        }
                        
                        group.forEach { pkg ->
                            val isInstalled = installedPkgSet.contains(pkg)
                            // 使用mutableStateOf保存图标状态，这样更新时会触发UI重新渲染
                            var iconBitmap by remember { mutableStateOf(AppRepository.getAppIcon(pkg)) }
                            
                            // 异步加载缺失的图标，并在加载完成后更新状态
                            LaunchedEffect(pkg) {
                                if (iconBitmap == null) {
                                    // 异步加载图标
                                    val loadedIcon = AppRepository.getAppIconAsync(context, pkg)
                                    // 更新状态，触发UI重新渲染
                                    iconBitmap = loadedIcon
                                }
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                                iconBitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(16.dp)) }
                                Text(pkg, style = textStyles.body2, color = if (isInstalled) colorScheme.primary else colorScheme.onSurface, modifier = Modifier.padding(start = 2.dp))
                            }
                        }
                    }
                    
                    // 在每组之间添加分割线，最后一组除外
                    if (idx < allGroups.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        
        // 添加新组按钮
        SuperArrow(
            title = "添加新组",
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
            modifier = Modifier.padding(vertical = 2.dp),
            enabled = enablePackageGroupMapping
        )
        
        // 在添加新组按钮下方添加分割线
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

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
            )}
        }
    }
}
}
    
