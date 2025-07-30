package com.xzyht.notifyrelay.data.Notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onTaskRemoved called, rootIntent=$rootIntent")
        super.onTaskRemoved(rootIntent)
        // 重新启动服务，防止被系统杀死
        val restartIntent = android.content.Intent(applicationContext, NotifyRelayNotificationListenerService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }
    override fun onCreate() {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onCreate called")
        super.onCreate()
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onBind called, intent=$intent")
        return super.onBind(intent)
    }
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    private fun logSbnDetail(tag: String, sbn: StatusBarNotification) {
        val n = sbn.notification
        val title = NotificationRepository.getStringCompat(n.extras, "android.title")
        val text = NotificationRepository.getStringCompat(n.extras, "android.text")
        val channelId = n.channelId
        val category = n.category
        val id = sbn.id
        val postTime = sbn.postTime
        val pkg = sbn.packageName
        val isOngoing = sbn.isOngoing
        val flags = n.flags
        android.util.Log.i(tag, "黑影 pkg=$pkg, id=$id, title=$title, text=$text, isOngoing=$isOngoing, flags=$flags, channelId=$channelId, category=$category, postTime=$postTime, sbnKey=${sbn.key}")
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onNotificationPosted called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
            logSbnDetail("法鸡-黑影 onNotificationPosted 被过滤", sbn)
            return
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                logSbnDetail("黑影 onNotificationPosted 通过", sbn)
                val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                if (added) {
                    forwardNotificationToRemoteDevices(sbn)
                }
            } catch (e: Exception) {
                android.util.Log.e("黑影 NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }

    private fun forwardNotificationToRemoteDevices(sbn: StatusBarNotification) {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] forwardNotificationToRemoteDevices called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        try {
            val deviceManager = com.xzyht.notifyrelay.DeviceForwardFragment.getDeviceManager(applicationContext)
            var appName: String? = null
            try {
                val pm = applicationContext.packageManager
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appName = sbn.packageName
            }
            val field = deviceManager.javaClass.getDeclaredField("authenticatedDevices")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val authedMap = field.get(deviceManager) as? Map<String, *>
            if (authedMap != null) {
                for ((uuid, auth) in authedMap) {
                    val myUuidField = deviceManager.javaClass.getDeclaredField("uuid")
                    myUuidField.isAccessible = true
                    val myUuid = myUuidField.get(deviceManager) as? String
                    if (uuid == myUuid) continue
                    val infoMethod = deviceManager.javaClass.getDeclaredMethod("getDeviceInfo", String::class.java)
                    infoMethod.isAccessible = true
                    val deviceInfo = infoMethod.invoke(deviceManager, uuid) as? com.xzyht.notifyrelay.data.deviceconnect.DeviceInfo
                    if (deviceInfo != null) {
                        val payload = com.xzyht.notifyrelay.data.deviceconnect.DeviceConnectionManagerUtil.buildNotificationJson(
                            sbn.packageName,
                            appName,
                            NotificationRepository.getStringCompat(sbn.notification.extras, "android.title"),
                            NotificationRepository.getStringCompat(sbn.notification.extras, "android.text"),
                            sbn.postTime
                        )
                        deviceManager.sendNotificationData(deviceInfo, payload)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NotifyRelay", "自动转发通知到远程设备失败", e)
        }
    }
// 默认通知过滤器（软编码，可配置）
object DefaultNotificationFilter {
    // 内置文本黑名单关键词（不可删除，支持标题+内容联合匹配）
    private val builtinCustomKeywords = setOf("米家 设备状态")
    private const val KEY_ENABLED_FOREGROUND_KEYWORDS = "enabled_foreground_keywords"

    // 可配置项
    var filterSelf: Boolean = true // 过滤本应用
    var filterOngoing: Boolean = true // 过滤持久化
    var filterNoTitleOrText: Boolean = true // 过滤空标题内容
    var filterImportanceNone: Boolean = true // 过滤IMPORTANCE_NONE
    var filterMiPushGroupSummary: Boolean = true // 过滤mipush群组引导消息
    var filterSensitiveHidden: Boolean = true // 过滤敏感内容被隐藏的通知
    // 关键词持久化相关
    private const val PREFS_NAME = "notifyrelay_filter_prefs"
    private const val KEY_FOREGROUND_KEYWORDS = "foreground_keywords"

    // 服务相关关键词，仅用于持久通知过滤
    private val serviceKeywords = setOf(
        "正在运行", "服务运行中", "后台运行", "点按即可了解详情", "正在同步", "运行中", "service running", "is running", "tap for more info"
    )

    // 获取自定义文本关键词集合（不含服务相关关键词）
    fun getForegroundKeywords(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)
        // 自动补全内置关键词（不可删除）
        return if (saved != null) builtinCustomKeywords + saved else builtinCustomKeywords
    }

    fun getEnabledForegroundKeywords(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)
        val all = getForegroundKeywords(context)
        // 首次无任何启用集时，默认启用全部（含内置）；否则严格以持久化内容为准（内置项可被禁用）
        return if (enabled == null) all else enabled.intersect(all)
    }

    fun setKeywordEnabled(context: Context, keyword: String, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = getForegroundKeywords(context)
        var enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: all.toMutableSet()
        if (enabled) enabledSet.add(keyword) else enabledSet.remove(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun addForegroundKeyword(context: Context, keyword: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        set.add(keyword)
        prefs.edit().putStringSet(KEY_FOREGROUND_KEYWORDS, set).apply()
        // 新增关键词默认启用
        val enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: set.toMutableSet()
        enabledSet.add(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun removeForegroundKeyword(context: Context, keyword: String) {
        // 内置关键词不可删除
        if (builtinCustomKeywords.contains(keyword)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        set.remove(keyword)
        prefs.edit().putStringSet(KEY_FOREGROUND_KEYWORDS, set).apply()
        // 同时移除启用状态
        val enabledSet = prefs.getStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, null)?.toMutableSet() ?: mutableSetOf()
        enabledSet.remove(keyword)
        prefs.edit().putStringSet(KEY_ENABLED_FOREGROUND_KEYWORDS, enabledSet).apply()
    }

    fun shouldForward(sbn: StatusBarNotification, context: Context): Boolean {
        if (filterSelf && sbn.packageName == context.packageName) return false
        val flags = sbn.notification.flags
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        // 过滤mipush群组引导消息（title=新消息 且 text=你有一条新消息）
        if (filterMiPushGroupSummary && title == "新消息" && text == "你有一条新消息") return false
        // 过滤敏感内容被隐藏的通知（text=已隐藏敏感通知）
        if (filterSensitiveHidden && text == "已隐藏敏感通知") return false
        // 持久化/前台服务过滤，包含服务相关关键词
        if (filterOngoing) {
            val isOngoing = sbn.isOngoing || (flags and Notification.FLAG_ONGOING_EVENT) != 0 || (flags and 0x00000200) != 0
            val hasServiceKeyword = serviceKeywords.any { k -> title.contains(k, true) || text.contains(k, true) }
            if (isOngoing || hasServiceKeyword) return false
        }
        // 文本关键词黑名单增强：支持“标题关键词 内容关键词”格式，前面匹配标题，后面匹配内容
        val enabledKeywords = getEnabledForegroundKeywords(context)
        for (k in enabledKeywords) {
            val parts = k.split(" ", limit = 2)
            if (parts.size == 2) {
                val t = parts[0].trim()
                val c = parts[1].trim()
                if (t.isNotEmpty() && c.isNotEmpty() && title.contains(t, true) && text.contains(c, true)) {
                    return false
                }
            }
        }
        // 兼容单关键词过滤（只要标题或内容包含即过滤）
        if (enabledKeywords.any { k -> title.contains(k, true) || text.contains(k, true) }) return false
        if (filterImportanceNone) {
            val channelId = sbn.notification.channelId
            if (channelId != null) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = nm.getNotificationChannel(channelId)
                if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            }
        }
        if (filterNoTitleOrText) {
            if (title.isBlank() || text.isBlank()) return false
        }
        return true
    }
}

    override fun onListenerConnected() {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected called")
        super.onListenerConnected()
        // 检查监听服务是否启用
        val enabledListeners = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            android.util.Log.w("黑影 NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected: activeNotifications.size=${actives.size}")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
                        logSbnDetail("法鸡-黑影 onListenerConnected 被过滤", sbn)
                        continue
                    }
                    try {
                        logSbnDetail("黑影 onListenerConnected 通过", sbn)
                        val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                        if (added) {
                            forwardNotificationToRemoteDevices(sbn)
                        }
                    } catch (e: Exception) {
                    android.util.Log.e("黑影 NotifyRelay", "onListenerConnected addNotification (active) error", e)
                    }
                }
            }
        } else {
            android.util.Log.w("黑影 NotifyRelay", "[NotifyListener] activeNotifications is null")
        }
        // 启动前台服务，保证后台存活
        startForegroundService()
        // 定时拉取活跃通知，保证后台实时性
        foregroundJob?.cancel()
        foregroundJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            while (true) {
                delay(5000)
                val actives = activeNotifications
                if (actives != null) {
                    android.util.Log.v("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications.size=${actives.size}")
                    for (sbn in actives) {
                        if (sbn.packageName == applicationContext.packageName) continue
                        if (!DefaultNotificationFilter.shouldForward(sbn, applicationContext)) {
                            logSbnDetail("法鸡-黑影 定时拉取被过滤", sbn)
                            continue
                        }
                        try {
                            logSbnDetail("黑影 定时拉取通过", sbn)
                            val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                            if (added) {
                                forwardNotificationToRemoteDevices(sbn)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("黑影 NotifyRelay", "定时拉取 addNotification (timer) error", e)
                        }
                    }
                } else {
                    android.util.Log.w("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications is null")
                }
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.i("黑影 NotifyRelay", "[NotifyListener] onDestroy called")
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通知转发后台服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("通知转发后台运行中")
            .setContentText("保证通知实时同步")
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        // Android 12+ 及以上不再指定特殊前台服务类型，避免权限崩溃
        startForeground(NOTIFY_ID, notification)
    }

    // 保留通知历史，不做移除处理
}
