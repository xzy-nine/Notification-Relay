package com.xzyht.notifyrelay.feature.device.repository

import android.content.Context
import androidx.compose.runtime.MutableState
import com.xzyht.notifyrelay.common.core.repository.AppRepository
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistory
import com.xzyht.notifyrelay.feature.notification.superisland.history.SuperIslandHistoryEntry
import kotlinx.coroutines.delay

// 延迟去重缓存（10秒内）
// private val dedupCache = mutableListOf<Triple<String, String, Long>>() // title, text, time
private val pendingDelayedNotifications = mutableListOf<Triple<String, String, String>>() // title, text, rawData

// 远程通知过滤与复刻到系统通知中心
fun remoteNotificationFilter(data: String, context: Context): com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult {
    return com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.filterRemoteNotification(data, context)
}

// 通知复刻处理函数
suspend fun replicateNotification(
    context: Context,
    result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult,
    chatHistoryState: MutableState<List<String>>? = null,
    startMonitoring: Boolean = true // 是否启动先发后撤回监控（锁屏延迟复刻时可关闭以节省性能）
) {
    try {
    //Logger.d("NotifyRelay(狂鼠)", "[立即]准备复刻通知: title=${result.title} text=${result.text} mappedPkg=${result.mappedPkg}")
    val json = org.json.JSONObject(result.rawData)
    val originalPackage = json.optString("packageName")
    json.put("packageName", result.mappedPkg)
        val pkg = result.mappedPkg
        // 超级岛专属处理：以特殊前缀标记的包名会被视为超级岛数据，走悬浮窗复刻路径
        // 同时根据 type 字段过滤 SI_END 结束包：结束包不再生成新的悬浮窗，只用于关闭已有浮窗
    if (pkg.startsWith("superisland:")) {
            try {
                val type = json.optString("type", "")
                val featureKeyName = json.optString("featureKeyName", "")
                val featureKeyValue = json.optString("featureKeyValue", "")

                // 结束包：只负责关闭对应 featureId 的悬浮窗，不生成任何 UI
                if (type == SuperIslandProtocol.TYPE_END && featureKeyValue.isNotBlank()) {
                    Logger.i("超级岛", "检测到超级岛结束包，准备关闭悬浮窗 featureId=$featureKeyValue")
                    FloatingReplicaManager.dismissBySource(featureKeyValue)
                    return
                }

                val title = json.optString("title")
                val text = json.optString("text")
                val paramV2 = if (json.has("param_v2_raw")) json.optString("param_v2_raw") else null
                val pics = try { json.optJSONObject("pics") } catch (_: Exception) { null }
                val picMap = mutableMapOf<String, String>()
                if (pics != null) {
                    val keys = pics.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        try {
                            val v = pics.optString(k)
                            if (!v.isNullOrEmpty()) picMap[k] = v
                        } catch (_: Exception) {}
                    }
                }
                Logger.i("超级岛", "超级岛: 检测到超级岛数据，准备复刻悬浮窗，pkg=$pkg, title=$title, type=$type")
                // 使用 featureKeyValue 作为 sourceId，确保结束包可以按 featureId 精确关闭
                val sourceId = if (featureKeyName == SuperIslandProtocol.FEATURE_KEY_NAME && featureKeyValue.isNotBlank()) {
                    featureKeyValue
                } else {
                    pkg
                }
                val isLocked = json.optBoolean("isLocked", false)
                FloatingReplicaManager.showFloating(context, sourceId, title, text, paramV2, picMap, json.optString("appName"), isLocked)
                val historyEntry = SuperIslandHistoryEntry(
                    id = System.currentTimeMillis(),
                    originalPackage = originalPackage.takeIf { it.isNotEmpty() },
                    mappedPackage = pkg,
                    appName = json.optString("appName").takeIf { it.isNotEmpty() },
                    title = title.takeIf { it.isNotBlank() },
                    text = text.takeIf { it.isNotBlank() },
                    paramV2Raw = paramV2?.takeIf { it.isNotBlank() },
                    picMap = picMap.toMap(),
                    rawPayload = result.rawData
                )
                try {
                    SuperIslandHistory.append(context, historyEntry)
                } catch (_: Exception) {
                    SuperIslandHistory.append(
                        context,
                        SuperIslandHistoryEntry(
                            id = System.currentTimeMillis(),
                            originalPackage = originalPackage.takeIf { it.isNotEmpty() },
                            mappedPackage = pkg,
                            rawPayload = result.rawData
                        )
                    )
                }
                return
            } catch (e: Exception) {
                Logger.w("超级岛", "超级岛: 复刻失败，回退到普通复刻: ${e.message}")
            }
        }
        val appName = json.optString("appName", pkg)
        val title = json.optString("title")
        val text = json.optString("text")
        val time = json.optLong("time", System.currentTimeMillis())
        var appIcon: android.graphics.Bitmap? = null
        try {
            // 使用统一的图标获取方法，自动处理本地和外部应用
            appIcon = AppRepository.getAppIconWithAutoRequest(context, pkg)

            // 如果初次没有获得图标，等待外部图标同步（最多等待2秒，每100ms轮询一次）。
            // 目的：在第一次获取不到远程同步到的图标时，给它短暂时间到达再复刻，避免某些情况下一直不复刻图标。
            if (appIcon == null) {
                val waitMaxMs = 2000L
                val intervalMs = 100L
                val start = System.currentTimeMillis()
                //Logger.d("NotifyRelay(狂鼠)", "未找到图标，等待最多 ${waitMaxMs}ms 以尝试获取外部图标: $pkg")
                try {
                    while (System.currentTimeMillis() - start < waitMaxMs) {
                        // 尝试从统一方法再次获取
                        appIcon = AppRepository.getAppIconWithAutoRequest(context, pkg)
                        if (appIcon != null) {
                            //Logger.d("NotifyRelay(狂鼠)", "等待期间获取到图标: $pkg")
                            break
                        }
                        delay(intervalMs)
                    }
                } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                    // 协程被取消，记录并重新抛出以尊重取消
                    Logger.w("NotifyRelay(狂鼠)", "等待图标时协程被取消", ce)
                    throw ce
                }
            }

            // 统一方法已经处理了本地和外部图标的获取，无需额外逻辑

            if (appIcon != null) {
                //Logger.d("NotifyRelay(狂鼠)", "成功获取应用图标: $pkg")
            } else {
                //Logger.d("NotifyRelay(狂鼠)", "未能获取到应用图标（将使用系统默认图标回退）: $pkg")
            }
        } catch (e: Exception) {
            Logger.e("NotifyRelay(狂鼠)", "获取应用图标失败: $pkg", e)
            // 如果图标获取失败，尝试使用默认图标
            try {
                val pm = context.packageManager
                // 尝试获取系统默认的应用图标
                val defaultIcon = pm.defaultActivityIcon
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
                //Logger.d("NotifyRelay(狂鼠)", "使用默认应用图标作为回退")
            } catch (fallbackException: Exception) {
                Logger.e("NotifyRelay(狂鼠)", "获取默认图标也失败", fallbackException)
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
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
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
                Logger.w("NotifyRelay(狂鼠)", "setBypassDnd not supported", e)
            }
            notificationManager.createNotificationChannel(channel)
            //Logger.d("NotifyRelay(狂鼠)", "已创建通知渠道: $channelId")
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
        //Logger.d("智能去重", if (startMonitoring) "发送通知并启动监控 - 包名:$pkg, 标题:$title, 内容:$text, 通知ID:$notifyId" else "发送通知（不启用监控） - 包名:$pkg, 标题:$title, 内容:$text, 通知ID:$notifyId")
        // 修复：发出通知前写入dedupCache，确保本地和远程都能去重
        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addToDedupCache(title, text)
        notificationManager.notify(notifyId, builder.build())
        //Logger.d("智能去重", "通知已发送 - 通知ID:$notifyId")

        // 添加到待监控队列，准备撤回机制（可按需关闭）
        if (startMonitoring) {
            com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addPendingNotification(notifyId, title, text, pkg, context)
            //Logger.d("智能去重", "已添加到监控队列 - 通知ID:$notifyId, 将监控15秒内重复")
        }
    } catch (e: Exception) {
        Logger.e("NotifyRelay(狂鼠)", "[立即]远程通知复刻失败", e)
    }
    com.xzyht.notifyrelay.feature.notification.data.ChatMemory.append(context, "收到: ${result.rawData}")
    chatHistoryState?.value = com.xzyht.notifyrelay.feature.notification.data.ChatMemory.getChatHistory(context)
}


