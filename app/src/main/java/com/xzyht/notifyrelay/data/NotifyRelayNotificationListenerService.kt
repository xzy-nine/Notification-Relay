package com.xzyht.notifyrelay.data

import android.app.Notification
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
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 使用协程在后台处理通知，提升实时性且不阻塞主线程
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                }
            }
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
                    for (sbn in actives) {
                        NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(true)
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "通知转发后台服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this)
            .setContentTitle("通知转发后台运行中")
            .setContentText("保证通知实时同步")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setChannelId(CHANNEL_ID)
                }
            }
            .build()
        startForeground(NOTIFY_ID, notification)
    }

    // 保留通知历史，不做移除处理
}
