package com.xzyht.notifyrelay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationRepository.addNotification(sbn, this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationRepository.removeNotification(sbn.key)
    }
}
