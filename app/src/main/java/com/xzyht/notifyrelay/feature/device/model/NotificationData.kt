package com.xzyht.notifyrelay.feature.device.model

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import com.xzyht.notifyrelay.common.data.PersistenceManager
import com.google.gson.reflect.TypeToken
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecord
import com.xzyht.notifyrelay.feature.notification.model.NotificationRecordEntity

class NotificationRecordStore(private val context: Context) {
    internal fun readAll(device: String): MutableList<NotificationRecordEntity> {
        val typeToken = object : TypeToken<List<NotificationRecordEntity>>() {}
        return PersistenceManager.readNotificationRecords(context, device, typeToken).toMutableList()
    }

    internal fun writeAll(list: List<NotificationRecordEntity>, device: String) {
        PersistenceManager.saveNotificationRecords(context, device, list)
    }

    suspend fun insert(record: NotificationRecordEntity) {
        val list = readAll(record.device)
        list.removeAll { it.key == record.key }
        list.add(0, record)
        writeAll(list, record.device)
    }

    suspend fun getAll(device: String): List<NotificationRecordEntity> {
        return readAll(device).sortedByDescending { it.time }
    }

    suspend fun deleteByKey(key: String, device: String) {
        val list = readAll(device)
        list.removeAll { it.key == key }
        writeAll(list, device)
    }

    suspend fun clearByDevice(device: String) {
        PersistenceManager.clearNotificationRecords(context, device)
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

    // 新增：通知历史 StateFlow，UI可订阅
    private val _notificationHistoryFlow = kotlinx.coroutines.flow.MutableStateFlow<List<NotificationRecord>>(emptyList())
    val notificationHistoryFlow: kotlinx.coroutines.flow.StateFlow<List<NotificationRecord>> get() = _notificationHistoryFlow

    /**
     * 主动刷新指定设备的通知历史并推送到StateFlow
     */
    @Synchronized
    fun notifyHistoryChanged(deviceKey: String, context: Context) {
        // 只允许刷新 currentDevice 的内容，禁止外部刷新非 currentDevice
        val realKey = currentDevice
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val history = runBlocking { store.getAll(if (realKey == "本机") "local" else realKey) }
            val mapped = history.map {
                NotificationRecord(
                    key = it.key,
                    packageName = it.packageName,
                    appName = it.appName,
                    title = it.title,
                    text = it.text,
                    time = it.time,
                    device = it.device
                )
            }
            _notificationHistoryFlow.value = mapped
            // 同时更新内存列表，确保内存与当前设备同步
            notifications.clear()
            notifications.addAll(mapped)
            if (BuildConfig.DEBUG) Log.d("NotifyRelay", "notifyHistoryChanged device=$realKey, 加载数量=${mapped.size}")
        } catch (e: Exception) {
            _notificationHistoryFlow.value = emptyList()
            notifications.clear()
            if (BuildConfig.DEBUG) Log.e("NotifyRelay", "notifyHistoryChanged 失败", e)
        }
    }

