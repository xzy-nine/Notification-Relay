package com.xzyht.notifyrelay.feature.device

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import com.xzyht.notifyrelay.feature.device.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.DeviceInfo
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.TabRow
import com.xzyht.notifyrelay.feature.notification.NotificationFilterPager
import com.xzyht.notifyrelay.feature.notification.DefaultNotificationFilter
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import com.xzyht.notifyrelay.feature.notification.AppPickerDialog
import com.xzyht.notifyrelay.feature.notification.AddKeywordDialog





// 通用包名映射、去重、黑白名单/对等模式配置
object NotificationForwardConfig {
    private const val PREFS_NAME = "notifyrelay_filter_prefs"
    private const val KEY_PACKAGE_GROUPS = "package_groups"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_FILTER_LIST = "filter_list"
    private const val KEY_ENABLE_DEDUP = "enable_dedup"
    private const val KEY_ENABLE_PEER = "enable_peer"

    // 包名等价功能总开关
    var enablePackageGroupMapping: Boolean = true
    // 默认包名等价组及其开关
    val defaultPackageGroups: List<List<String>> = listOf(
        listOf("tv.danmaku.bilibilihd", "tv.danmaku.bili"),
        listOf("com.sina.weibo", "com.sina.weibog3", "com.weico.international", "com.sina.weibolite", "com.hengye.share", "com.caij.see"),
        listOf("com.tencent.mobileqq", "com.tencent.tim")
    )
    var defaultGroupEnabled: MutableList<Boolean> = mutableListOf(true, true, true)
    // 用户自定义包名等价组，每组为包名列表
    var customPackageGroups: MutableList<MutableList<String>> = mutableListOf()
    // 每个自定义组的开关
    var customGroupEnabled: MutableList<Boolean> = mutableListOf()
    // 合并后的包名等价组
    val packageGroups: List<Set<String>>
        get() = if (!enablePackageGroupMapping) emptyList()
            else defaultPackageGroups.withIndex().filter { defaultGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() } +
                customPackageGroups.withIndex().filter { customGroupEnabled.getOrNull(it.index) == true }.map { it.value.toSet() }
    // 延迟去重开关
    var enableDeduplication: Boolean = true
    // 黑白名单模式："none"=无，"black"=黑名单，"white"=白名单，"peer"=对等
    var filterMode: String = "none"
    // 黑/白名单内容（包名或通用包名+可选文本关键词）
    var filterList: List<Pair<String, String?>> = emptyList() // Pair<包名, 关键词?>
    // 对等模式开关（仅本机存在的应用或通用应用）
    var enablePeerMode: Boolean = false

    // 加载设置
    fun load(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        enablePackageGroupMapping = prefs.getBoolean("enable_package_group_mapping", true)
        val defaultEnabled = prefs.getString("default_group_enabled", "1,1,1")!!.split(",").map { it == "1" }
        defaultGroupEnabled = defaultEnabled.toMutableList().apply {
            while (size < defaultPackageGroups.size) add(true)
        }
        val customGroups = prefs.getStringSet(KEY_PACKAGE_GROUPS, null)?.mapNotNull {
            it.split("|").map { s->s.trim() }.filter { s->s.isNotBlank() }.toMutableList().takeIf { set->set.isNotEmpty() }
        }?.toMutableList() ?: mutableListOf()
        customPackageGroups = customGroups
        val customEnabledStr = prefs.getString("custom_group_enabled", null)
        customGroupEnabled = if (customEnabledStr != null) {
            customEnabledStr.split(",").map { it == "1" }.toMutableList().apply {
                while (size < customGroups.size) add(true)
            }
        } else MutableList(customGroups.size) { true }
        filterMode = prefs.getString(KEY_FILTER_MODE, "none") ?: "none"
        enableDeduplication = prefs.getBoolean(KEY_ENABLE_DEDUP, true)
        enablePeerMode = prefs.getBoolean(KEY_ENABLE_PEER, false)
        filterList = prefs.getStringSet(KEY_FILTER_LIST, null)?.map {
            val arr = it.split("|", limit=2)
            arr[0] to arr.getOrNull(1)?.takeIf { k->k.isNotBlank() }
        } ?: emptyList()
    }
    // 保存设置
    fun save(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, 0)
        prefs.edit()
            .putBoolean("enable_package_group_mapping", enablePackageGroupMapping)
            .putString("default_group_enabled", defaultGroupEnabled.joinToString(",") { if (it) "1" else "0" })
            .putString("custom_group_enabled", customGroupEnabled.joinToString(",") { if (it) "1" else "0" })
            .putStringSet(KEY_PACKAGE_GROUPS, customPackageGroups.map { it.joinToString("|") }.toSet())
            .putString(KEY_FILTER_MODE, filterMode)
            .putBoolean(KEY_ENABLE_DEDUP, enableDeduplication)
            .putBoolean(KEY_ENABLE_PEER, enablePeerMode)
            .putStringSet(KEY_FILTER_LIST, filterList.map { it.first + (it.second?.let { k->"|"+k } ?: "") }.toSet())
            .apply()
    }
    // 包名映射：返回本地等价包名
    fun mapToLocalPackage(pkg: String, installedPkgs: Set<String>): String {
        for (group in packageGroups) {
            if (pkg in group) {
                // 优先本机已安装的包名
                val local = group.firstOrNull { it in installedPkgs }
                if (local != null) return local
                // 否则取第一个
                return group.first()
            }
        }
        return pkg
    }
}

