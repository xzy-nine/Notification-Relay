package com.xzyht.notifyrelay.data.Notify

import android.app.Notification
// android.app.ServiceInfo 仅在 API 33+，此处直接用常量值 1073741824（FOREGROUND_SERVICE_TYPE_SPECIAL_USE）
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        // 重新启动服务，防止被系统杀死
        val restartIntent = android.content.Intent(applicationContext, NotifyRelayNotificationListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }
    override fun onCreate() {
        super.onCreate()
        // android.util.Log.i("NotifyRelay", "NotifyRelayNotificationListenerService onCreate") // 调试日志已注释
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        // android.util.Log.i("NotifyRelay", "NotifyRelayNotificationListenerService onBind: intent=$intent") // 调试日志已注释
        return super.onBind(intent)
    }
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    private fun logSbnDetail(tag: String, sbn: StatusBarNotification) {
        val n = sbn.notification
        val title = NotificationRepository.getStringCompat(n.extras, "android.title")
        val text = NotificationRepository.getStringCompat(n.extras, "android.text")
        val channelId = n.channelId
        val category = n.category
        val id = sbn.id
        val postTime = sbn.postTime
        val pkg = sbn.packageName
        val isOngoing = sbn.isOngoing
        val flags = n.flags
        android.util.Log.i(tag, "pkg=$pkg, id=$id, title=$title, text=$text, isOngoing=$isOngoing, flags=$flags, channelId=$channelId, category=$category, postTime=$postTime, sbnKey=${sbn.key}")
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 使用软编码过滤器
        if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
            logSbnDetail("法鸡-黑影 onNotificationPosted 被过滤", sbn)
            return
        }
        // 使用协程在后台处理通知，提升实时性且不阻塞主线程
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                logSbnDetail("黑影 onNotificationPosted 通过", sbn)
                NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                // ===== 新增：自动转发到所有已认证设备 =====
                try {
                    val deviceManager = com.xzyht.notifyrelay.DeviceForwardFragment.getDeviceManager(applicationContext)
                    var appName: String? = null
                    try {
                        val pm = applicationContext.packageManager
                        val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                        appName = pm.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) {
                        appName = sbn.packageName
                    }
                    val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val authedMap = field.get(deviceManager) as? Map<String, *>
                    if (authedMap != null) {
                        for ((uuid, auth) in authedMap) {
                            val myUuidField = deviceManager.javaClass.getDeclaredField("uuid")
                            myUuidField.isAccessible = true
                            val myUuid = myUuidField.get(deviceManager) as? String
                            if (uuid == myUuid) continue
                            val infoMethod = deviceManager.javaClass.getDeclaredMethod("getDeviceInfo", String::class.java)
                            infoMethod.isAccessible = true
                            val deviceInfo = infoMethod.invoke(deviceManager, uuid) as? com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
                            if (deviceInfo != null) {
                                val payload = com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManagerUtil.buildNotificationJson(
                                    sbn.packageName,
                                    appName,
                                    NotificationRepository.getStringCompat(sbn.notification.extras, "android.title"),
                                    NotificationRepository.getStringCompat(sbn.notification.extras, "android.text"),
                                    sbn.postTime
                                )
                                deviceManager.sendNotificationData(deviceInfo, payload)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NotifyRelay", "自动转发通知到远程设备失败", e)
                }
                // ===== END =====
            } catch (e: Exception) {
                // android.util.Log.e("NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }
// 默认通知过滤器（软编码，可配置）
object DefaultNotificationFilter {
    // 可配置项
    var filterSelf: Boolean = true // 过滤本应用
    var filterOngoing: Boolean = true // 过滤持久化
    var filterNoTitleOrText: Boolean = true // 过滤空标题内容
    var filterImportanceNone: Boolean = true // 过滤IMPORTANCE_NONE
    // 前台/持久化服务文本关键词（可扩展）
    val foregroundKeywords = listOf(
        "正在运行", "服务运行中", "后台运行", "点按即可了解详情", "正在同步", "运行中", "service running", "is running", "tap for more info"
    )
    // 未来可扩展更多条件

    fun shouldForward(sbn: StatusBarNotification, context: Context): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false
        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        // 持久化/前台服务过滤，包含文本关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            val hasForegroundKeyword = foregroundKeywords.any { k -> title.contains(k, true) || text.contains(k, true) }
            if (isOngoing || hasForegroundKeyword) return false
        }
        if (filterImportanceNone) {
            val channelId = sbn.notification.channelId
            if (channelId != null) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = nm.getNotificationChannel(channelId)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
        }
        if (filterNoTitleOrText) {
            if (title.isBlank() || text.isBlank()) return false
        }
        return true
    }
}

    override fun onListenerConnected() {
        super.onListenerConnected()
        // android.util.Log.i("NotifyRelay", "[NotifyListener] onListenerConnected") // 调试日志已注释
        // 检查监听服务是否启用
        val enabledListeners = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        // android.util.Log.i("NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners") // 调试日志已注释
        if (!isEnabled) {
            android.util.Log.w("NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            // android.util.Log.i("NotifyRelay", "[NotifyListener] activeNotifications size=${actives.size}") // 调试日志已注释
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
                        logSbnDetail("法鸡-黑影 onListenerConnected 被过滤", sbn)
                        continue
                    }
                    try {
                        logSbnDetail("黑影 onListenerConnected 通过", sbn)
                        NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                    } catch (e: Exception) {
                        android.util.Log.e("黑影", "onListenerConnected addNotification (active) error", e)
                    }
                }
            }
        } else {
            android.util.Log.w("NotifyRelay", "[NotifyListener] activeNotifications is null")
        }
        // 启动前台服务，保证后台存活
        startForegroundService()
        // 定时拉取活跃通知，保证后台实时性
        foregroundJob?.cancel()
        foregroundJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (true) {
                delay(5000) // 每5秒拉取一次
                val actives = activeNotifications
                if (actives != null) {
                    // android.util.Log.i("NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications size=${actives.size}") // 调试日志已注释
                    for (sbn in actives) {
                        if (sbn.packageName == applicationContext.packageName) continue
                        if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
                            logSbnDetail("法鸡-黑影 定时拉取被过滤", sbn)
                            continue
                        }
                        try {
                            logSbnDetail("黑影 定时拉取通过", sbn)
                            NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                        } catch (e: Exception) {
                            android.util.Log.e("黑影", "定时拉取 addNotification (timer) error", e)
                        }
                    }
                } else {
                    android.util.Log.w("NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications is null")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通知转发后台服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("通知转发后台运行中")
            .setContentText("保证通知实时同步")
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        // Android 12+ 及以上不再指定特殊前台服务类型，避免权限崩溃
        startForeground(NOTIFY_ID, notification)
    }

    // 保留通知历史，不做移除处理
}