    /**
     * 新增：以远程设备uuid存储转发通知
     */
    @JvmStatic
    fun addRemoteNotification(packageName: String, appName: String?, title: String, text: String, time: Long, device: String, context: Context) {
        val ctxType = context::class.java.name
        val ctxHash = System.identityHashCode(context)
        if (BuildConfig.DEBUG) Log.i("秩序之光 狂鼠 NotifyRelay", "[addRemoteNotification] contextType=$ctxType, hash=$ctxHash, device=$device")
        if (context !is android.app.Application) {
            if (BuildConfig.DEBUG) Log.w("秩序之光 狂鼠 NotifyRelay", "[addRemoteNotification] context is not Application: $ctxType, hash=$ctxHash")
        }
        val key = (time.toString() + packageName + device)
        // 使用传入的appName参数
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val fileKey = device // 远程设备uuid
            val oldList = runBlocking { store.getAll(fileKey) }.toMutableList()
            oldList.removeAll { it.key == key }
            // device 字段严格等于 fileKey，保证UI读取时一致
            oldList.add(0, NotificationRecordEntity(
                key = key,
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                time = time,
                device = fileKey
            ))
            // writeAll是同步的，不需要runBlocking
            store.writeAll(oldList, fileKey)
            if (BuildConfig.DEBUG) Log.i("秩序之光 狂鼠 NotifyRelay", "写入远端历史 device=$device, size=${oldList.size}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("秩序之光 狂鼠 NotifyRelay", "[addRemoteNotification] 写入远程设备json失败: $device, error=${e.message}")
        }
        // 写入后主动推送变更
        notifyHistoryChanged(device, context)
        if (BuildConfig.DEBUG) Log.i("秩序之光 狂鼠 NotifyRelay", "[addRemoteNotification] after sync (no global add), device=$device")
    }
    /**
     * 新增通知到历史记录（支持监听服务调用）
     * @return true 表示本地历史中原本不存在该通知（即为新增）
     */
    @Synchronized
    fun addNotification(sbn: StatusBarNotification, context: Context): Boolean {
        val notification = sbn.notification
        val time = sbn.postTime
        val key = (sbn.key ?: (sbn.id.toString() + sbn.packageName)) + "_" + time.toString()
        fun getStringCompat(bundle: android.os.Bundle, key: String): String? {
            val value = bundle.getCharSequence(key)
            return value?.toString()
        }
        // 保证 title 是实际通知标题
        val title = getStringCompat(notification.extras, Notification.EXTRA_TITLE)
        val text = getStringCompat(notification.extras, Notification.EXTRA_TEXT)
        val packageName = sbn.packageName
        val device = "本机"
        var appName: String? = null
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            appName = pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            appName = packageName
        }
        val record = NotificationRecord(
            key = key,
            packageName = packageName,
            appName = appName,
            title = title, // 这里始终用实际通知标题
            text = text,
            time = time,
            device = device
        )
        // 改进判重逻辑：对于活跃通知，允许时间戳有一定差异（5秒内），避免因时间戳微差导致重复
        // 注意：这里不删除历史记录，只是不添加重复的通知
        var existed = false
        val timeTolerance = 5000L // 5秒容差
        notifications.forEach {
            if (it.packageName == packageName &&
                (it.title ?: "") == (title ?: "") &&
                (it.text ?: "") == (text ?: "")) {
                // 时间戳在容差范围内认为相同
                if (Math.abs(it.time - time) <= timeTolerance) {
                    existed = true
                    if (BuildConfig.DEBUG) Log.i("回声 NotifyRelay", "[判重] 发现重复通知，不添加到历史: key=${it.key}, pkg=${it.packageName}, title=${it.title}, text=${it.text}, time差=${Math.abs(it.time - time)}ms")
                }
            }
        }

        // 只有在没有重复时才添加新通知
        if (!existed) {
            notifications.removeAll {
                it.key == key || (
                    it.packageName == packageName &&
                    (it.title ?: "") == (title ?: "") &&
                    (it.text ?: "") == (text ?: "") &&
                    Math.abs(it.time - time) <= timeTolerance
                )
            }
            notifications.add(0, record)
            syncToCache(context)
        }

