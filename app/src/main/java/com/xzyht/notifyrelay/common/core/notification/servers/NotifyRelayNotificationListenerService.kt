package com.xzyht.notifyrelay.common.core.notification.servers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.app.Notification
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.R
import com.xzyht.notifyrelay.common.core.sync.MessageSender
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import com.xzyht.notifyrelay.feature.notification.backend.BackendLocalFilter
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandManager
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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
            Logger.w("NotifyRelay", "前台服务通知被移除，自动补发！")
            // 立即补发本服务前台通知
            startForegroundService()
        } else {
            // 普通通知被移除时，从已处理缓存中移除，允许下次重新处理
            val notificationKey = sbn.key ?: (sbn.id.toString() + sbn.packageName)
            processedNotifications.remove(notificationKey)
            Logger.v("NotifyRelay", "通知移除，从缓存中清理: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
            // 超级岛：发送终止包
            try {
                val pair = superIslandFeatureByKey.remove(notificationKey)
                if (pair != null) {
                    val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(applicationContext)
                    val (superPkg, featureId) = pair
                    MessageSender.sendSuperIslandEnd(
                        applicationContext,
                        superPkg,
                        try { applicationContext.packageName } catch (_: Exception) { null },
                        System.currentTimeMillis(),
                        try { SuperIslandManager.extractSuperIslandData(sbn, applicationContext)?.paramV2Raw } catch (_: Exception) { null },
                        try { NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") } catch (_: Exception) { null },
                        try { NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") } catch (_: Exception) { null },
                        deviceManager,
                        featureIdOverride = featureId
                    )
                }
            } catch (_: Exception) {}
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onTaskRemoved called, rootIntent=$rootIntent")
        super.onTaskRemoved(rootIntent)
        // 重新启动服务，防止被系统杀死
        val restartIntent =
            Intent(applicationContext, NotifyRelayNotificationListenerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
    }
    override fun onCreate() {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onCreate called")
        // 注册缓存清理器
        NotificationRepository.registerCacheCleaner { keysToRemove ->
            if (keysToRemove.isEmpty()) {
                // 空集合表示清除全部缓存
                val beforeSize = processedNotifications.size
                processedNotifications.clear()
                Logger.i("黑影 NotifyRelay", "[NotifyListener] 清理全部processedNotifications缓存，清除前: $beforeSize 个条目")
            } else {
                // 清除指定的缓存项
                val beforeSize = processedNotifications.size
                processedNotifications.keys.removeAll(keysToRemove)
                val afterSize = processedNotifications.size
                Logger.i("黑影 NotifyRelay", "[NotifyListener] 清理processedNotifications缓存，清除前: $beforeSize，清除后: $afterSize，移除 ${keysToRemove.size} 个条目")
            }
        }
        // 确保本地历史缓存已加载，避免首次拉取时判重失效
        NotificationRepository.init(applicationContext)
        // 初始化设备连接管理器并启动发现
        connectionManager = DeviceConnectionManagerSingleton.getDeviceManager(applicationContext)
        try {
            val discoveryField = connectionManager.javaClass.getDeclaredField("discoveryManager")
            discoveryField.isAccessible = true
            val discovery = discoveryField.get(connectionManager)
            val startMethod = discovery.javaClass.getDeclaredMethod("startDiscovery")
            startMethod.isAccessible = true
            startMethod.invoke(discovery)
        } catch (_: Exception) {}

        // 监听设备状态变化，更新通知
        CoroutineScope(Dispatchers.Default).launch {
            connectionManager.devices.collect { deviceMap ->
                // 设备状态发生变化时更新通知
                updateNotification()
            }
        }

        // 监听网络状态变化，更新通知
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNotification()
            }

            override fun onLost(network: Network) {
                updateNotification()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateNotification()
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onBind called, intent=$intent")
        return super.onBind(intent)
    }
    private var foregroundJob: Job? = null
    private val CHANNEL_ID = "notifyrelay_foreground"
    private val NOTIFY_ID = 1001

    // 设备连接管理器
    private lateinit var connectionManager: DeviceConnectionManager

    // 网络监听器
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 新增：已处理通知缓存，避免重复处理 (改进版：带时间戳的LRU缓存)
    private val processedNotifications = mutableMapOf<String, Long>()
    // 记录本机转发过的超级岛特征ID，用于在移除时发送终止包
    private val superIslandFeatureByKey = mutableMapOf<String, Pair<String, String>>() // sbnKey -> (superPkg, featureId)
    
    // 媒体播放通知状态管理：使用sbn.key作为会话键，跟踪每个媒体通知的状态
    private val mediaPlayStateByKey = mutableMapOf<String, MediaPlayState>()
    
    // 媒体播放状态数据类
    data class MediaPlayState(
        val title: String,
        val text: String,
        val packageName: String,
        val postTime: Long,
        val coverUrl: String? = null
    )
    
    /**
     * 将Drawable转换为Bitmap
     */
    private fun Drawable.toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(intrinsicWidth, intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
    
    /**
     * 处理媒体播放通知
     */
    private fun processMediaNotification(sbn: StatusBarNotification) {
        val sbnKey = sbn.key ?: (sbn.id.toString() + "|" + sbn.packageName)
        val title = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title") ?: ""
        val text = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text") ?: ""
        
        // 获取音乐封面图标
        var coverUrl: String? = null
        try {
            // 尝试从通知的大图中获取封面
            val largeIcon = sbn.notification.getLargeIcon()
            if (largeIcon != null) {
                // 将Drawable转换为Bitmap
                val drawable = largeIcon.loadDrawable(applicationContext)
                if (drawable != null) {
                    val bitmap = drawable.toBitmap()
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val bytes = stream.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    coverUrl = "data:image/jpeg;base64,$base64"
                }
            }
        } catch (e: Exception) {
            Logger.e("NotifyRelay-Media", "获取音乐封面失败", e)
        }
        
        // 记录日志
        Logger.v("NotifyRelay-Media", "processMediaNotification: title='$title', text='$text', sbnKey=$sbnKey, coverUrl=${coverUrl?.take(20)}...")
        
        // 检查状态是否变化，只在内容变化时发送
        val currentState = MediaPlayState(title, text, sbn.packageName, sbn.postTime, coverUrl)
        val lastState = mediaPlayStateByKey[sbnKey]
        
        if (lastState == null || lastState.title != currentState.title || lastState.text != currentState.text || lastState.coverUrl != currentState.coverUrl) {
            // 状态变化，发送通知
            Logger.i("NotifyRelay-Media", "媒体播放状态变化，发送通知: $title - $text")
            
            try {
                val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(applicationContext)
                var appName: String? = null
                try {
                    val pm = applicationContext.packageManager
                    val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                    appName = pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    appName = sbn.packageName
                }
                
                // 使用专门的协议前缀标记媒体通知
                MessageSender.sendMediaPlayNotification(
                    applicationContext,
                    "mediaplay:${sbn.packageName}",
                    appName,
                    title,
                    text,
                    coverUrl,
                    sbn.postTime,
                    deviceManager
                )
                
                // 更新状态缓存
                mediaPlayStateByKey[sbnKey] = currentState
            } catch (e: Exception) {
                Logger.e("NotifyRelay-Media", "发送媒体播放通知失败", e)
            }
        }
    }

    private fun cleanupExpiredCacheEntries(currentTime: Long) {
        if (processedNotifications.size <= CACHE_CLEANUP_THRESHOLD) return

        val expiredKeys = processedNotifications.filter { (_, timestamp) ->
            currentTime - timestamp > CACHE_ENTRY_TTL
        }.keys

        if (expiredKeys.isNotEmpty()) {
            processedNotifications.keys.removeAll(expiredKeys)
            Logger.i("黑影 NotifyRelay", "[NotifyListener] 清理过期缓存条目: ${expiredKeys.size} 个")
        }

        // 如果仍然超过最大大小，进行LRU清理
        if (processedNotifications.size > MAX_CACHE_SIZE) {
            val entriesToRemove = processedNotifications.size - MAX_CACHE_SIZE
            val sortedByTime = processedNotifications.entries.sortedBy { it.value }
            val keysToRemove = sortedByTime.take(entriesToRemove).map { it.key }
            processedNotifications.keys.removeAll(keysToRemove)
            Logger.i("黑影 NotifyRelay", "[NotifyListener] LRU清理缓存条目: ${keysToRemove.size} 个")
        }
    }

    private fun processNotification(sbn: StatusBarNotification, checkProcessed: Boolean = false) {
        // 读取超级岛设置开关，决定是否按超级岛专用逻辑处理
        val superIslandEnabled = try {
            StorageManager.getBoolean(applicationContext, "superisland_enabled", true)
        } catch (_: Exception) { true }

        // 检查是否为媒体播放通知
        val isMediaNotification = sbn.notification.category == Notification.CATEGORY_TRANSPORT
        if (isMediaNotification) {
            // 媒体播放通知，单独处理
            processMediaNotification(sbn)
            return
        }

        // 在本机本地过滤前，尝试读取超级岛信息并单独转发
        // 当开关开启且检测到超级岛数据时，只发送超级岛分支，不再走普通通知转发
        val superIslandHandledAndStop: Boolean = if (superIslandEnabled) {
            try {
                val superData = SuperIslandManager.extractSuperIslandData(sbn, applicationContext)
                if (superData != null) {
                    Logger.i("超级岛", "超级岛: 检测到超级岛数据，准备转发，pkg=${superData.sourcePackage}, title=${superData.title}")
                    try {
                        val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(applicationContext)
                        // 不再使用包名前缀标记；通过通道头 DATA_SUPERISLAND 区分超级岛
                        val superPkg = superData.sourcePackage ?: "unknown"
                        // 严格以通知 sbn.key 作为会话键：一条系统通知只对应一座“岛”
                        val sbnInstanceId = sbn.key ?: (sbn.id.toString() + "|" + sbn.packageName)
                        // 优先复用历史特征ID，避免因字段轻微变化导致“不同岛”的错判
                        val oldId = try { superIslandFeatureByKey[sbnInstanceId]?.second } catch (_: Exception) { null }
                        val computedId = SuperIslandProtocol.computeFeatureId(
                            superPkg,
                            superData.paramV2Raw,
                            superData.title,
                            superData.text,
                            sbnInstanceId
                        )
                        val featureId = oldId ?: computedId
                        // 初次出现时登记；后续保持不变
                        try { if (oldId == null) superIslandFeatureByKey[sbnInstanceId] = superPkg to featureId } catch (_: Exception) {}
                        MessageSender.sendSuperIslandData(
                            applicationContext,
                            superPkg,
                            superData.appName ?: "超级岛",
                            superData.title,
                            superData.text,
                            sbn.postTime,
                            superData.paramV2Raw,
                            // 尝试把 simple pic map 提取为 string map（仅支持 string/url 类值）
                            (superData.picMap ?: emptyMap()),
                            deviceManager,
                            featureIdOverride = featureId
                        )
                    } catch (e: Exception) {
                        Logger.w("超级岛", "超级岛: 转发超级岛数据失败: ${e.message}")
                    }
                    // 已按超级岛分支处理，本条不再继续普通转发
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        if (superIslandHandledAndStop) {
            // 超级岛分支已完成，只保留本机历史，不再转发普通通知
            logSbnDetail("超级岛: 已按超级岛分支处理，跳过普通转发", sbn)
            return
        }

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

        CoroutineScope(Dispatchers.Default).launch {
            try {
                logSbnDetail("黑影 通过", sbn)
                val added = NotificationRepository.addNotification(sbn, this@NotifyRelayNotificationListenerService)
                if (added) {
                    forwardNotificationToRemoteDevices(sbn)
                } else {
                    Logger.i("狂鼠 NotifyRelay", "[NotifyListener] 本地已存在该通知，未转发到远程设备: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
                }
            } catch (e: Exception) {
                Logger.e("黑影 NotifyRelay", "[NotifyListener] addNotification error", e)
            }
        }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onNotificationPosted called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        processNotification(sbn)
    }

    private fun forwardNotificationToRemoteDevices(sbn: StatusBarNotification) {
        Logger.i("狂鼠 NotifyRelay", "[NotifyListener] forwardNotificationToRemoteDevices called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        try {
            val deviceManager = DeviceConnectionManagerSingleton.getDeviceManager(applicationContext)
            var appName: String? = null
            try {
                val pm = applicationContext.packageManager
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                appName = pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                appName = sbn.packageName
            }

            // 使用整合的消息发送工具
            MessageSender.sendNotificationMessage(
                applicationContext,
                sbn.packageName,
                appName,
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.title"),
                NotificationRepository.getStringCompat(sbn.notification.extras, "android.text"),
                sbn.postTime,
                deviceManager
            )
        } catch (e: Exception) {
            Logger.e("NotifyRelay", "自动转发通知到远程设备失败", e)
        }
    }


    override fun onListenerConnected() {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected called")
        super.onListenerConnected()
        // 检查监听服务是否启用
        val enabledListeners = Settings.Secure.getString(
            applicationContext.contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        Logger.i("黑影 NotifyRelay", "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            Logger.w("黑影 NotifyRelay", "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            Logger.i("黑影 NotifyRelay", "[NotifyListener] onListenerConnected: activeNotifications.size=${actives.size}")
            CoroutineScope(Dispatchers.Default).launch {
                for (sbn in actives) {
                    processNotification(sbn, true)
                }
            }
        } else {
            Logger.w("黑影 NotifyRelay", "[NotifyListener] activeNotifications is null")
        }
        // 启动前台服务，保证后台存活
        startForegroundService()
        // 定时拉取活跃通知，保证后台实时性
        foregroundJob?.cancel()
        foregroundJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(5000)
                val actives = activeNotifications
                if (actives != null) {

                    for (sbn in actives) {
                        if (sbn.packageName == applicationContext.packageName) continue
                        processNotification(sbn, true)
                    }
                    // 定期清理过期的缓存，避免内存泄漏
                    cleanupExpiredCacheEntries(System.currentTimeMillis())
                    if (BuildConfig.DEBUG && processedNotifications.size > CACHE_CLEANUP_THRESHOLD) {
                        Logger.i("黑影 NotifyRelay", "[NotifyListener] 缓存大小: ${processedNotifications.size}")
                    }
                } else {
                    Logger.w("黑影 NotifyRelay", "[NotifyListener] 定时拉取 activeNotifications is null")
                }
            }
        }
    }

    override fun onDestroy() {
        Logger.i("黑影 NotifyRelay", "[NotifyListener] onDestroy called")
        super.onDestroy()
        foregroundJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 停止设备连接
        try {
            if (this::connectionManager.isInitialized) {
                try {
                    val discoveryField = connectionManager.javaClass.getDeclaredField("discoveryManager")
                    discoveryField.isAccessible = true
                    val discovery = discoveryField.get(connectionManager)
                    val stopMethod = discovery.javaClass.getDeclaredMethod("stopAll")
                    stopMethod.isAccessible = true
                    stopMethod.invoke(discovery)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        // 注销网络监听器
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通知监听/转发中")
            .setContentText(getNotificationText())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // Android 12+ 及以上不再指定特殊前台服务类型，避免权限崩溃
        startForeground(NOTIFY_ID, notification)
    }

    private fun getNotificationText(): String {
        // 使用 DeviceConnectionManager 提供的线程安全方法获取在线且已认证的设备数量
        val onlineDevices = try { connectionManager.getAuthenticatedOnlineCount() } catch (_: Exception) { 0 }
        //Logger.d("黑影 NotifyRelay", "getNotificationText: authenticatedOnlineCount=$onlineDevices")

        // 优先显示设备连接数，如果有设备连接
        if (onlineDevices > 0) {
            return "当前${onlineDevices}台设备已连接"
        }

        // 没有设备连接时，显示网络状态
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isWifiDirect = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true

        // 如果不是WiFi、以太网或WLAN直连，则认为是移动数据等非局域网
        if (!isWifi && !isEthernet && !isWifiDirect) {
            return "非局域网连接"
        }

        return "无设备在线"
    }

    private fun updateNotification() {
        //Logger.d("黑影 NotifyRelay", "updateNotification called")
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // 根据是否有转发条件决定标题
        val canForward = getNotificationText().let { text ->
            !text.contains("无设备在线") && !text.contains("非局域网连接")
        }
        val title = if (canForward) "通知监听/转发中" else "通知监听中"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getNotificationText())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(NOTIFY_ID, notification)
        //Logger.d("黑影 NotifyRelay", "notify posted: id=$NOTIFY_ID, text=${getNotificationText()}")
    }

    // 保留通知历史，不做移除处理

    private fun logSbnDetail(prefix: String, sbn: StatusBarNotification) {
        {
            NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")
            NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")
            //Logger.d("NotifyRelay", "$prefix sbnKey=${sbn.key}, pkg=${sbn.packageName}, id=${sbn.id}, title=$title, text=$text")
        }
    }
}