package com.xzyht.notifyrelay

import android.app.Application
import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat

// 通知数据模型
 data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机"
)

object NotificationRepository {
    val notifications = mutableStateListOf<NotificationRecord>()
    var currentDevice: String = "本机"
    val deviceList = listOf("本机") // 预留扩展

    fun addNotification(sbn: StatusBarNotification, context: Context) {
        if (sbn.packageName == context.packageName) return // 忽略自身通知
        if (sbn.notification.category == Notification.CATEGORY_TRANSPORT) return // 忽略媒体通知
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        notifications.add(
            NotificationRecord(
                key = sbn.key,
                packageName = sbn.packageName,
                title = title,
                text = text,
                time = sbn.postTime,
                device = "本机"
            )
        )
    }

    fun removeNotification(key: String) {
        notifications.removeAll { it.key == key }
    }

    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        return notifications.filter { it.device == device }
    }
}
