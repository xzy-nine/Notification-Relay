package com.xzyht.notifyrelay.feature.notification.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val MAX_CACHE_SIZE = 2000
        private const val CACHE_CLEANUP_THRESHOLD = 1500
        private const val CACHE_ENTRY_TTL = 24 * 60 * 60 * 1000L // 24小时TTL
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 只补发本应用的前台服务通知（必须channelId和id都匹配）
        if (sbn.packageName == applicationContext.packageName
            && sbn.notification.channelId == CHANNEL_ID
            && sbn.id == NOTIFY_ID) {
            if (BuildConfig.DEBUG) Log.w("NotifyRelay", "前台服务通知被移除，自动补发！")
            // 立即补发本服务前台通知
            startForegroundService()
        } else {
            // 普通通知被移除时，从已处理缓存中移除，允许下次重新处理
            val notificationKey = sbn.key ?: (sbn.id.toString() + sbn.packageName)
            processedNotifications.remove(notificationKey)
            if (BuildConfig.DEBUG) Log.v("NotifyRelay", "通知移除，从缓存中清理: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        }
    }
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onTaskRemoved called, rootIntent=$rootIntent")
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
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onCreate called")
        // 注册缓存清理器
        NotificationRepository.registerCacheCleaner { keysToRemove ->
            if (keysToRemove.isEmpty()) {
                // 空集合表示清除全部缓存
                val beforeSize = processedNotifications.size
                processedNotifications.clear()
                if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] 清理全部processedNotifications缓存，清除前: $beforeSize 个条目")
            } else {
                // 清除指定的缓存项
                val beforeSize = processedNotifications.size
                processedNotifications.keys.removeAll(keysToRemove)
                val afterSize = processedNotifications.size
                if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] 清理processedNotifications缓存，清除前: $beforeSize，清除后: $afterSize，移除 ${keysToRemove.size} 个条目")
            }
        }
        // 确保本地历史缓存已加载，避免首次拉取时判重失效
        NotificationRepository.init(applicationContext)
        // 初始化设备连接管理器并启动发现
        connectionManager = com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment.getDeviceManager(applicationContext)
        connectionManager.startDiscovery()

        // 监听设备状态变化，更新通知
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            connectionManager.devices.collect { deviceMap ->
                // 设备状态发生变化时更新通知
                updateNotification()
            }
        }

        // 监听网络状态变化，更新通知
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                updateNotification()
            }

            override fun onLost(network: android.net.Network) {
                updateNotification()
            }

            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                updateNotification()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        super.onCreate()
    }

    override fun onBind(intent: android.content.Intent?): android.os.IBinder? {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onBind called, intent=$intent")
        return super.onBind(intent)
    }
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    // 设备连接管理器
    private lateinit var connectionManager: com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager

    // 网络监听器
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    // 新增：已处理通知缓存，避免重复处理 (改进版：带时间戳的LRU缓存)
    private val processedNotifications = mutableMapOf<String, Long>()

    private fun cleanupExpiredCacheEntries(currentTime: Long) {
        if (processedNotifications.size <= CACHE_CLEANUP_THRESHOLD) return

        val expiredKeys = processedNotifications.filter { (_, timestamp) ->
            currentTime - timestamp > CACHE_ENTRY_TTL
        }.keys

        if (expiredKeys.isNotEmpty()) {
            processedNotifications.keys.removeAll(expiredKeys)
            if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] 清理过期缓存条目: ${expiredKeys.size} 个")
        }

        // 如果仍然超过最大大小，进行LRU清理
        if (processedNotifications.size > MAX_CACHE_SIZE) {
            val entriesToRemove = processedNotifications.size - MAX_CACHE_SIZE
            val sortedByTime = processedNotifications.entries.sortedBy { it.value }
            val keysToRemove = sortedByTime.take(entriesToRemove).map { it.key }
            processedNotifications.keys.removeAll(keysToRemove)
            if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] LRU清理缓存条目: ${keysToRemove.size} 个")
        }
    }

    private fun processNotification(sbn: StatusBarNotification, checkProcessed: Boolean = false) {
        if (!BackendLocalFilter.shouldForward(sbn, applicationContext, checkProcessed)) {
            logSbnDetail("法鸡-黑影 被过滤", sbn)
            return
        }
        val notificationKey = sbn.key ?: (sbn.id.toString() + sbn.packageName)
        val currentTime = System.currentTimeMillis()

        // 检查缓存和TTL
        if (checkProcessed) {
            val lastProcessedTime = processedNotifications[notificationKey]
            if (lastProcessedTime != null) {
                // 检查是否过期
                if (currentTime - lastProcessedTime < CACHE_ENTRY_TTL) {
                    if (BuildConfig.DEBUG) Log.v("黑影 NotifyRelay", "[NotifyListener] 跳过已处理通知: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
                    return
                } else {
                    // 过期条目，移除
                    processedNotifications.remove(notificationKey)
                }
            }
        }

        // 清理过期缓存条目
        cleanupExpiredCacheEntries(currentTime)

        // 更新缓存
        processedNotifications[notificationKey] = currentTime

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                logSbnDetail("黑影 通过", sbn)
                val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                if (added) {
                    forwardNotificationToRemoteDevices(sbn)
                } else {
                    if (BuildConfig.DEBUG) Log.i("狂鼠 NotifyRelay", "[NotifyListener] 本地已存在该通知，未转发到远程设备: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("黑影 NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onNotificationPosted called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        processNotification(sbn)
    }

    private fun forwardNotificationToRemoteDevices(sbn: StatusBarNotification) {
        if (BuildConfig.DEBUG) Log.i("狂鼠 NotifyRelay", "[NotifyListener] forwardNotificationToRemoteDevices called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        try {
            val deviceManager = com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment.getDeviceManager(applicationContext)
            var appName: String? = null
            try {
                val pm = applicationContext.packageManager
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appName = sbn.packageName
            }

            // 使用整合的消息发送工具
            com.xzyht.notifyrelay.core.util.MessageSender.sendNotificationMessage(
                applicationContext,
                sbn.packageName,
                appName,
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.title"),
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.text"),
                sbn.postTime,
                deviceManager
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("NotifyRelay", "自动转发通知到远程设备失败", e)
        }
    }


    override fun onListenerConnected() {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected called")
        super.onListenerConnected()
        // 检查监听服务是否启用
        val enabledListeners = android.provider.Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected: activeNotifications.size=${actives.size}")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                for (sbn in actives) {
                    processNotification(sbn, true)
                }
            }
        } else {
            if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] activeNotifications is null")
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
                    if (BuildConfig.DEBUG) Log.v("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications.size=${actives.size}")
                    for (sbn in actives) {
                        if (sbn.packageName == applicationContext.packageName) continue
                        processNotification(sbn, true)
                    }
                    // 定期清理过期的缓存，避免内存泄漏
                    cleanupExpiredCacheEntries(System.currentTimeMillis())
                    if (BuildConfig.DEBUG && processedNotifications.size > CACHE_CLEANUP_THRESHOLD) {
                        Log.i("黑影 NotifyRelay", "[NotifyListener] 缓存大小: ${processedNotifications.size}")
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.w("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications is null")
                }
            }
        }
    }

    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.i("黑影 NotifyRelay", "[NotifyListener] onDestroy called")
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
        // 停止设备连接
        try {
            if (this::connectionManager.isInitialized) {
                connectionManager.stopAll()
            }
        } catch (_: Exception) {}
        // 注销网络监听器
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {}
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "通知转发后台服务",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通知监听/转发中")
            .setContentText(getNotificationText())
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        // Android 12+ 及以上不再指定特殊前台服务类型，避免权限崩溃
        startForeground(NOTIFY_ID, notification)
    }

    private fun getNotificationText(): String {
        // 获取在线设备数量
        val onlineDevices = connectionManager.devices.value.values.count { it.second }

        // 优先显示设备连接数，如果有设备连接
        if (onlineDevices > 0) {
            return "当前${onlineDevices}台设备已连接"
        }

        // 没有设备连接时，显示网络状态
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isWifi = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isWifiDirect = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true

        // 如果不是WiFi、以太网或WLAN直连，则认为是移动数据等非局域网
        if (!isWifi && !isEthernet && !isWifiDirect) {
            return "非局域网连接"
        }

        return "无设备在线"
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 根据是否有转发条件决定标题
        val canForward = getNotificationText().let { text ->
            !text.contains("无设备在线") && !text.contains("非局域网连接")
        }
        val title = if (canForward) "通知监听/转发中" else "通知监听中"
        val notification = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getNotificationText())
            .setSmallIcon(com.xzyht.notifyrelay.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFY_ID, notification)
    }

    // 保留通知历史，不做移除处理

    private fun logSbnDetail(prefix: String, sbn: StatusBarNotification) {
        if (BuildConfig.DEBUG) {
            val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")
            val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")
            Log.d("NotifyRelay", "$prefix sbnKey=${sbn.key}, pkg=${sbn.packageName}, id=${sbn.id}, title=$title, text=$text")
        }
    }
}
