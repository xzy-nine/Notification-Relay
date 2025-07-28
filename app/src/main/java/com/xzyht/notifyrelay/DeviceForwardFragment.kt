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
import androidx.compose.foundation.background
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.basic.Checkbox

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
                val localList = com.xzyht.notifyrelay.data.Notify.NotificationRepository.notifications
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
    val context2 = LocalContext.current
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
                        com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                    } else {
                        com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "收到: ${result.rawData}")
                        chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
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
        // 通知过滤设置卡片
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(2.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorScheme.surface
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    "通知过滤设置",
                    style = textStyles.headline1,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.IconButton(onClick = { filterExpanded = !filterExpanded }) {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = if (filterExpanded) "收起" else "展开",
                        tint = colorScheme.onSurface
                    )
                }
            }
            androidx.compose.animation.AnimatedVisibility(visible = filterExpanded) {
                Column(Modifier.fillMaxWidth().padding(8.dp)) {
                    // 包名等价功能总开关（文字在前，开关在后，增加下方间距）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Text("启用包名等价映射", style = textStyles.body2, color = colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        androidx.compose.material3.Switch(
                            checked = enablePackageGroupMapping,
                            onCheckedChange = { enablePackageGroupMapping = it },
                            modifier = Modifier.size(width = 24.dp, height = 12.dp),
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.primary,
                                uncheckedThumbColor = colorScheme.outline,
                                checkedTrackColor = colorScheme.primaryContainer,
                                uncheckedTrackColor = colorScheme.surface
                            )
                        )
                    }
                    // 合并组渲染（默认组+自定义组）
    allGroups.forEachIndexed { idx, group ->
        val groupEnabled = enablePackageGroupMapping
        androidx.compose.material3.Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .then(
                    if (groupEnabled) Modifier.clickable {
                        allGroupEnabled = allGroupEnabled.toMutableList().apply { set(idx, !allGroupEnabled[idx]) }
                    } else Modifier
                ),
            shape = androidx.compose.material3.MaterialTheme.shapes.small,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(1.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorScheme.surfaceVariant
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
                        Button(
                            onClick = { showAppPickerForGroup = true to idx },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(24.dp),
                            enabled = enablePackageGroupMapping
                        ) {
                            Text("+", style = textStyles.button)
                        }
                        Button(
                            onClick = {
                                allGroups = allGroups.toMutableList().apply { removeAt(idx) }
                                allGroupEnabled = allGroupEnabled.toMutableList().apply { removeAt(idx) }
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(24.dp).padding(start = 2.dp),
                            enabled = enablePackageGroupMapping
                        ) {
                            Text("×", style = textStyles.button, color = colorScheme.primary)
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
                    // 添加新组按钮
                Button(
                    onClick = {
                        allGroups = allGroups.toMutableList().apply { add(mutableListOf()) }
                        allGroupEnabled = allGroupEnabled.toMutableList().apply { add(true) }
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                    enabled = enablePackageGroupMapping
                ) {
                    Text("添加新组", style = textStyles.button)
                }
                    // 应用选择弹窗
                    if (showAppPickerForGroup.first) {
                        val groupIdx = showAppPickerForGroup.second
                        val pm = context.packageManager
                        val allApps = remember { pm.getInstalledApplications(0).sortedBy { pm.getApplicationLabel(it).toString() } }
                        val filteredApps = allApps.filter { appSearchQuery.isBlank() || pm.getApplicationLabel(it).toString().contains(appSearchQuery, true) || appSearchQuery in it.packageName }
                        AlertDialog(
                            onDismissRequest = { showAppPickerForGroup = false to -1; appSearchQuery = "" },
                            title = { Text("选择应用", style = textStyles.headline2) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = appSearchQuery,
                                        onValueChange = { appSearchQuery = it },
                                        label = { Text("搜索应用/包名", style = textStyles.body2) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                        colors = TextFieldDefaults.colors(),
                                        textStyle = textStyles.body2
                                    )
                                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                        items(filteredApps) { appInfo ->
                                            val pkg = appInfo.packageName
                                            val label = pm.getApplicationLabel(appInfo).toString()
                                            val icon = pm.getApplicationIcon(appInfo)
                                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                                allGroups = allGroups.toMutableList().apply {
                                                    if (groupIdx in indices && !this[groupIdx].contains(pkg)) this[groupIdx] = this[groupIdx] + pkg
                                                }
                                                showAppPickerForGroup = false to -1; appSearchQuery = ""
                                            }.padding(4.dp)) {
                                                if (icon is android.graphics.drawable.BitmapDrawable) {
                                                    Image(bitmap = icon.bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(22.dp))
                                                }
                                                Text(label, style = textStyles.body2, color = colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
                                                Text(pkg, style = textStyles.body2, color = colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp))
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showAppPickerForGroup = false to -1; appSearchQuery = "" }) { Text("关闭", style = textStyles.button) }
                            }
                        )
                    }
                    // 过滤模式
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("过滤模式:", style = textStyles.body2, color = colorScheme.onSurface)
                        val modes = listOf("none" to "无", "black" to "黑名单", "white" to "白名单", "peer" to "对等")
                        modes.forEach { (value, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = filterMode == value,
                                onClick = { filterMode = value },
                                label = {
                                    Text(label, style = textStyles.body2, color = if (filterMode == value) colorScheme.onPrimary else colorScheme.onSurface)
                                },
                                modifier = Modifier.padding(horizontal = 2.dp),
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colorScheme.primary,
                                    containerColor = colorScheme.surface,
                                    labelColor = colorScheme.onSurface,
                                    selectedLabelColor = colorScheme.onPrimary
                                )
                            )
                        }
                    }
                    // 黑白名单
                    if (filterMode == "black" || filterMode == "white") {
                        Text(
                            "${if (filterMode=="black")"黑" else "白"}名单(每行:包名,可选关键词):",
                            style = textStyles.body2,
                            color = colorScheme.onSurface
                        )
                        OutlinedTextField(
                            value = filterListText,
                            onValueChange = { filterListText = it },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            placeholder = {
                                Text("com.a,关键字\ncom.b", color = colorScheme.onSurfaceSecondary, style = textStyles.body2)
                            },
                            colors = TextFieldDefaults.colors(),
                            textStyle = textStyles.body2
                        )
                    }
                    // 对等模式Switch已移除，避免与FilterChip重复
                    // 延迟去重
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(
                            checked = enableDedup,
                            onCheckedChange = { enableDedup = it },
                            modifier = Modifier.padding(end = 4.dp),
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = colorScheme.primary,
                                uncheckedThumbColor = colorScheme.outline,
                                checkedTrackColor = colorScheme.primaryContainer,
                                uncheckedTrackColor = colorScheme.surface
                            )
                        )
                        Text("10秒内重复通知去重", style = textStyles.body2, color = colorScheme.onSurface)
                    }
                    // 应用按钮
                    Button(
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
                        modifier = Modifier.padding(top = 8.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary
                        )
                    ) {
                        Text("应用设置", style = textStyles.button, color = colorScheme.onPrimary)
                    }
                }
            }
        }

        // 聊天区卡片
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.material3.MaterialTheme.shapes.medium,
            elevation = androidx.compose.material3.CardDefaults.cardElevation(2.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = colorScheme.surface
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(
                    "聊天测试",
                    style = textStyles.headline1,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.IconButton(onClick = { chatExpanded = !chatExpanded }) {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = if (chatExpanded) "收起" else "展开",
                        tint = colorScheme.onSurface
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
                                        color = if (isSend) colorScheme.onPrimaryContainer else colorScheme.onSecondaryContainer
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
                            placeholder = {
                                Text("输入消息...", color = colorScheme.onSurfaceSecondary, style = textStyles.body2)
                            },
                            colors = TextFieldDefaults.colors(),
                            textStyle = textStyles.body2
                        )
                        Button(
                            onClick = {
                                // 全部广播：遍历所有已认证设备
                                val allDevices = deviceManager.devices.value.values.map { it.first }
                                val sentAny = allDevices.isNotEmpty() && chatInput.isNotBlank()
                                // 构建标准 JSON
                                val pkgName = context.packageName
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
                                    com.xzyht.notifyrelay.data.Notify.ChatMemory.append(context, "发送: $chatInput")
                                    chatHistoryState.value = com.xzyht.notifyrelay.data.Notify.ChatMemory.getChatHistory(context)
                                    chatInput = ""
                                }
                            },
                            enabled = deviceManager.devices.value.isNotEmpty() && chatInput.isNotBlank(),
                            modifier = Modifier.align(Alignment.CenterVertically),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = colorScheme.primary,
                                contentColor = colorScheme.onPrimary
                            )
                        ) {
                            Text("发送", style = textStyles.button, color = colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }
}}