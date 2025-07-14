package com.xzyht.notifyrelay.data

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
        android.util.Log.i("NotifyRelay", "NotifyRelayNotificationListenerService onCreate")
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        android.util.Log.i("NotifyRelay", "NotifyRelayNotificationListenerService onBind: intent=$intent")
        return super.onBind(intent)
    }
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.i("NotifyRelay", "[NotifyListener] onNotificationPosted: key=${sbn.key}, package=${sbn.packageName}, id=${sbn.id}, postTime=${sbn.postTime}")
        // 使用协程在后台处理通知，提升实时性且不阻塞主线程
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                android.util.Log.i("NotifyRelay", "[NotifyListener] addNotification success: key=${sbn.key}, title=${sbn.notification.extras.getString("android.title")}, text=${sbn.notification.extras.getString("android.text")}")
            } catch (e: Exception) {
                android.util.Log.e("NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        android.util.Log.i("NotifyRelay", "[NotifyListener] onListenerConnected")
        // 检查监听服务是否启用
        val enabledListeners = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        android.util.Log.i("NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            android.util.Log.w("NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            android.util.Log.i("NotifyRelay", "[NotifyListener] activeNotifications size=${actives.size}")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    try {
                        NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                        android.util.Log.i("NotifyRelay", "[NotifyListener] addNotification (active) success: key=${sbn.key}, title=${sbn.notification.extras.getString("android.title")}, text=${sbn.notification.extras.getString("android.text")}")
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
                    android.util.Log.i("NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications size=${actives.size}")
                    for (sbn in actives) {
                        try {
                            NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                            android.util.Log.i("NotifyRelay", "[NotifyListener] addNotification (timer) success: key=${sbn.key}, title=${sbn.notification.extras.getString("android.title")}, text=${sbn.notification.extras.getString("android.text")}")
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
        // Android 12+ 必须指定前台服务类型，否则会崩溃
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 1073741824 = FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(
                NOTIFY_ID,
                notification,
                1073741824
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    // 保留通知历史，不做移除处理
}
