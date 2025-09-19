package com.xzyht.notifyrelay.feature.device.ui

import android.os.Bundle
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.core.repository.AppRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import com.xzyht.notifyrelay.feature.notification.ui.NotificationFilterPager
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AddKeywordDialog
import com.xzyht.notifyrelay.feature.notification.ui.dialog.AppPickerDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme


class DeviceForwardFragment : Fragment() {
    // 认证通过设备持久化key
    private val KEY_AUTHED_UUIDS = "authed_device_uuids"

    // 应用安装/卸载监听器
    private val appChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_PACKAGE_ADDED -> {
                    // 应用安装时清除缓存，下次使用时会重新加载最新的应用列表和图标
                    AppRepository.clearCache()
                    if (BuildConfig.DEBUG) Log.d("DeviceForwardFragment", "应用安装，清除缓存")
                }
                android.content.Intent.ACTION_PACKAGE_REMOVED -> {
                    // 应用卸载时清除缓存，下次使用时会重新加载最新的应用列表和图标
                    AppRepository.clearCache()
                    if (BuildConfig.DEBUG) Log.d("DeviceForwardFragment", "应用卸载，清除缓存")
                }
            }
        }
    }

    companion object {
        // 全局单例，保证同一进程内所有页面共享同一个 deviceManager
        @Volatile
        private var sharedDeviceManager: DeviceConnectionManager? = null
        fun getDeviceManager(context: android.content.Context): DeviceConnectionManager {
            return sharedDeviceManager ?: synchronized(this) {
                sharedDeviceManager ?: DeviceConnectionManager(context.applicationContext).also { sharedDeviceManager = it }
            }
        }
    }

    // 加载已认证设备uuid集合
    fun loadAuthedUuids(): Set<String> {
        return StorageManager.getStringSet(requireContext(), KEY_AUTHED_UUIDS, emptySet(), StorageManager.PrefsType.DEVICE)
    }

    // 保存已认证设备uuid集合
    fun saveAuthedUuids(uuids: Set<String>) {
        StorageManager.putStringSet(requireContext(), KEY_AUTHED_UUIDS, uuids, StorageManager.PrefsType.DEVICE)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // 注册应用安装/卸载监听器
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        requireContext().registerReceiver(appChangeReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销监听器
        requireContext().unregisterReceiver(appChangeReceiver)
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): android.view.View? {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "onCreateView called")
        return ComposeView(requireContext()).apply {
            setContent {
                MiuixTheme {
                    DeviceForwardScreen(
                        deviceManager = getDeviceManager(requireContext()),
                        loadAuthedUuids = { loadAuthedUuids() },
                        saveAuthedUuids = { saveAuthedUuids(it) }
                    )
                }
            }
        }
    }