        notifyHistoryChanged(device, context)
        return !existed
    }
    val notifications: SnapshotStateList<NotificationRecord> = mutableStateListOf()

    /**
     * 兼容 Bundle 字段类型，支持 CharSequence/SpannableString 自动转 String
     */
    fun getStringCompat(bundle: android.os.Bundle, key: String): String? {
        val str = bundle.getString(key)
        if (str != null) return str
        val charSeq = bundle.getCharSequence(key)
        return charSeq?.toString()
    }
    // 当前选中设备
    var currentDevice: String = "本机"
    // 设备列表，自动维护
    val deviceList: MutableList<String> = mutableListOf("本机")

    // 新增：扫描本地所有 notification_records_*.json 文件，自动识别设备
    fun scanDeviceList(context: Context) {
        val files = context.filesDir.listFiles()?.filter { it.name.startsWith("notification_records_") && it.name.endsWith(".json") } ?: emptyList()
        val found = files.mapNotNull {
            val name = it.name.removePrefix("notification_records_").removeSuffix(".json")
            if (name == "local") "本机" else name
        }.toMutableSet()
        found.add("本机")
        // 保证本机在首位
        val sorted = found.sortedWith(compareBy({ if (it == "本机") 0 else 1 }, { it }))
        if (BuildConfig.DEBUG) Log.i("NotifyRelay", "[scanDeviceList] found devices: $sorted") //打印本机存储的通知
        deviceList.clear()
        deviceList.addAll(sorted)
    }
    private var maxNotificationsPerDevice: Int = 100
    private var debounceJob: Job? = null
    private const val DEBOUNCE_DELAY = 500L

    @Synchronized
    fun init(context: Context) {
        try {
            scanDeviceList(context)
            val store = NotifyRelayStoreProvider.getInstance(context)
            // 主动加载本地历史到内存，保证判重有效
            val store2 = NotifyRelayStoreProvider.getInstance(context)
            val localList = store2.readAll("本机").map {
                NotificationRecord(
                    key = it.key,
                    packageName = it.packageName,
                    appName = it.appName,
                    title = it.title,
                    text = it.text,
                    time = it.time,
                    device = it.device
                )
            }
            notifications.clear()
            notifications.addAll(localList)
        } catch (e: Exception) {
            notifications.clear()
        }
    }

    /**
     * 移除指定 key 的通知
     */
    @Synchronized
    fun removeNotification(key: String, context: Context) {
        if (BuildConfig.DEBUG) Log.d("NotifyRelay", "开始删除通知 key=$key")
        val beforeSize = notifications.size

        // 查找要删除的通知，检查其设备类型
        val notificationToRemove = notifications.find { it.key == key }
        val isLocalDevice = notificationToRemove?.device == "本机"

        notifications.removeAll { it.key == key }
        val afterSize = notifications.size
        if (BuildConfig.DEBUG) Log.d("NotifyRelay", "删除通知 key=$key, 删除前数量=$beforeSize, 删除后数量=$afterSize")
        syncToCache(context)

        // 仅在本机设备时清理processedNotifications缓存
        if (isLocalDevice) {
            clearProcessedCache(setOf(key))
        }

        notifyHistoryChanged(currentDevice, context)
    }

    /**
     * 移除指定包名的所有通知（分组删除）
     */
    @Synchronized
    fun removeNotificationsByPackage(packageName: String, context: Context) {
        // 收集要清除的通知key，用于清理缓存
        val notificationsToRemove = notifications.filter { it.packageName == packageName }
        val keysToClear = notificationsToRemove.map { it.key }.toSet()

        // 检查是否有本机设备的通知
        val hasLocalNotifications = notificationsToRemove.any { it.device == "本机" }
        val localKeysToClear = notificationsToRemove.filter { it.device == "本机" }.map { it.key }.toSet()

        notifications.removeAll { it.packageName == packageName }
        syncToCache(context)

        // 仅清理本机设备的缓存
        if (hasLocalNotifications) {
            clearProcessedCache(localKeysToClear)
        }

        notifyHistoryChanged(currentDevice, context)
    }

    /**
     * 清除指定设备的通知历史
     */
    @Synchronized
    fun clearDeviceHistory(device: String, context: Context) {
        // 收集要清除的通知key，用于清理缓存
        val keysToClear = notifications.filter { it.device == device }.map { it.key }.toSet()
        notifications.removeAll { it.device == device }
        syncToCache(context)

        // 对于清除操作，等待写入完成确保数据确实被清除
        if (device == "本机") {
            PersistenceManager.waitForAllWrites(2000) // 等待最多2秒
        }

        // 仅在本机设备时清理processedNotifications缓存（非本机设备没有缓存）
        if (device == "本机") {
            // 对于本机设备，直接清除全部缓存（处理遗留问题）
            clearProcessedCacheAll()
        } else {
            // 对于非本机设备，仅清理对应的key
            clearProcessedCache(keysToClear)
        }

        // 写入后主动推送变更
        notifyHistoryChanged(device, context)
    }

    /**
     * 将当前通知列表同步到本地缓存
     */
    @Synchronized
    internal fun syncToCache(context: Context) {
        val ctxType = context::class.java.name
        val ctxHash = System.identityHashCode(context)
        if (BuildConfig.DEBUG) Log.i("NotifyRelay", "[syncToCache] contextType=$ctxType, hash=$ctxHash")
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val grouped = notifications.groupBy { it.device }
            for ((device, list) in grouped) {
                val entities = list.map {
                    NotificationRecordEntity(
                        key = it.key,
                        packageName = it.packageName,
                        appName = it.appName,
                        title = it.title,
                        text = it.text,
                        time = it.time,
                        device = it.device
                    )
                }
                val fileKey = if (device == "本机") "local" else device
                // 移除不必要的runBlocking，writeAll已经是同步的
                store.writeAll(entities, fileKey)
                if (BuildConfig.DEBUG) Log.i("回声 NotifyRelay", "写入本地历史 device=$device, fileKey=$fileKey, size=${entities.size}")
            }
            scanDeviceList(context)
        } catch (e: Exception) {
            val device = notifications.firstOrNull()?.device ?: "(unknown)"
            if (BuildConfig.DEBUG) Log.e("NotifyRelay", "通知保存到缓存失败, contextType=$ctxType, hash=$ctxHash, device=$device, error=${e.message}\n${e.stackTraceToString()}")
        }
    }

    /**
     * 获取指定设备的通知列表
     */
    @Synchronized
    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        val filtered = notifications.filter { it.device == device }
        if (BuildConfig.DEBUG) Log.i("NotifyRelay", "[getNotificationsByDevice] device=$device, found=${filtered.size}")
        return filtered
    }

    // 缓存清理回调
    private var cacheCleaner: ((Set<String>) -> Unit)? = null

    /**
     * 注册缓存清理器（由监听服务调用）
     */
    fun registerCacheCleaner(cleaner: (Set<String>) -> Unit) {
        cacheCleaner = cleaner
    }

    /**
     * 清理指定通知的缓存
     */
    private fun clearProcessedCache(notificationKeys: Set<String>) {
        cacheCleaner?.invoke(notificationKeys)
    }

    /**
     * 清理全部缓存（仅用于本机设备）
     */
    private fun clearProcessedCacheAll() {
        // 传递空集合表示清除全部缓存
        cacheCleaner?.invoke(emptySet())
    }
}
