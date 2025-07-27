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

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 过滤本应用自身通知
        if (sbn.packageName == applicationContext.packageName) return
        // 过滤持久化通知（ongoing/persistent）
        if ((sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)) return
        // 过滤优先级为无的通知（IMPORTANCE_NONE）
        val channelId = sbn.notification.channelId
        if (channelId != null) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = nm.getNotificationChannel(channelId)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return
        }
        // 过滤空标题和空内容
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")
        if (title.isNullOrBlank() || text.isNullOrBlank()) return
        // 使用协程在后台处理通知，提升实时性且不阻塞主线程
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                // ===== 新增：自动转发到所有已认证设备 =====
                try {
                    // 获取全局 DeviceConnectionManager 实例（与 DeviceForwardFragment 保持一致）
                    val deviceManager = com.xzyht.notifyrelay.DeviceForwardFragment.getDeviceManager(applicationContext)
                    // 反射获取认证设备表
                    val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val authedMap = field.get(deviceManager) as? Map<String, *>
                    if (authedMap != null) {
                        for ((uuid, auth) in authedMap) {
                            // 跳过本机
                            val myUuidField = deviceManager.javaClass.getDeclaredField("uuid")
                            myUuidField.isAccessible = true
                            val myUuid = myUuidField.get(deviceManager) as? String
                            if (uuid == myUuid) continue
                            // 获取设备信息
                            val infoMethod = deviceManager.javaClass.getDeclaredMethod("getDeviceInfo", String::class.java)
                            infoMethod.isAccessible = true
                            val deviceInfo = infoMethod.invoke(deviceManager, uuid) as? com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
                            if (deviceInfo != null) {
                                // 组装转发内容（统一用json）
                                val payload = com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManagerUtil.buildNotificationJson(
                                    sbn.packageName,
                                    title,
                                    text,
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
                // android.util.Log.e("NotifyRelay", "[NotifyListener] addNotification error", e) // 调试日志已注释
            }
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
                    // 过滤本应用自身通知
                    if (sbn.packageName == applicationContext.packageName) continue
                    // 过滤持久化通知
                    if ((sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)) continue
                    // 过滤优先级为无的通知（IMPORTANCE_NONE）
                    val channelId = sbn.notification.channelId
                    if (channelId != null) {
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        val channel = nm.getNotificationChannel(channelId)
                        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) continue
                    }
                    val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")
                    val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")
                    if (title.isNullOrBlank() || text.isNullOrBlank()) continue // 过滤无标题或无内容
                    try {
                        NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                        // android.util.Log.i("NotifyRelay", "[NotifyListener] addNotification (active) success: key=${sbn.key}, title=$title, text=$text") // 调试日志已注释
                    } catch (e: Exception) {
                        android.util.Log.e("NotifyRelay", "[NotifyListener] addNotification (active) error", e)
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
                        // 过滤本应用自身通知
                        if (sbn.packageName == applicationContext.packageName) continue
                        // 过滤持久化通知
                        if ((sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)) continue
                        // 过滤优先级为无的通知（IMPORTANCE_NONE）
                        val channelId = sbn.notification.channelId
                        if (channelId != null) {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val channel = nm.getNotificationChannel(channelId)
                            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) continue
                        }
                        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")
                        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")
                        if (title.isNullOrBlank() || text.isNullOrBlank()) continue // 过滤无标题或无内容
                        try {
                            NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                            // android.util.Log.i("NotifyRelay", "[NotifyListener] addNotification (timer) success: key=${sbn.key}, title=$title, text=$text") // 已不再需要，注释保留
                        } catch (e: Exception) {
                            android.util.Log.e("NotifyRelay", "[NotifyListener] addNotification (timer) error", e)
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
