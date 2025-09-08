package com.xzyht.notifyrelay.feature.device.repository

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.util.AppListHelper
import com.xzyht.notifyrelay.common.data.StorageManager

// 通用包名映射、去重、黑白名单/对等模式配置
object NotificationForwardConfig {
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
    fun load(context: Context) {
        enablePackageGroupMapping = StorageManager.getBoolean(context, "enable_package_group_mapping", true, StorageManager.PrefsType.FILTER)
        val defaultEnabledStr = StorageManager.getString(context, "default_group_enabled", "1,1,1", StorageManager.PrefsType.FILTER)
        val defaultEnabled = defaultEnabledStr.split(",").map { it == "1" }
        defaultGroupEnabled = defaultEnabled.toMutableList().apply {
            while (size < defaultPackageGroups.size) add(true)
        }
        val customGroupsStr = StorageManager.getStringSet(context, KEY_PACKAGE_GROUPS, emptySet(), StorageManager.PrefsType.FILTER)
        val customGroups = customGroupsStr.mapNotNull {
            it.split("|").map { s->s.trim() }.filter { s->s.isNotBlank() }.toMutableList().takeIf { set->set.isNotEmpty() }
        }.toMutableList()
        customPackageGroups = customGroups
        val customEnabledStr = StorageManager.getString(context, "custom_group_enabled", "", StorageManager.PrefsType.FILTER)
        customGroupEnabled = if (customEnabledStr.isNotEmpty()) {
            customEnabledStr.split(",").map { it == "1" }.toMutableList().apply {
                while (size < customGroups.size) add(true)
            }
        } else MutableList(customGroups.size) { true }
        filterMode = StorageManager.getString(context, KEY_FILTER_MODE, "none", StorageManager.PrefsType.FILTER)
        enableDeduplication = StorageManager.getBoolean(context, KEY_ENABLE_DEDUP, true, StorageManager.PrefsType.FILTER)
        enablePeerMode = StorageManager.getBoolean(context, KEY_ENABLE_PEER, false, StorageManager.PrefsType.FILTER)
        val filterListStr = StorageManager.getStringSet(context, KEY_FILTER_LIST, emptySet(), StorageManager.PrefsType.FILTER)
        filterList = filterListStr.map {
            val arr = it.split("|", limit=2)
            arr[0] to arr.getOrNull(1)?.takeIf { k->k.isNotBlank() }
        }
    }
    // 保存设置
    fun save(context: Context) {
        StorageManager.putBoolean(context, "enable_package_group_mapping", enablePackageGroupMapping, StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, "default_group_enabled", defaultGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, "custom_group_enabled", customGroupEnabled.joinToString(",") { if (it) "1" else "0" }, StorageManager.PrefsType.FILTER)
        StorageManager.putStringSet(context, KEY_PACKAGE_GROUPS, customPackageGroups.map { it.joinToString("|") }.toSet(), StorageManager.PrefsType.FILTER)
        StorageManager.putString(context, KEY_FILTER_MODE, filterMode, StorageManager.PrefsType.FILTER)
        StorageManager.putBoolean(context, KEY_ENABLE_DEDUP, enableDeduplication, StorageManager.PrefsType.FILTER)
        StorageManager.putBoolean(context, KEY_ENABLE_PEER, enablePeerMode, StorageManager.PrefsType.FILTER)
        StorageManager.putStringSet(context, KEY_FILTER_LIST, filterList.map { it.first + (it.second?.let { k->"|"+k } ?: "") }.toSet(), StorageManager.PrefsType.FILTER)
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

// 远程通知过滤与复刻到系统通知中心
fun remoteNotificationFilter(data: String, context: Context): com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult {
    return com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.filterRemoteNotification(data, context)
}

// 通知复刻处理函数
fun replicateNotification(context: Context, result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult, chatHistoryState: MutableState<List<String>>) {
    try {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "[立即]准备复刻通知: title=${result.title} text=${result.text} mappedPkg=${result.mappedPkg}")
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
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "获取应用图标失败", e)
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
                if (BuildConfig.DEBUG) Log.w("NotifyRelay(狂鼠)", "setBypassDnd not supported", e)
            }
            notificationManager.createNotificationChannel(channel)
            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "已创建通知渠道: $channelId")
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
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "[立即]准备发送通知: id=$notifyId, title=$title, text=$text, pkg=$pkg")
        // 修复：发出通知前写入dedupCache，确保本地和远程都能去重
        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addToDedupCache(title, text)
        notificationManager.notify(notifyId, builder.build())
        if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "[立即]已调用notify: id=$notifyId")
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "[立即]远程通知复刻失败", e)
    }
    com.xzyht.notifyrelay.feature.notification.data.ChatMemory.append(context, "收到: ${result.rawData}")
    chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.data.ChatMemory.getChatHistory(context)
}

// 延迟通知复刻处理函数
suspend fun replicateNotificationDelayed(context: Context, result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult, chatHistoryState: MutableState<List<String>>) {
    kotlinx.coroutines.delay(10_000)
    var shouldShow = true
    shouldShow = !com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.isInDedupCache(result.title, result.text)
    if (shouldShow) {
        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addToDedupCache(result.title, result.text)
        replicateNotification(context, result, chatHistoryState)
    } else {
        com.xzyht.notifyrelay.feature.notification.data.ChatMemory.append(context, "收到: ${result.rawData}")
        chatHistoryState.value = com.xzyht.notifyrelay.feature.notification.data.ChatMemory.getChatHistory(context)
    }
}
