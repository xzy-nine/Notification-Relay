package com.xzyht.notifyrelay

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.compose.ui.platform.ComposeView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManager
import com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
import com.xzyht.notifyrelay.GlobalSelectedDeviceHolder
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon

import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import top.yukonga.miuix.kmp.icon.MiuixIcons

import top.yukonga.miuix.kmp.icon.icons.basic.ArrowRight


// 通用包名映射、去重、黑白名单/对等模式配置
object NotificationForwardConfig {
    private const val PREFS_NAME = "notifyrelay_filter_prefs"
    private const val KEY_PACKAGE_GROUPS = "package_groups"
    private const val KEY_FILTER_MODE = "filter_mode"
    private const val KEY_FILTER_LIST = "filter_list"
    private const val KEY_ENABLE_DEDUP = "enable_dedup"
    private const val KEY_ENABLE_PEER = "enable_peer"

    // 包名等价组，每组内包名视为同一应用
    var packageGroups: List<Set<String>> = listOf()
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
        packageGroups = prefs.getStringSet(KEY_PACKAGE_GROUPS, null)?.mapNotNull {
            it.split("|").map { s->s.trim() }.filter { s->s.isNotBlank() }.toSet().takeIf { set->set.isNotEmpty() }
        } ?: listOf()
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
            .putStringSet(KEY_PACKAGE_GROUPS, packageGroups.map { it.joinToString("|") }.toSet())
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
                val localList = com.xzyht.notifyrelay.data.Notify.NotificationRepository.notifications
                val localDup = localList.any {
                    it.device == "本机" && it.title == title && it.text == text && (now - it.time <= 10_000)
                }
                if (localDup) {
                    android.util.Log.d("NotifyRelay(狂鼠)", "remoteNotificationFilter: 去重命中 title=$title text=$text (本机历史)")
                    return DedupResult(true, false, mappedPkg, title, text, data)
                }
            } catch (_: Exception) {}
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

class RemoteNotificationClickReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        android.util.Log.d("NotifyRelay(狂鼠)", "RemoteNotificationClickReceiver onReceive called")
        val notifyId = intent.getIntExtra("notifyId", 0)
        val pkg = intent.getStringExtra("pkg") ?: run {
            android.util.Log.e("NotifyRelay(狂鼠)", "pkg is null in broadcast")
            return
        }
        val title = intent.getStringExtra("title") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val key = intent.getStringExtra("key") ?: (System.currentTimeMillis().toString() + pkg)
        val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.cancel(notifyId)
        // 跳转应用
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            android.util.Log.w("NotifyRelay(狂鼠)", "No launch intent for package: $pkg")
        }
    }
}


