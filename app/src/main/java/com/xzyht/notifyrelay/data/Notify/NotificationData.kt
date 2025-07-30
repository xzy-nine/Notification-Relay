package com.xzyht.notifyrelay.data.Notify

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Job
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
    val appName: String? = null, // 新增字段
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
    val appName: String? = null, // 新增字段
    val title: String?,
    val text: String?,
    val time: Long,
    val device: String = "本机"
)

// 本地存储实现
class NotificationRecordStore(private val context: Context) {
    private val gson = Gson()
    // 新增：多设备文件缓存
    private val fileCache = mutableMapOf<String, File>()
    private fun getFile(device: String): File {
        return fileCache.getOrPut(device) {
            val safe = if (device == "本机") "local" else device.replace(Regex("[^a-zA-Z0-9_]"), "_")
            File(context.filesDir, "notification_records_${safe}.json")
        }
    }

    private fun readAll(device: String): MutableList<NotificationRecordEntity> {
        val file = getFile(device)
        if (!file.exists()) return mutableListOf()
        val json = file.readText()
        try {
            return gson.fromJson(json, object : TypeToken<MutableList<NotificationRecordEntity>>() {}.type) ?: mutableListOf()
        } catch (e: Exception) {
            // 仅在报错时打印原始内容，避免干扰
            android.util.Log.e("NotifyRelay", "[readAll] 解析 JSON 失败: ${file.absolutePath}, error=${e.message}")
            android.util.Log.e("NotifyRelay", "[readAll] 损坏内容: $json")
            // 自动删除损坏文件，避免循环报错
            try {
                if (file.delete()) {
                    android.util.Log.e("NotifyRelay", "[readAll] 已删除损坏文件: ${file.absolutePath}")
                    // 重新创建空文件以防止后续读取异常
                    file.createNewFile()
                } else {
                    android.util.Log.e("NotifyRelay", "[readAll] 删除损坏文件失败: ${file.absolutePath}")
                }
            } catch (ex: Exception) {
                android.util.Log.e("NotifyRelay", "[readAll] 删除损坏文件异常: ${ex.message}")
            }
            return mutableListOf()
        }
    }

