package com.xzyht.notifyrelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifyRelayNotificationListenerService : NotificationListenerService() {
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
    }

    // 保留通知历史，不做移除处理
}
