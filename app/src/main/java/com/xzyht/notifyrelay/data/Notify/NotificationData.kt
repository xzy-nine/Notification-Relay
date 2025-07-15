package com.xzyht.notifyrelay.data.Notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// 通知动作数据模型
data class NotificationAction(
    val icon: Icon? = null,
    val svgResId: Int? = null,
    val title: String?,
    val intent: PendingIntent?
)

// 通知记录数据模型
data class NotificationRecord(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机",
    val actions: List<NotificationAction> = emptyList(),
    val smallIconResId: Int? = null
)

// 数据库存储实体
data class NotificationRecordEntity(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机"
)

// 本地存储实现
class NotificationRecordStore(private val context: Context) {
    private val gson = Gson()
    private val file: File by lazy {
        File(context.filesDir, "notification_records.json")
    }

    private fun readAll(): MutableList<NotificationRecordEntity> {
        if (!file.exists()) return mutableListOf()
        val json = file.readText()
        return gson.fromJson(json, object : TypeToken<MutableList<NotificationRecordEntity>>() {}.type) ?: mutableListOf()
    }

    internal fun writeAll(list: List<NotificationRecordEntity>) {
        file.writeText(gson.toJson(list))
    }

    suspend fun insert(record: NotificationRecordEntity) {
        val list = readAll()
        list.removeAll { it.key == record.key }
        list.add(0, record)
        writeAll(list)
    }

    suspend fun getAll(): List<NotificationRecordEntity> {
        return readAll().sortedByDescending { it.time }
    }

    suspend fun deleteByKey(key: String) {
        val list = readAll()
        list.removeAll { it.key == key }
        writeAll(list)
    }

    suspend fun clearByDevice(device: String) {
        val list = readAll()
        list.removeAll { it.device == device }
        writeAll(list)
    }
}

// 单例提供者
object NotifyRelayStoreProvider {
    @Volatile
    private var INSTANCE: NotificationRecordStore? = null

    fun getInstance(context: Context): NotificationRecordStore {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: NotificationRecordStore(context.applicationContext).also { INSTANCE = it }
        }
    }
}

// 仓库对象，负责通知数据管理
object NotificationRepository {
    /**
     * 新增通知到历史记录（支持监听服务调用）
     */
    fun addNotification(sbn: StatusBarNotification, context: Context) {
        val notification = sbn.notification
        val key = sbn.key ?: (sbn.id.toString() + sbn.packageName)
        fun getStringCompat(bundle: android.os.Bundle, key: String): String? {
            val value = bundle.getCharSequence(key)
            return value?.toString()
        }
        val title = getStringCompat(notification.extras, Notification.EXTRA_TITLE)
        val text = getStringCompat(notification.extras, Notification.EXTRA_TEXT)
        val time = sbn.postTime
        val packageName = sbn.packageName
        val device = "本机"
        // 可扩展解析 actions、icon 等
        val record = NotificationRecord(
            key = key,
            packageName = packageName,
            title = title,
            text = text,
            time = time,
            device = device
        )
        // 去重并插入，仅保留内存，不持久化
        notifications.removeAll { it.key == key }
        notifications.add(0, record)
        // 持久化逻辑已暂时注释
        syncToCache(context)
    }
    val notifications: SnapshotStateList<NotificationRecord> = mutableStateListOf()

    /**
     * 兼容 Bundle 字段类型，支持 CharSequence/SpannableString 自动转 String
     */
    fun getStringCompat(bundle: android.os.Bundle, key: String): String? {
        val value = bundle.get(key)
        return when (value) {
            is String -> value
            is android.text.SpannableString -> value.toString()
            is CharSequence -> value.toString()
            else -> null
        }
    }
    var currentDevice: String = "本机"
    val deviceList = listOf("本机")
    private var maxNotificationsPerDevice: Int = 100
    private var debounceJob: Job? = null
    private const val DEBOUNCE_DELAY = 500L

    fun init(context: Context) {
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
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
            notifications.clear()
        }
    }

    /**
     * 移除指定 key 的通知
     */
    fun removeNotification(key: String, context: Context) {
        notifications.removeAll { it.key == key }
        syncToCache(context)
    }

    /**
     * 清除指定设备的通知历史
     */
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
            runBlocking { store.writeAll(entities) }
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "通知保存到缓存失败", e)
        }
    }

    /**
     * 获取指定设备的通知列表
     */
    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        return notifications.filter { it.device == device }
    }
}