    internal fun writeAll(list: List<NotificationRecordEntity>, device: String) {
        val file = getFile(device)
        try {
            // android.util.Log.i("NotifyRelay", "[writeAll] path=${file.absolutePath}, device=$device, size=${list.size}")
            file.writeText(gson.toJson(list))
        } catch (e: Exception) {
            // android.util.Log.e("NotifyRelay", "[writeAll] 写入失败: path=${file.absolutePath}, device=$device, error=${e.message}\n${e.stackTraceToString()}")
            throw e
        }
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
        writeAll(emptyList(), device)
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
    fun notifyHistoryChanged(deviceKey: String, context: Context) {
        // 只允许刷新 currentDevice 的内容，禁止外部刷新非 currentDevice
        val realKey = currentDevice
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val history = runBlocking { store.getAll(if (realKey == "本机") "local" else realKey) }
            _notificationHistoryFlow.value = history.map {
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
        } catch (e: Exception) {
            _notificationHistoryFlow.value = emptyList()
        }
    }

    /**
     * 新增：以远程设备uuid存储转发通知
     */
    @JvmStatic
    fun addRemoteNotification(packageName: String, title: String, text: String, time: Long, device: String, context: Context) {
        val ctxType = context::class.java.name
        val ctxHash = System.identityHashCode(context)
        android.util.Log.i("NotifyRelay", "[addRemoteNotification] contextType=$ctxType, hash=$ctxHash, device=$device")
        if (context !is android.app.Application) {
            android.util.Log.w("NotifyRelay", "[addRemoteNotification] context is not Application: $ctxType, hash=$ctxHash")
        }
        val key = (time.toString() + packageName + device)
        // 远程推送时应带上 appName，尝试从 text/title 里提取，或后续参数补充
        var appName: String? = null
        // 远程 json 应带有 appName 字段，后续解析时补充
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val fileKey = device // 远程设备uuid
            val oldList = runBlocking { store.getAll(fileKey) }.toMutableList()
            oldList.removeAll { it.key == key }
            // device 字段严格等于 fileKey，保证UI读取时一致
            // 兼容远程 json 结构，appName 字段后续由解析补充
            oldList.add(0, NotificationRecordEntity(
                key = key,
                packageName = packageName,
                appName = appName,
                title = title,
                text = text,
                time = time,
                device = fileKey
            ))
            runBlocking { store.writeAll(oldList, fileKey) }
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "[addRemoteNotification] 写入远程设备json失败: $device, error=${e.message}")
        }
        // 写入后主动推送变更
        notifyHistoryChanged(device, context)
        android.util.Log.i("NotifyRelay", "[addRemoteNotification] after sync (no global add), device=$device")
    }
    /**
     * 新增通知到历史记录（支持监听服务调用）
     * @return true 表示本地历史中原本不存在该通知（即为新增）
     */
    fun addNotification(sbn: StatusBarNotification, context: Context): Boolean {
        val notification = sbn.notification
        val key = sbn.key ?: (sbn.id.toString() + sbn.packageName)
        fun getStringCompat(bundle: android.os.Bundle, key: String): String? {
            val value = bundle.getCharSequence(key)
            return value?.toString()
        }
        // 保证 title 是实际通知标题
        val title = getStringCompat(notification.extras, Notification.EXTRA_TITLE)
        val text = getStringCompat(notification.extras, Notification.EXTRA_TEXT)
        val time = sbn.postTime
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
        val existed = notifications.any { it.key == key }
        notifications.removeAll { it.key == key }
        notifications.add(0, record)
        syncToCache(context)
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
        // android.util.Log.i("NotifyRelay", "[scanDeviceList] found devices: $sorted") //打印本机存储的通知
        deviceList.clear()
        deviceList.addAll(sorted)
    }
    private var maxNotificationsPerDevice: Int = 100
    private var debounceJob: Job? = null
    private const val DEBOUNCE_DELAY = 500L

    fun init(context: Context) {
        try {
            scanDeviceList(context)
            val store = NotifyRelayStoreProvider.getInstance(context)
            // 这里可以添加初始化逻辑
        } catch (e: Exception) {
            notifications.clear()
        }
    }

    /**
     * 移除指定 key 的通知
     */
    fun removeNotification(key: String, context: Context) {
        notifications.removeAll { it.key == key }
    }

    /**
     * 清除指定设备的通知历史
     */
    fun clearDeviceHistory(device: String, context: Context) {
        notifications.removeAll { it.device == device }
        syncToCache(context)
        // 写入后主动推送变更
        notifyHistoryChanged(device, context)
    }

    /**
     * 将当前通知列表同步到本地缓存
     */
    internal fun syncToCache(context: Context) {
        val ctxType = context::class.java.name
        val ctxHash = System.identityHashCode(context)
        // android.util.Log.i("NotifyRelay", "[syncToCache] contextType=$ctxType, hash=$ctxHash")
        try {
            val store = NotifyRelayStoreProvider.getInstance(context)
            val entities = notifications.map {
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
            if (entities.isNotEmpty()) {
                val device = entities.first().device
                runBlocking { store.writeAll(entities, device) }
            }
            scanDeviceList(context)
        } catch (e: Exception) {
            val device = notifications.firstOrNull()?.device ?: "(unknown)"
            android.util.Log.e("NotifyRelay", "通知保存到缓存失败, contextType=$ctxType, hash=$ctxHash, device=$device, error=${e.message}\n${e.stackTraceToString()}")
        }
    }

    /**
     * 获取指定设备的通知列表
     */
    fun getNotificationsByDevice(device: String): List<NotificationRecord> {
        val filtered = notifications.filter { it.device == device }
        android.util.Log.i("NotifyRelay", "[getNotificationsByDevice] device=$device, found=${filtered.size}")
        return filtered
    }
}
