package com.xzyht.notifyrelay

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// 通知数据模型
data class NotificationAction(
    val icon: Icon? = null, // 通常为系统Icon类型，界面渲染时可用
    val svgResId: Int? = null, // SVG资源id，优先用于自定义按钮
    val title: String?,
    val intent: android.app.PendingIntent?
)

data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机",
    val actions: List<NotificationAction> = emptyList(), // 媒体控制按钮
    val smallIconResId: Int? = null // 通知小图标资源ID，可为空
)

object NotificationRepository {
    val notifications: SnapshotStateList<NotificationRecord> = mutableStateListOf()
    var currentDevice: String = "本机"
    val deviceList = listOf("本机") // 预留扩展

    private var store: NotificationRecordStore? = null

    fun init(context: Context) {
        if (store == null) {
            store = NotifyRelayStoreProvider.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                val list = store?.getAll() ?: emptyList()
                notifications.clear()
                notifications.addAll(list.map {
                    NotificationRecord(
                        key = it.key,
                        packageName = it.packageName,
                        title = it.title,
                        text = it.text,
                        time = it.time,
                        device = it.device
                    )
                })
            }
        }
    }

    fun addNotification(sbn: StatusBarNotification, context: Context) {
        if (store == null) init(context)
        if (sbn.packageName == context.packageName) return // 忽略自身通知
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val actions = sbn.notification.actions?.map {
            NotificationAction(
                icon = try { it.getIcon() } catch (e: Exception) { null },
                title = it.title?.toString(),
                intent = it.actionIntent
            )
        } ?: emptyList()

        // 移除媒体通知特例，所有通知统一处理
        // 过滤无标题且无内容的通知
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        val record = NotificationRecord(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            time = sbn.postTime,
            device = "本机",
            actions = actions
        )
        val idx = notifications.indexOfFirst { it.key == record.key }
        if (idx >= 0) {
            notifications[idx] = record
        } else {
            notifications.add(record) // 所有通知统一处理，无置顶媒体特例
        }
        CoroutineScope(Dispatchers.IO).launch {
            store?.insert(
                NotificationRecordEntity(
                    key = record.key,
                    packageName = record.packageName,
                    title = record.title,
                    text = record.text,
                    time = record.time,
                    device = record.device
                    // actions/封面图不存储，仅内存展示
                )
            )
        }
    }

    fun removeNotification(key: String, context: Context) {
        if (store == null) init(context)
        notifications.removeAll { it.key == key }
        CoroutineScope(Dispatchers.IO).launch {
            store?.deleteByKey(key)
        }
    }

    fun clearDeviceHistory(device: String, context: Context) {
        if (store == null) init(context)
        notifications.removeAll { it.device == device }
        CoroutineScope(Dispatchers.IO).launch {
            store?.clearByDevice(device)
        }
    }

    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        return notifications.filter { it.device == device }
    }
}
