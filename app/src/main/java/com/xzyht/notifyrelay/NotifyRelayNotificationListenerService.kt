package com.xzyht.notifyrelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // ...后续可扩展通知转发逻辑...
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // ...后续可扩展通知移除逻辑...
    }
}