@Composable
fun DeviceForwardScreen(
    deviceManager: DeviceConnectionManager,
    loadAuthedUuids: () -> Set<String>,
    saveAuthedUuids: (Set<String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 确保RemoteFilterConfig已加载
    if (!RemoteFilterConfig.isLoaded) {
        RemoteFilterConfig.load(context)
        RemoteFilterConfig.isLoaded = true
    }
    // 手动发现提示相关状态
    val manualDiscoveryPrompt = remember { mutableStateOf<String?>(null) }
    val snackbarVisible = remember { mutableStateOf(false) }
    val coroutineScopeSnackbar = rememberCoroutineScope()

     
    if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "DeviceForwardScreen Composable launched")
    // TabRow相关状态
    val tabTitles = listOf("远程通知过滤", "聊天测试", "本地通知过滤")
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) }
    // NotificationFilterPager 状态，持久化与后端同步
    var filterSelf by remember { mutableStateOf<Boolean>(BackendLocalFilter.filterSelf) }
    var filterOngoing by remember { mutableStateOf<Boolean>(BackendLocalFilter.filterOngoing) }
    var filterNoTitleOrText by remember { mutableStateOf<Boolean>(BackendLocalFilter.filterNoTitleOrText) }
    var filterImportanceNone by remember { mutableStateOf<Boolean>(BackendLocalFilter.filterImportanceNone) }

    // 持久化监听 - 直接更新后端状态
    LaunchedEffect(filterSelf, filterOngoing, filterNoTitleOrText, filterImportanceNone) {
        BackendLocalFilter.filterSelf = filterSelf
        BackendLocalFilter.filterOngoing = filterOngoing
        BackendLocalFilter.filterNoTitleOrText = filterNoTitleOrText
        BackendLocalFilter.filterImportanceNone = filterImportanceNone
        context?.let {
            StorageManager.putBoolean(it, "filter_self", filterSelf, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(it, "filter_ongoing", filterOngoing, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(it, "filter_no_title_or_text", filterNoTitleOrText, StorageManager.PrefsType.FILTER)
            StorageManager.putBoolean(it, "filter_importance_none", filterImportanceNone, StorageManager.PrefsType.FILTER)
        }
    }
    // 连接弹窗与错误弹窗相关状态
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectError by rememberSaveable { mutableStateOf<String?>(null) }
    // 设备认证、删除等逻辑已交由DeviceListFragment统一管理

    // Miuix风格通知弹窗
    if (snackbarVisible.value && manualDiscoveryPrompt.value != null) {
        Surface(
            color = MiuixTheme.colorScheme.surface,
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    manualDiscoveryPrompt.value!!,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "关闭",
                    onClick = { snackbarVisible.value = false }
                )
            }
        }
        // 自动消失
        LaunchedEffect(manualDiscoveryPrompt.value) {
            kotlinx.coroutines.delay(5000)
            snackbarVisible.value = false
        }
    }
    val colorScheme = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    // 聊天区相关状态
    var chatInput by rememberSaveable { mutableStateOf("") }
    var chatExpanded by rememberSaveable { mutableStateOf(false) }
    val chatHistoryState = remember { mutableStateOf<List<String>>(emptyList()) }
    // 协程作用域（必须在 @Composable 作用域内声明）
    val coroutineScope = rememberCoroutineScope()
    // 聊天内容持久化到本地文件，应用退出前都保留
    LaunchedEffect(context) {
        chatHistoryState.value = ChatMemory.getChatHistory(context)
    }
    // 只监听全局选中设备
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceState.value
    // 复刻lancomm事件监听风格，Compose事件流监听消息
    // 远程通知过滤与复刻到系统通知中心
    val notificationCallback: (String) -> Unit = remember {
        { data: String ->
            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "onNotificationDataReceived: $data")
            // 通知处理已在后台完成，这里只更新UI聊天历史
            chatHistoryState.value = ChatMemory.getChatHistory(context)
        }
    }
    DisposableEffect(deviceManager) {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "注册通知数据接收回调")
        deviceManager.registerOnNotificationDataReceived(notificationCallback)
        onDispose {
            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "注销通知数据接收回调")
            deviceManager.unregisterOnNotificationDataReceived(notificationCallback)
        }
    }

    // 聊天区UI+过滤设置（可折叠）
    var filterExpanded by rememberSaveable { mutableStateOf(false) }

    // UI状态变量 - 直接从后端获取，不自己持久化
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

    // 直接更新后端状态，不需要额外的持久化监听

    // 状态已实时同步，不需要重置加载标志

    var showAppPickerForGroup by rememberSaveable(stateSaver = Saver(
        save = { "${it.first},${it.second}" },
        restore = { value ->
            val parts = value.split(",")
            (parts[0].toBoolean()) to (parts.getOrNull(1)?.toInt() ?: -1)
        }
    )) { mutableStateOf<Pair<Boolean, Int>>(false to -1) }
    var appSearchQuery by rememberSaveable { mutableStateOf("") }

    // 前端状态已直接从后端初始化，不需要额外加载

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(12.dp)
    ) {
        TabRow(
            tabs = tabTitles,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = { selectedTabIndex = it },
            modifier = Modifier.fillMaxWidth()
        )
        // 移除Spacer，改为内容区顶部padding
        when (selectedTabIndex) {
            0 -> {
                // 远程通知过滤 Tab 内容，支持整体上下滚动
                val scrollState = androidx.compose.foundation.rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(top = 12.dp)
                ) {
                    // 包名等价功能总开关
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
                    // 滚动区域：包名组配置
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp) // 限高，避免撑满整个屏幕
                    ) {
                        items(allGroups.size) { idx ->
                            val group = allGroups[idx]
                            val groupEnabled = enablePackageGroupMapping
                            top.yukonga.miuix.kmp.basic.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .then(
                                        if (groupEnabled) Modifier.clickable {
                                            allGroupEnabled = allGroupEnabled.toMutableList().apply { set(idx, !allGroupEnabled[idx]) }
                                        } else Modifier
                                    )
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
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
                                            modifier = Modifier.size(20.dp),
                                            colors = top.yukonga.miuix.kmp.basic.CheckboxDefaults.checkboxColors(
                                                checkedBackgroundColor = colorScheme.primary,
                                                checkedForegroundColor = colorScheme.onPrimary,
                                                uncheckedBackgroundColor = colorScheme.outline.copy(alpha = 0.8f),
                                                uncheckedForegroundColor = colorScheme.outline,
                                                disabledCheckedBackgroundColor = colorScheme.surface,
                                                disabledUncheckedBackgroundColor = colorScheme.outline.copy(alpha = 0.8f),
                                                disabledCheckedForegroundColor = colorScheme.outline,
                                                disabledUncheckedForegroundColor = colorScheme.outline
                                            ),
                                            enabled = enablePackageGroupMapping
                                        )
                                        Text(
                                            if (idx < RemoteFilterConfig.defaultPackageGroups.size) "默认组${idx+1}" else "自定义组${idx+1-RemoteFilterConfig.defaultPackageGroups.size}",
                                            style = textStyles.body2, color = colorScheme.onSurface, modifier = Modifier.padding(end = 4.dp)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (idx >= RemoteFilterConfig.defaultPackageGroups.size) {
                                            top.yukonga.miuix.kmp.basic.Button(
                                                onClick = { showAppPickerForGroup = true to idx },
                                                modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
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
                                                modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).padding(start = 2.dp),
                                                enabled = enablePackageGroupMapping
                                            ) {
                                                top.yukonga.miuix.kmp.basic.Text("×")
                                            }
                                        }
                                    }
                                    // 包名自动换行显示
                                    androidx.compose.foundation.layout.FlowRow(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val pm = context.packageManager
                                        val installedPkgs = remember { AppRepository.getInstalledPackageNamesSync(context) }
                                        group.forEach { pkg ->
                                            val isInstalled = installedPkgs.contains(pkg)
                                            // 使用缓存的图标（同步版本）
                                            val iconBitmap = AppRepository.getAppIconSync(context, pkg)
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                                                if (iconBitmap != null) {
                                                    Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp))
                                                }
                                                Text(pkg, style = textStyles.body2, color = if (isInstalled) colorScheme.primary else colorScheme.onSurface, modifier = Modifier.padding(start = 2.dp))
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
                        modifier = Modifier.padding(vertical = 2.dp),
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
                    // 过滤模式
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    // 黑白名单
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
                                    // 追加到名单
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
                            modifier = Modifier.padding(end = 4.dp)
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
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("仅复刻锁屏通知（非锁屏通知仅存储不复刻）", style = textStyles.body2, color = colorScheme.onSurface)
                    }
                    // 应用按钮
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            // 所有状态已经实时同步到后端，这里只需要确保最终保存
                            RemoteFilterConfig.save(context)
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        top.yukonga.miuix.kmp.basic.Text("应用设置")
                    }
                }
            }
            1 -> {
                // 聊天测试 Tab 内容
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 8.dp)
                ) {
                    // 填满剩余空间的聊天历史，自动滚动到底部
                    val listState = remember { androidx.compose.foundation.lazy.LazyListState() }
                    val chatList = chatHistoryState.value
                    // 聊天内容变动时自动滚动到底部，首次进入直接定位
                    var firstLoad by remember { mutableStateOf(true) }
                    LaunchedEffect(chatList.size) {
                        if (chatList.isNotEmpty()) {
                            if (firstLoad) {
                                listState.scrollToItem(chatList.lastIndex)
                                firstLoad = false
                            } else {
                                listState.animateScrollToItem(chatList.lastIndex)
                            }
                        }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        items(chatList) { msg ->
                            val isSend = msg.startsWith("发送:")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isSend) Arrangement.End else Arrangement.Start
                            ) {
                                top.yukonga.miuix.kmp.basic.Surface(
                                    color = if (isSend) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                    modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        msg.removePrefix("发送:").removePrefix("收到:"),
                                        style = textStyles.body2,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                    // 输入区始终底部
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        top.yukonga.miuix.kmp.basic.TextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            label = "输入消息..."
                        )
                        top.yukonga.miuix.kmp.basic.Button(
                            onClick = {
                                // 使用整合的消息发送工具
                                com.xzyht.notifyrelay.core.util.MessageSender.sendChatMessage(
                                    context,
                                    chatInput,
                                    deviceManager
                                )
                                chatHistoryState.value = ChatMemory.getChatHistory(context)
                                chatInput = ""
                            },
                            enabled = com.xzyht.notifyrelay.core.util.MessageSender.hasAvailableDevices(deviceManager) &&
                                    com.xzyht.notifyrelay.core.util.MessageSender.isValidMessage(chatInput),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            top.yukonga.miuix.kmp.basic.Text("发送")
                        }
                    }
                }
            }
            2 -> {
                // 本地通知过滤 Tab
                NotificationFilterPager(
                    filterSelf = filterSelf,
                    filterOngoing = filterOngoing,
                    filterNoTitleOrText = filterNoTitleOrText,
                    filterImportanceNone = filterImportanceNone,
                    onFilterSelfChange = { filterSelf = it },
                    onFilterOngoingChange = { filterOngoing = it },
                    onFilterNoTitleOrTextChange = { filterNoTitleOrText = it },
                    onFilterImportanceNoneChange = { filterImportanceNone = it }
                )
            }
        }
    }
}}

