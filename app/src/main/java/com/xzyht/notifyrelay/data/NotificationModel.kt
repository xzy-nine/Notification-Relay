
package com.xzyht.notifyrelay.data

import com.xzyht.notifyrelay.data.NotificationRecordEntity

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

    // 默认每设备最多100条通知，支持动态调整
    private var maxNotificationsPerDevice: Int = 100

    // 防抖持久化相关变量
    private var debounceJob: kotlinx.coroutines.Job? = null
    private const val DEBOUNCE_DELAY = 500L // 500ms内多次变动只写一次

    /**
     * 初始化：从本地缓存加载历史通知
     */
    fun init(context: Context) {
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            // 只加载本机设备历史
            val entities = runBlocking { store.getAll().filter { it.device == "本机" } }
            notifications.clear()
            notifications.addAll(entities.map {
                NotificationRecord(
                    key = it.key,
                    packageName = it.packageName,
                    title = it.title,
                    text = it.text,
                    time = it.time,
                    device = it.device
                )
            })
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "历史通知加载失败", e)
        }
    }
    /**
     * 设置每个设备的通知缓存上限
     * TODO: 可扩展为持久化配置或多设备自定义
     */
    fun setMaxNotificationsPerDevice(max: Int) {
        maxNotificationsPerDevice = max
    }

    fun addNotification(sbn: StatusBarNotification, context: Context) {
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
        // 按设备分组，超出上限则移除最旧的
        val deviceRecords = notifications.filter { it.device == record.device }
        if (deviceRecords.size >= maxNotificationsPerDevice) {
            val oldest = deviceRecords.minByOrNull { it.time }
            if (oldest != null) notifications.remove(oldest)
        }
        notifications.add(record)
        debounceJob?.cancel()
        debounceJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(DEBOUNCE_DELAY)
            syncToCache(context)
        }
    }

    fun removeNotification(key: String, context: Context) {
        notifications.removeAll { it.key == key }
        syncToCache(context)
    }

    fun clearDeviceHistory(device: String, context: Context) {
        notifications.removeAll { it.device == device }
        syncToCache(context)
    }
    /**
     * 将当前通知列表同步到本地缓存
     */
    private fun syncToCache(context: Context) {
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val entities = notifications.map {
                NotificationRecordEntity(
                    key = it.key,
                    packageName = it.packageName,
                    title = it.title,
                    text = it.text,
                    time = it.time,
                    device = it.device
                )
            }
            kotlinx.coroutines.runBlocking { store.writeAll(entities) }
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "通知保存到缓存失败", e)
        }
    }

    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        return notifications.filter { it.device == device }
    }
}
