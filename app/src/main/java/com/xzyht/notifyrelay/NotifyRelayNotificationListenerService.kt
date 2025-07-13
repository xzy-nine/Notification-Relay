package com.xzyht.notifyrelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationRepository.addNotification(sbn, this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 启动时同步所有活跃通知到历史
        val actives = activeNotifications
        if (actives != null) {
            for (sbn in actives) {
                NotificationRepository.addNotification(sbn, this)
            }
        }
    }

    // 保留通知历史，不做移除处理
}