// 延迟去重缓存（10秒内）
private val dedupCache = mutableListOf<Triple<String, String, Long>>() // title, text, time
private val pendingDelayedNotifications = mutableListOf<Triple<String, String, String>>() // title, text, rawData

// 远程通知过滤接口（含包名映射、去重、黑白名单/对等模式）
private data class DedupResult(val immediate: Boolean, val shouldShow: Boolean, val mappedPkg: String, val title: String, val text: String, val rawData: String)

private fun remoteNotificationFilter(data: String, context: android.content.Context): DedupResult {
    try {
        val json = org.json.JSONObject(data)
        var pkg = json.optString("packageName")
        val title = json.optString("title")
        val text = json.optString("text")
        val time = System.currentTimeMillis()
        val pm = context.packageManager
        val installedPkgs = pm.getInstalledApplications(0).map { it.packageName }.toSet()
        val mappedPkg = NotificationForwardConfig.mapToLocalPackage(pkg, installedPkgs)
        if (NotificationForwardConfig.filterMode == "peer" || NotificationForwardConfig.enablePeerMode) {
            if (mappedPkg !in installedPkgs) {
                android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: peer mode过滤 mappedPkg=$mappedPkg 不在本机已安装应用")
                return DedupResult(true, false, mappedPkg, title, text, data)
            }
        }
        if (NotificationForwardConfig.filterMode == "black" || NotificationForwardConfig.filterMode == "white") {
            val match = NotificationForwardConfig.filterList.any { (filterPkg, keyword) ->
                (mappedPkg == filterPkg || pkg == filterPkg) && (keyword.isNullOrBlank() || title.contains(keyword) || text.contains(keyword))
            }
            if (NotificationForwardConfig.filterMode == "black" && match) {
                android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 命中黑名单 filtered=$match mappedPkg=$mappedPkg title=$title text=$text")
                return DedupResult(true, false, mappedPkg, title, text, data)
            }
            if (NotificationForwardConfig.filterMode == "white" && !match) {
                android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 未命中白名单 mappedPkg=$mappedPkg title=$title text=$text")
                return DedupResult(true, false, mappedPkg, title, text, data)
            }
        }
        if (NotificationForwardConfig.enableDeduplication) {
            val now = System.currentTimeMillis()
            // 1. 先查dedupCache
            synchronized(dedupCache) {
                dedupCache.removeAll { now - it.third > 10_000 }
                val dup = dedupCache.any { it.first == title && it.second == text }
                if (dup) {
                    android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 去重命中 title=$title text=$text (dedupCache)")
                    return DedupResult(true, false, mappedPkg, title, text, data)
                }
            }
            // 2. 再查本机通知历史（10秒内同title+text）
            try {
                val localList = com.xzyht.notifyrelay.feature.device.NotificationRepository.notifications
                val localDup = localList.any {
                    val match = it.device == "本机" && it.title == title && it.text == text && (now - it.time <= 10_000)
                    if (it.device == "本机" && (now - it.time <= 10_000)) {
                        android.util.Log.d(
                            "NotifyRelay(狂鼠)",
                            "remoteNotificationFilter(狂鼠): 本机历史检查 title=$title text=$text vs it.title=${it.title} it.text=${it.text} match=$match"
                        )
                    }
                    match
                }
                if (localDup) {
                    android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 去重命中 title=$title text=$text (本机历史)")
                    return DedupResult(true, false, mappedPkg, title, text, data)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotifyRelay(狂鼠)", "remoteNotificationFilter: 本机历史去重检查异常", e)
            }
            // 需延迟判断
            val result = DedupResult(false, true, mappedPkg, title, text, data)
            android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 需延迟判断 title=$title text=$text")
            return result
        }
        val result = DedupResult(true, true, mappedPkg, title, text, data)
        android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 直接通过 mappedPkg=$mappedPkg title=$title text=$text result=$result")
        return result
    } catch (e: Exception) {
        android.util.Log.e("NotifyRelay(狂鼠)", "remoteNotificationFilter: 解析异常", e)
        return DedupResult(true, true, "", "", "", data)
    }
}


class DeviceForwardFragment : Fragment() {
    // 认证通过设备持久化key
    private val PREFS_NAME = "notifyrelay_device_prefs"
    private val KEY_AUTHED_UUIDS = "authed_device_uuids"

    // 应用安装/卸载监听器
    private val appChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
            when (intent.action) {
                android.content.Intent.ACTION_PACKAGE_ADDED, android.content.Intent.ACTION_PACKAGE_REMOVED -> {
                    // AppListCache现在在AppPickerDialog.kt中，由其负责管理缓存
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
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        return prefs.getStringSet(KEY_AUTHED_UUIDS, emptySet()) ?: emptySet()
    }

    // 保存已认证设备uuid集合
    fun saveAuthedUuids(uuids: Set<String>) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, 0)
        prefs.edit().putStringSet(KEY_AUTHED_UUIDS, uuids).apply()
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
        android.util.Log.d("NotifyRelay(狂鼠)", "onCreateView called")
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
    // 手动发现提示相关状态
    val manualDiscoveryPrompt = remember { mutableStateOf<String?>(null) }
    val snackbarVisible = remember { mutableStateOf(false) }
    val coroutineScopeSnackbar = rememberCoroutineScope()

     
    android.util.Log.d("NotifyRelay(狂鼠)", "DeviceForwardScreen Composable launched")
    // TabRow相关状态
    val tabTitles = listOf("通知过滤设置", "聊天测试", "通知软编码过滤")
    var selectedTabIndex by remember { mutableStateOf(0) }
    // NotificationFilterPager 状态，持久化与后端同步
    var filterSelf by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterSelf) }
    var filterOngoing by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterOngoing) }
    var filterNoTitleOrText by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterNoTitleOrText) }
    var filterImportanceNone by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterImportanceNone) }
    var filterMiPushGroupSummary by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterMiPushGroupSummary) }
    var filterSensitiveHidden by remember { mutableStateOf<Boolean>(DefaultNotificationFilter.filterSensitiveHidden) }

    // 持久化监听
    LaunchedEffect(filterSelf, filterOngoing, filterNoTitleOrText, filterImportanceNone, filterMiPushGroupSummary, filterSensitiveHidden) {
        DefaultNotificationFilter.filterSelf = filterSelf
        DefaultNotificationFilter.filterOngoing = filterOngoing
        DefaultNotificationFilter.filterNoTitleOrText = filterNoTitleOrText
        DefaultNotificationFilter.filterImportanceNone = filterImportanceNone
        DefaultNotificationFilter.filterMiPushGroupSummary = filterMiPushGroupSummary
        DefaultNotificationFilter.filterSensitiveHidden = filterSensitiveHidden
        context?.let {
            val prefs = it.getSharedPreferences("notifyrelay_filter_prefs", 0)
            prefs.edit()
                .putBoolean("filter_self", filterSelf)
                .putBoolean("filter_ongoing", filterOngoing)
                .putBoolean("filter_no_title_or_text", filterNoTitleOrText)
                .putBoolean("filter_importance_none", filterImportanceNone)
                .putBoolean("filter_mipush_group_summary", filterMiPushGroupSummary)
                .putBoolean("filter_sensitive_hidden", filterSensitiveHidden)
                .apply()
        }
    }
    // 启动时加载本地持久化
    LaunchedEffect(Unit) {
        context?.let {
            val prefs = it.getSharedPreferences("notifyrelay_filter_prefs", 0)
            filterSelf = prefs.getBoolean("filter_self", filterSelf)
            filterOngoing = prefs.getBoolean("filter_ongoing", filterOngoing)
            filterNoTitleOrText = prefs.getBoolean("filter_no_title_or_text", filterNoTitleOrText)
            filterImportanceNone = prefs.getBoolean("filter_importance_none", filterImportanceNone)
            filterMiPushGroupSummary = prefs.getBoolean("filter_mipush_group_summary", filterMiPushGroupSummary)
            filterSensitiveHidden = prefs.getBoolean("filter_sensitive_hidden", filterSensitiveHidden)
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
    var chatInput by remember { mutableStateOf("") }
    var chatExpanded by remember { mutableStateOf(false) }
    val chatHistoryState = remember { mutableStateOf<List<String>>(emptyList()) }
    // 协程作用域（必须在 @Composable 作用域内声明）
    val coroutineScope = rememberCoroutineScope()
    // 聊天内容持久化到本地文件，应用退出前都保留
    LaunchedEffect(context) {
        chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
    }
    // 只监听全局选中设备
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceState.value
    // 复刻lancomm事件监听风格，Compose事件流监听消息
    // 远程通知过滤与复刻到系统通知中心
    val notificationCallback: (String) -> Unit = remember {
        { data: String ->
            android.util.Log.d("NotifyRelay(狂鼠)", "onNotificationDataReceived: $data")
            val result = remoteNotificationFilter(data, context)
            android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter result: $result")
            if (NotificationForwardConfig.enableDeduplication && !result.immediate && result.shouldShow) {
                // 延迟去重，需10秒后再判断
                coroutineScope.launch {
                    kotlinx.coroutines.delay(10_000)
                    var shouldShow = true
                    synchronized(dedupCache) {
                        val dup = dedupCache.any { it.first == result.title && it.second == result.text }
                        if (dup) shouldShow = false
                        else dedupCache.add(Triple(result.title, result.text, System.currentTimeMillis()))
                    }
                    if (shouldShow) {
                        try {
                            android.util.Log.d("NotifyRelay(狂鼠)", "[延迟]准备复刻通知: title=${result.title} text=${result.text} mappedPkg=${result.mappedPkg}")
                            val json = org.json.JSONObject(result.rawData)
                            json.put("packageName", result.mappedPkg)
                            val pkg = result.mappedPkg
                            val appName = json.optString("appName", pkg)
                            val title = json.optString("title")
                            val text = json.optString("text")
                            val time = json.optLong("time", System.currentTimeMillis())
                            var appIcon: android.graphics.Bitmap? = null
                            try {
                                val pm = context.packageManager
                                val appInfo = pm.getApplicationInfo(pkg, 0)
                                val drawable = pm.getApplicationIcon(appInfo)
                                if (drawable is android.graphics.drawable.BitmapDrawable) {
                                    appIcon = drawable.bitmap
                                } else {
                                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bitmap)
                                    drawable.setBounds(0, 0, width, height)
                                    drawable.draw(canvas)
                                    appIcon = bitmap
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("NotifyRelay(狂鼠)", "获取应用图标失败", e)
                            }
                            val pm = context.packageManager
                            val launchIntent = pm.getLaunchIntentForPackage(pkg)
                            val key = time.toString() + pkg
                            val notifyId = key.hashCode()
                            val pendingIntent = if (launchIntent != null) {
                                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                android.app.PendingIntent.getActivity(
                                    context,
                                    notifyId,
                                    launchIntent,
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
                                )
                            } else null
                            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                            val channelId = "notifyrelay_remote"
                            if (notificationManager.getNotificationChannel(channelId) == null) {
                                val channel = android.app.NotificationChannel(channelId, "远程通知", android.app.NotificationManager.IMPORTANCE_HIGH)
                                channel.description = "远程设备转发通知"
                                channel.enableLights(true)
                                channel.lightColor = android.graphics.Color.GREEN
                                channel.enableVibration(false)
                                channel.setSound(null, null)
                                channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                                channel.setShowBadge(false)
                                try {
                                    channel.setBypassDnd(true)
                                } catch (e: Exception) {
                                    android.util.Log.w("NotifyRelay(狂鼠)", "setBypassDnd not supported", e)
                                }
                                notificationManager.createNotificationChannel(channel)
                                android.util.Log.d("NotifyRelay(狂鼠)", "已创建通知渠道: $channelId")
                            }
                            val builder = android.app.Notification.Builder(context, channelId)
                                .setContentTitle(title)
                                .setContentText(text)
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                                .setAutoCancel(true)
                                .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                                .setOngoing(false)
                                .setWhen(time)
                            if (appIcon != null) {
                                builder.setLargeIcon(appIcon)
                            }
                            if (pendingIntent != null) {
                                builder.setContentIntent(pendingIntent)
                            }
                            android.util.Log.d("NotifyRelay(狂鼠)", "[延迟]准备发送通知: id=$notifyId, title=$title, text=$text, pkg=$pkg")
                            // 修复：发出通知前写入dedupCache，确保本地和远程都能去重
                            synchronized(dedupCache) {
                                dedupCache.add(Triple(title, text, System.currentTimeMillis()))
                            }
                            notificationManager.notify(notifyId, builder.build())
                            android.util.Log.d("NotifyRelay(狂鼠)", "[延迟]已调用notify: id=$notifyId")
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay(狂鼠)", "[延迟]远程通知复刻失败", e)
                        }
                        com.xzyht.notifyrelay.feature.notification.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
                    } else {
                        com.xzyht.notifyrelay.feature.notification.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
                    }
                }
            } else {
                // 立即决定
                if (result.shouldShow) {
                    try {
                        android.util.Log.d("NotifyRelay(狂鼠)", "[立即]准备复刻通知: title=${result.title} text=${result.text} mappedPkg=${result.mappedPkg}")
                        val json = org.json.JSONObject(result.rawData)
                        json.put("packageName", result.mappedPkg)
                        val pkg = result.mappedPkg
                        val appName = json.optString("appName", pkg)
                        val title = json.optString("title")
                        val text = json.optString("text")
                        val time = json.optLong("time", System.currentTimeMillis())
                        var appIcon: android.graphics.Bitmap? = null
                        try {
                            val pm = context.packageManager
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val drawable = pm.getApplicationIcon(appInfo)
                            if (drawable is android.graphics.drawable.BitmapDrawable) {
                                appIcon = drawable.bitmap
                            } else {
                                val drawable = drawable as android.graphics.drawable.Drawable
                                val width: Int = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                                val height: Int = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable.setBounds(0, 0, width, height)
                                drawable.draw(canvas)
                                appIcon = bitmap
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay(狂鼠)", "获取应用图标失败", e)
                        }
                        val pm = context.packageManager
                        val launchIntent = pm.getLaunchIntentForPackage(pkg)
                        val key = time.toString() + pkg
                        val notifyId = key.hashCode()
                        val pendingIntent = if (launchIntent != null) {
                            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            android.app.PendingIntent.getActivity(
                                context,
                                notifyId,
                                launchIntent,
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
                            )
                        } else null
                        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        val channelId = "notifyrelay_remote"
                        if (notificationManager.getNotificationChannel(channelId) == null) {
                            val channel = android.app.NotificationChannel(channelId, "远程通知", android.app.NotificationManager.IMPORTANCE_HIGH)
                            channel.description = "远程设备转发通知"
                            channel.enableLights(true)
                            channel.lightColor = android.graphics.Color.GREEN
                            channel.enableVibration(false)
                            channel.setSound(null, null)
                            channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                            channel.setShowBadge(false)
                            try {
                                channel.setBypassDnd(true)
                            } catch (e: Exception) {
                                android.util.Log.w("NotifyRelay(狂鼠)", "setBypassDnd not supported", e)
                            }
                            notificationManager.createNotificationChannel(channel)
                            android.util.Log.d("NotifyRelay(狂鼠)", "已创建通知渠道: $channelId")
                        }
                        val builder = android.app.Notification.Builder(context, channelId)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                            .setAutoCancel(true)
                            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                            .setOngoing(false)
                            .setWhen(time)
                        if (appIcon != null) {
                            builder.setLargeIcon(appIcon)
                        }
                        if (pendingIntent != null) {
                            builder.setContentIntent(pendingIntent)
                        }
                        android.util.Log.d("NotifyRelay(狂鼠)", "[立即]准备发送通知: id=$notifyId, title=$title, text=$text, pkg=$pkg")
                        // 修复：发出通知前写入dedupCache，确保本地和远程都能去重
                        synchronized(dedupCache) {
                            dedupCache.add(Triple(title, text, System.currentTimeMillis()))
                        }
                        notificationManager.notify(notifyId, builder.build())
                        android.util.Log.d("NotifyRelay(狂鼠)", "[立即]已调用notify: id=$notifyId")
                    } catch (e: Exception) {
                        android.util.Log.e("NotifyRelay(狂鼠)", "[立即]远程通知复刻失败", e)
                    }
                    com.xzyht.notifyrelay.feature.notification.ChatMemory.append(context, "收到: ${result.rawData}")
                    chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
                } else {
                    com.xzyht.notifyrelay.feature.notification.ChatMemory.append(context, "收到: ${result.rawData}")
                    chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
                }
            }
        }
    }
    DisposableEffect(deviceManager) {
        deviceManager.registerOnNotificationDataReceived(notificationCallback)
        onDispose { deviceManager.unregisterOnNotificationDataReceived(notificationCallback) }
    }

    // 聊天区UI+过滤设置（可折叠）
    var filterExpanded by remember { mutableStateOf(false) }

    // 首次进入时加载持久化设置
    LaunchedEffect(context) {
        NotificationForwardConfig.load(context)
    }
    var filterMode by remember { mutableStateOf(NotificationForwardConfig.filterMode) }
    var enableDedup by remember { mutableStateOf(NotificationForwardConfig.enableDeduplication) }
    // 移除enablePeer的独立Switch，只用filterMode控制
    var enablePackageGroupMapping by remember { mutableStateOf(NotificationForwardConfig.enablePackageGroupMapping) }
    // 合并组：默认组+自定义组
    var allGroups by remember {
        mutableStateOf(
            NotificationForwardConfig.defaultPackageGroups.map { it.toList() }.toMutableList() +
            NotificationForwardConfig.customPackageGroups.map { it.toList() }.toMutableList()
        )
    }
    var allGroupEnabled by remember {
        mutableStateOf(
            NotificationForwardConfig.defaultGroupEnabled.toList() + NotificationForwardConfig.customGroupEnabled.toList()
        )
    }
    var showAppPickerForGroup by remember { mutableStateOf<Pair<Boolean, Int>>(false to -1) }
    var appSearchQuery by remember { mutableStateOf("") }
    var filterListText by remember { mutableStateOf(NotificationForwardConfig.filterList.joinToString("\n") { it.first + (it.second?.let { k-> ","+k } ?: "") }) }

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
                // 通知过滤设置 Tab 内容，支持整体上下滚动
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
                            onCheckedChange = { enablePackageGroupMapping = it },
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
                                                allGroupEnabled = allGroupEnabled.toMutableList().apply { set(idx, v) }
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
                                            if (idx < NotificationForwardConfig.defaultPackageGroups.size) "默认组${idx+1}" else "自定义组${idx+1-NotificationForwardConfig.defaultPackageGroups.size}",
                                            style = textStyles.body2, color = colorScheme.onSurface, modifier = Modifier.padding(end = 4.dp)
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (idx >= NotificationForwardConfig.defaultPackageGroups.size) {
                                            top.yukonga.miuix.kmp.basic.Button(
                                                onClick = { showAppPickerForGroup = true to idx },
                                                modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp),
                                                enabled = enablePackageGroupMapping
                                            ) {
                                                top.yukonga.miuix.kmp.basic.Text("+")
                                            }
                                            top.yukonga.miuix.kmp.basic.Button(
                                                onClick = {
                                                    allGroups = allGroups.toMutableList().apply { removeAt(idx) }
                                                    allGroupEnabled = allGroupEnabled.toMutableList().apply { removeAt(idx) }
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
                                        val installedPkgs = remember { pm.getInstalledApplications(0).map { it.packageName }.toSet() }
                                        group.forEach { pkg ->
                                            val isInstalled = installedPkgs.contains(pkg)
                                            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                                                if (icon is android.graphics.drawable.BitmapDrawable) {
                                                    Image(bitmap = icon.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(18.dp))
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
                            allGroups = allGroups.toMutableList().apply { add(mutableListOf()) }
                            allGroupEnabled = allGroupEnabled.toMutableList().apply { add(true) }
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
                                allGroups = allGroups.toMutableList().apply {
                                    if (groupIdx in indices && !this[groupIdx].contains(pkg)) this[groupIdx] = this[groupIdx] + pkg
                                }
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
                                onClick = { filterMode = value },
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
                                onValueChange = { filterListText = it },
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
                                    filterListText = if (filterListText.isBlank()) line else filterListText.trimEnd() + "\n" + line
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
                            onCheckedChange = { enableDedup = it },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("10秒内重复通知去重", style = textStyles.body2, color = colorScheme.onSurface)
                    }
                    // 应用按钮
                    top.yukonga.miuix.kmp.basic.Button(
                        onClick = {
                            NotificationForwardConfig.enablePackageGroupMapping = enablePackageGroupMapping
                            // 拆分allGroups和allGroupEnabled为默认组和自定义组
                            val defaultSize = NotificationForwardConfig.defaultPackageGroups.size
                            NotificationForwardConfig.defaultGroupEnabled = allGroupEnabled.take(defaultSize).toMutableList()
                            NotificationForwardConfig.customGroupEnabled = allGroupEnabled.drop(defaultSize).toMutableList()
                            NotificationForwardConfig.customPackageGroups = allGroups.drop(defaultSize).map { it.toMutableList() }.toMutableList()
                            NotificationForwardConfig.filterMode = filterMode
                            NotificationForwardConfig.enableDeduplication = enableDedup
                            NotificationForwardConfig.enablePeerMode = (filterMode == "peer")
                            NotificationForwardConfig.filterList = filterListText.lines().filter { it.isNotBlank() }.map {
                                val arr = it.split(",", limit=2)
                                arr[0].trim() to arr.getOrNull(1)?.trim().takeIf { k->!k.isNullOrBlank() }
                            }
                            NotificationForwardConfig.save(context)
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
                                // 全部广播：遍历所有已认证设备
                                val allDevices = deviceManager.devices.value.values.map { it.first }
                                val sentAny = allDevices.isNotEmpty() && chatInput.isNotBlank()
                                // 构建标准 JSON
                                val pkgName: String = context.packageName
                                val json = org.json.JSONObject().apply {
                                    put("packageName", pkgName)
                                    put("appName", "NotifyRelay")
                                    put("title", "聊天测试")
                                    put("text", chatInput)
                                    put("time", System.currentTimeMillis())
                                }.toString()
                                allDevices.forEach { dev ->
                                    deviceManager.sendNotificationData(dev, json)
                                }
                                if (sentAny) {
                                    com.xzyht.notifyrelay.feature.notification.ChatMemory.append(context, "发送: $chatInput")
                                    chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.ChatMemory.getChatHistory(context)
                                    chatInput = ""
                                }
                            },
                            enabled = deviceManager.devices.value.isNotEmpty() && chatInput.isNotBlank(),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            top.yukonga.miuix.kmp.basic.Text("发送")
                        }
                    }
                }
            }
            2 -> {
                // 通知软编码过滤 Tab
                NotificationFilterPager(
                    filterSelf = filterSelf,
                    filterOngoing = filterOngoing,
                    filterNoTitleOrText = filterNoTitleOrText,
                    filterImportanceNone = filterImportanceNone,
                    filterMiPushGroupSummary = filterMiPushGroupSummary,
                    filterSensitiveHidden = filterSensitiveHidden,
                    onFilterSelfChange = { filterSelf = it },
                    onFilterOngoingChange = { filterOngoing = it },
                    onFilterNoTitleOrTextChange = { filterNoTitleOrText = it },
                    onFilterImportanceNoneChange = { filterImportanceNone = it },
                    onFilterMiPushGroupSummaryChange = { filterMiPushGroupSummary = it },
                    onFilterSensitiveHiddenChange = { filterSensitiveHidden = it }
                )
            }
        }
    }
}}
