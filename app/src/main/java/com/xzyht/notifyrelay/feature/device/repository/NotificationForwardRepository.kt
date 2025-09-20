package com.xzyht.notifyrelay.feature.device.repository

import android.content.Context
import android.util.Log
import androidx.compose.runtime.MutableState
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.core.repository.AppRepository

// 延迟去重缓存（10秒内）
// private val dedupCache = mutableListOf<Triple<String, String, Long>>() // title, text, time
private val pendingDelayedNotifications = mutableListOf<Triple<String, String, String>>() // title, text, rawData

// 远程通知过滤与复刻到系统通知中心
fun remoteNotificationFilter(data: String, context: Context): com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult {
    return com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.filterRemoteNotification(data, context)
}

// 通知复刻处理函数
fun replicateNotification(context: Context, result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult, chatHistoryState: MutableState<List<String>>? = null) {
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
            // 优先使用缓存的图标（同步版本）
            appIcon = AppRepository.getAppIconSync(context, pkg)
            if (appIcon == null) {
                // 如果缓存中没有，尝试获取外部应用图标（来自其他设备的同步）
                appIcon = AppRepository.getExternalAppIcon(pkg)
            }
            if (appIcon == null) {
                // 如果还是没有，尝试直接获取（本地安装的应用）
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
            }
            if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "成功获取应用图标: $pkg")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "获取应用图标失败: $pkg", e)
            // 如果图标获取失败，尝试使用默认图标
            try {
                val pm = context.packageManager
                // 尝试获取系统默认的应用图标
                val defaultIcon = pm.getDefaultActivityIcon()
                if (defaultIcon is android.graphics.drawable.BitmapDrawable) {
                    appIcon = defaultIcon.bitmap
                } else {
                    val width = if (defaultIcon.intrinsicWidth > 0) defaultIcon.intrinsicWidth else 96
                    val height = if (defaultIcon.intrinsicHeight > 0) defaultIcon.intrinsicHeight else 96
                    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    defaultIcon.setBounds(0, 0, width, height)
                    defaultIcon.draw(canvas)
                    appIcon = bitmap
                }
                if (BuildConfig.DEBUG) Log.d("NotifyRelay(狂鼠)", "使用默认应用图标作为回退")
            } catch (fallbackException: Exception) {
                if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "获取默认图标也失败", fallbackException)
            }
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
            .setContentTitle("($appName)$title")
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
        if (BuildConfig.DEBUG) Log.d("智能去重", "发送通知并启动监控 - 包名:$pkg, 标题:$title, 内容:$text, 通知ID:$notifyId")
        // 修复：发出通知前写入dedupCache，确保本地和远程都能去重
        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addToDedupCache(title, text)
        notificationManager.notify(notifyId, builder.build())
        if (BuildConfig.DEBUG) Log.d("智能去重", "通知已发送 - 通知ID:$notifyId")

        // 添加到待监控队列，准备撤回机制
        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addPendingNotification(notifyId, title, text, pkg, context)
        if (BuildConfig.DEBUG) Log.d("智能去重", "已添加到监控队列 - 通知ID:$notifyId, 将监控15秒内重复")
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e("NotifyRelay(狂鼠)", "[立即]远程通知复刻失败", e)
    }
    com.xzyht.notifyrelay.feature.notification.data.ChatMemory.append(context, "收到: ${result.rawData}")
    chatHistoryState?.value = com.xzyht.notifyrelay.feature.notification.data.ChatMemory.getChatHistory(context)
}


