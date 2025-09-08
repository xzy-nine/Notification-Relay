package com.xzyht.notifyrelay.feature.notification.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 只补发本应用的前台服务通知（必须channelId和id都匹配）
        if (sbn.packageName == applicationContext.packageName
            && sbn.notification.channelId == CHANNEL_ID
            && sbn.id == NOTIFY_ID) {
            if (BuildConfig.DEBUG) Log.w("NotifyRelay", "前台服务通知被移除，自动补发！")
            // 立即补发本服务前台通知
            startForegroundService()
            // 通知DeviceConnectionService延迟补发
            try {
                val intent = android.content.Intent(applicationContext, com.xzyht.notifyrelay.feature.device.service.DeviceConnectionService::class.java)
                intent.action = "com.xzyht.notifyrelay.ACTION_REISSUE_FOREGROUND"
                intent.putExtra("delay", 3000L) // 延迟3秒
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("NotifyRelay", "通知DeviceConnectionService补发前台通知失败", e)
            }
        }
    }
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onTaskRemoved called, rootIntent=$rootIntent")
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
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onCreate called")
        // 确保本地历史缓存已加载，避免首次拉取时判重失效
        NotificationRepository.init(applicationContext)
        super.onCreate()
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onBind called, intent=$intent")
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
        if (BuildConfig.DEBUG) Log.i(tag, "黑影 pkg=$pkg, id=$id, title=$title, text=$text, isOngoing=$isOngoing, flags=$flags, channelId=$channelId, category=$category, postTime=$postTime, sbnKey=${sbn.key}")
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onNotificationPosted called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        // 先判断是否需要转发（如过滤等）
        if (!BackendLocalFilter.shouldForward(sbn, applicationContext)) {
            logSbnDetail("法鸡-黑影 onNotificationPosted 被过滤", sbn)
            return
        }
        // 再写入本地历史（写入本地时回调是否写入过）
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                logSbnDetail("黑影 onNotificationPosted 通过", sbn)
                val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                // 没写入过再转发到远程设备
                if (added) {
                    forwardNotificationToRemoteDevices(sbn)
                } else {
                    if (BuildConfig.DEBUG) Log.i("狂鼠 NotifyRelay", "[NotifyListener] 本地已存在该通知，未转发到远程设备: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("黑影 NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }

    private fun forwardNotificationToRemoteDevices(sbn: StatusBarNotification) {
        if (BuildConfig.DEBUG) Log.i("狂鼠 NotifyRelay", "[NotifyListener] forwardNotificationToRemoteDevices called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        try {
            val deviceManager = com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment.getDeviceManager(applicationContext)
            var appName: String? = null
            try {
                val pm = applicationContext.packageManager
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appName = sbn.packageName
            }

            // 使用整合的消息发送工具
            com.xzyht.notifyrelay.core.util.MessageSender.sendNotificationMessage(
                applicationContext,
                sbn.packageName,
                appName,
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.title"),
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.text"),
                sbn.postTime,
                deviceManager
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay", "自动转发通知到远程设备失败", e)
        }
    }


    override fun onListenerConnected() {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected called")
        super.onListenerConnected()
        // 检查监听服务是否启用
        val enabledListeners = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected: activeNotifications.size=${actives.size}")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    if (!BackendLocalFilter.shouldForward(sbn, applicationContext)) {
                        logSbnDetail("法鸡-黑影 onListenerConnected 被过滤", sbn)
                        continue
                    }
                    try {
                        logSbnDetail("黑影 onListenerConnected 通过", sbn)
                        val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                        if (added) {
                            forwardNotificationToRemoteDevices(sbn)
                        }
                    } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("黑影 NotifyRelay", "onListenerConnected addNotification (active) error", e)
                    }
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] activeNotifications is null")
        }
        // 启动前台服务，保证后台存活
        startForegroundService()
        // 定时拉取活跃通知，保证后台实时性
        foregroundJob?.cancel()
        foregroundJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (true) {
                delay(5000)
                val actives = activeNotifications
                if (actives != null) {
                    if (BuildConfig.DEBUG) Log.v("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications.size=${actives.size}")
                    for (sbn in actives) {
                        if (sbn.packageName == applicationContext.packageName) continue
                        if (!BackendLocalFilter.shouldForward(sbn, applicationContext)) {
                            logSbnDetail("法鸡-黑影 定时拉取被过滤", sbn)
                            continue
                        }
                        try {
                            logSbnDetail("黑影 定时拉取通过", sbn)
                            val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                            if (added) {
                                forwardNotificationToRemoteDevices(sbn)
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.e("黑影 NotifyRelay", "定时拉取 addNotification (timer) error", e)
                        }
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications is null")
                }
            }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onDestroy called")
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通知转发后台服务",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        // 用 NotificationCompat.Builder 替换已废弃的 Notification.Builder
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通知转发后台运行中")
            .setContentText("保证通知实时同步")
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        // Android 12+ 及以上不再指定特殊前台服务类型，避免权限崩溃
        startForeground(NOTIFY_ID, notification)
    }

    // 保留通知历史，不做移除处理
}