class DeviceForwardFragment : Fragment() {
    // 认证通过设备持久化key
    private val PREFS_NAME = "notifyrelay_device_prefs"
    private val KEY_AUTHED_UUIDS = "authed_device_uuids"

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
    android.util.Log.d("NotifyRelay(狂鼠)", "DeviceForwardScreen Composable launched")
    // 连接弹窗与错误弹窗相关状态
    var showConfirmDialog by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectingDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var connectError by rememberSaveable { mutableStateOf<String?>(null) }
    // 设备认证、删除等逻辑已交由DeviceListFragment统一管理
    val context = androidx.compose.ui.platform.LocalContext.current
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
        chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
    }
    // 只监听全局选中设备
    val selectedDeviceState = GlobalSelectedDeviceHolder.current()
    val selectedDevice = selectedDeviceState.value
    // 复刻lancomm事件监听风格，Compose事件流监听消息
    // 远程通知过滤与复刻到系统通知中心
    DisposableEffect(Unit) {
        val oldHandler = deviceManager.onNotificationDataReceived
        deviceManager.onNotificationDataReceived = { data ->
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
                        com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                    } else {
                        com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                    }
                    oldHandler?.invoke(data)
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
                    com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                    chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                } else {
                    com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                    chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                }
                oldHandler?.invoke(data)
            }
        }
        onDispose { deviceManager.onNotificationDataReceived = oldHandler }
    }

    // 聊天区UI+过滤设置（可折叠）
    var filterExpanded by remember { mutableStateOf(false) }

    // 首次进入时加载持久化设置
    LaunchedEffect(context) {
        NotificationForwardConfig.load(context)
    }
    var filterMode by remember { mutableStateOf(NotificationForwardConfig.filterMode) }
    var enableDedup by remember { mutableStateOf(NotificationForwardConfig.enableDeduplication) }
    var enablePeer by remember { mutableStateOf(NotificationForwardConfig.enablePeerMode) }
    var packageGroupText by remember { mutableStateOf(NotificationForwardConfig.packageGroups.joinToString("\n") { it.joinToString(",") }) }
    var filterListText by remember { mutableStateOf(NotificationForwardConfig.filterList.joinToString("\n") { it.first + (it.second?.let { k-> ","+k } ?: "") }) }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(12.dp)) {
        // 通知过滤设置卡片
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("通知过滤设置", style = textStyles.headline1, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { filterExpanded = !filterExpanded }) {
                    val tintColor = colorScheme.onBackground
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = if (filterExpanded) "收起" else "展开",
                        tint = tintColor
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = filterExpanded) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    // 包名等价组
                    Text("包名等价组(每行一组,逗号分隔):", style = textStyles.body2)
                    OutlinedTextField(
                        value = packageGroupText,
                        onValueChange = { packageGroupText = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        placeholder = { Text("com.a,com.b\ncom.c,com.d") }
                    )
                    // 过滤模式
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("过滤模式:", style = textStyles.body2)
                        androidx.compose.material3.DropdownMenu(
                            expanded = false,
                            onDismissRequest = {},
                            modifier = Modifier.weight(1f)
                        ) {}
                        val modes = listOf("none" to "无", "black" to "黑名单", "white" to "白名单", "peer" to "对等")
                        modes.forEach { (value, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = filterMode == value,
                                onClick = { filterMode = value },
                                label = { Text(label) },
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                    // 黑白名单
                    if (filterMode == "black" || filterMode == "white") {
                        Text("${if (filterMode=="black")"黑" else "白"}名单(每行:包名,可选关键词):", style = textStyles.body2)
                        OutlinedTextField(
                            value = filterListText,
                            onValueChange = { filterListText = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            placeholder = { Text("com.a,关键字\ncom.b") }
                        )
                    }
                    // 对等模式
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = enablePeer,
                            onCheckedChange = { enablePeer = it },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("仅本机存在的应用可接收(对等模式)", style = textStyles.body2)
                    }
                    // 延迟去重
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = enableDedup,
                            onCheckedChange = { enableDedup = it },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Text("10秒内重复通知去重", style = textStyles.body2)
                    }
                    // 应用按钮
                    Button(onClick = {
                        // 解析包名组
                        NotificationForwardConfig.packageGroups = packageGroupText.lines().filter { it.isNotBlank() }.map { it.split(",").map { s->s.trim() }.filter { it.isNotBlank() }.toSet() }.filter { it.isNotEmpty() }
                        NotificationForwardConfig.filterMode = filterMode
                        NotificationForwardConfig.enableDeduplication = enableDedup
                        NotificationForwardConfig.enablePeerMode = enablePeer
                        NotificationForwardConfig.filterList = filterListText.lines().filter { it.isNotBlank() }.map {
                            val arr = it.split(",", limit=2)
                            arr[0].trim() to arr.getOrNull(1)?.trim().takeIf { k->!k.isNullOrBlank() }
                        }
                        NotificationForwardConfig.save(context)
                    }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("应用设置")
                    }
                }
            }
        }

        // 聊天区卡片
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("聊天测试", style = textStyles.headline1, modifier = Modifier.weight(1f))
                androidx.compose.material3.IconButton(onClick = { chatExpanded = !chatExpanded }) {
                    val tintColor = colorScheme.onBackground
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = if (chatExpanded) "收起" else "展开",
                        tint = tintColor
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = chatExpanded) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth().padding(horizontal = 8.dp)) {
                        items(chatHistoryState.value) { msg ->
                            val isSend = msg.startsWith("发送:")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isSend) Arrangement.End else Arrangement.Start
                            ) {
                                androidx.compose.material3.Surface(
                                    color = if (isSend) colorScheme.primaryContainer else colorScheme.secondaryContainer,
                                    shape = androidx.compose.material3.MaterialTheme.shapes.medium,
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        msg.removePrefix("发送:").removePrefix("收到:"),
                                        style = textStyles.body2,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        color = colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        OutlinedTextField(
                            value = chatInput,
                            onValueChange = { chatInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("输入消息...") }
                        )
                        Button(
                            onClick = {
                                // 全部广播：遍历所有已认证设备
                                val allDevices = deviceManager.devices.value.values.map { it.first }
                                val sentAny = allDevices.isNotEmpty() && chatInput.isNotBlank()
                                allDevices.forEach { dev ->
                                    deviceManager.sendNotificationData(dev, chatInput)
                                }
                                if (sentAny) {
                                    com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "发送: $chatInput")
                                    chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                                    chatInput = ""
                                }
                            },
                            enabled = deviceManager.devices.value.isNotEmpty() && chatInput.isNotBlank(),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }
}
                                    }
