package com.xzyht.notifyrelay.feature.notification.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.xzyht.notifyrelay.R
import com.xzyht.notifyrelay.feature.clipboard.ClipboardSyncManager
import com.xzyht.notifyrelay.feature.clipboard.ClipboardSyncReceiver
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManagerSingleton
import com.xzyht.notifyrelay.feature.media.service.MediaSessionMonitorService
import com.xzyht.notifyrelay.feature.notification.filter.BackendLocalFilter
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.tracker.LocalSuperIslandTracker
import com.xzyht.notifyrelay.feature.notification.superisland.media.MediaCapsulePresenter
import com.xzyht.notifyrelay.sync.MessageSender
import github.xzynine.superislandui.common.SuperIslandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import com.xzyht.notifyrelay.ui.activity.GuideActivity
import notifyrelay.data.StorageManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class NotifyRelayNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "NotifyRelayNotificationListenerService"
        private const val MAX_CACHE_SIZE = 2000
        private const val CACHE_CLEANUP_THRESHOLD = 1500
        private const val CACHE_ENTRY_TTL = 24 * 60 * 60 * 1000L

        // 24小时TTL

        // 最新的媒体播放通知（用于被外部工具查询并触发其 action）
        @Volatile
        var latestMediaSbn: StatusBarNotification? = null

        // 服务实例，用于在静态方法中访问实例方法
        @Volatile
        var instance: NotifyRelayNotificationListenerService? = null

        // 媒体会话数据缓存
        private val mediaSessionDataCache = ConcurrentHashMap<String, MediaSessionData>()

        // 接收来自 MediaSessionMonitorService 的媒体会话数据
        @JvmStatic
        fun onMediaSessionUpdated(
            packageName: String,
            title: String,
            artist: String,
            duration: Long,
            artBitmap: Any?,
        ) {
            Logger.i(TAG, "Received MediaSession update: $packageName - $title")

            // 缓存媒体会话数据
            val mediaSessionData =
                MediaSessionData(
                    packageName = packageName,
                    title = title,
                    artist = artist,
                    duration = duration,
                    artBitmap = artBitmap as? Bitmap,
                    timestamp = System.currentTimeMillis(),
                )
            mediaSessionDataCache[packageName] = mediaSessionData

            // 立即处理媒体会话数据，确保歌词获取与通知获取同步
            // 查找对应的媒体通知并处理
            val activeNotifications = instance?.activeNotifications
            if (activeNotifications != null) {
                val mediaSbn =
                    activeNotifications.firstOrNull {
                        it.packageName == packageName && it.notification.category == Notification.CATEGORY_TRANSPORT
                    }
                for (sbn in activeNotifications) {
                    if (sbn.packageName == packageName && sbn.notification.category == Notification.CATEGORY_TRANSPORT) {
                        instance?.processMediaNotification(sbn)
                        break
                    }
                }
            }
        }

        // 获取指定包名的媒体会话数据
        fun getMediaSessionData(packageName: String): MediaSessionData? = mediaSessionDataCache[packageName]

        // 媒体会话数据类
        data class MediaSessionData(
            val packageName: String,
            val title: String,
            val artist: String,
            val duration: Long,
            val artBitmap: Bitmap?,
            val timestamp: Long,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // 只补发本应用的前台服务通知（必须channelId和id都匹配）
        if (sbn.packageName == applicationContext.packageName &&
            sbn.notification.channelId == channelId &&
            sbn.id == notifyId
        ) {
            Logger.w(TAG, "前台服务通知被移除，自动补发！")
            // 立即补发本服务前台通知
            startForegroundService()
        } else if (sbn.packageName == applicationContext.packageName) {
            // 检查是否为超级岛相关通知（包括普通超级岛和焦点歌词）
            val channelId = sbn.notification.channelId
            if (channelId == "super_island_replica") {
                // 超级岛相关通知被移除，关闭对应的浮窗条目
                Logger.i(TAG, "超级岛相关通知被移除，关闭对应的浮窗条目: id=${sbn.id}, channelId=$channelId")
                FloatingReplicaManager.closeByNotificationId(sbn.id)
            }
        } else {
            // 普通通知被移除时，从已处理缓存中移除，允许下次重新处理
            val notificationKey = getNotificationKey(sbn, "")
            processedNotifications.remove(notificationKey)
            Logger.v(TAG, "通知移除，从缓存中清理: sbnKey=${sbn.key}, pkg=${sbn.packageName}")

            // 检查是否为媒体通知
            val isMediaNotification = sbn.notification.category == Notification.CATEGORY_TRANSPORT
            if (isMediaNotification) {
                // 媒体通知被移除，发送媒体结束包
                try {
                    Logger.i(TAG, "媒体通知被移除，发送结束包: pkg=${sbn.packageName}")
                    val appName = getAppName(sbn.packageName)
                    // 发送媒体结束包
                    MessageSender.sendMediaPlayEndNotification(
                        applicationContext,
                        sbn.packageName,
                        appName,
                        System.currentTimeMillis(),
                        deviceManager,
                    )
                    // 更新全局最新媒体通知
                    if (latestMediaSbn?.key == sbn.key) {
                        latestMediaSbn = null
                    }
                    // 关闭对应的浮窗条目，像远程通知一样立即结束
                    val sbnKey = getNotificationKey(sbn)
                    FloatingReplicaManager.dismissBySource(sbnKey)
                    Logger.i(TAG, "媒体通知被移除，关闭对应的浮窗条目: sbnKey=$sbnKey")
                } catch (e: Exception) {
                    Logger.e(TAG, "发送媒体结束包失败", e)
                }
            } else {
                // 超级岛：发送终止包
                try {
                    val superData =
                        try {
                            SuperIslandManager.extractSuperIslandData(sbn, applicationContext)
                        } catch (_: Exception) {
                            null
                        }
                    if (superData != null) {
                        val deviceManager = this.deviceManager
                        val superPkg = superData.sourcePackage ?: "unknown"
                        LocalSuperIslandTracker.markInactive(superPkg)
                        MessageSender.sendSuperIslandEnd(
                            applicationContext,
                            superPkg,
                            try {
                                applicationContext.packageName
                            } catch (_: Exception) {
                                null
                            },
                            System.currentTimeMillis(),
                            superData.paramV2Raw,
                            getNotificationTitle(sbn),
                            getNotificationText(sbn),
                            deviceManager,
                            featureIdOverride = getNotificationKey(sbn, ""),
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i(TAG, "[NotifyListener] onTaskRemoved called, rootIntent=$rootIntent")
        super.onTaskRemoved(rootIntent)
        // 重新启动服务，防止被系统杀死
        val restartIntent =
            Intent(applicationContext, NotifyRelayNotificationListenerService::class.java)
        applicationContext.startForegroundService(restartIntent)
    }

    override fun onCreate() {
        Logger.i(TAG, "[NotifyListener] onCreate called")
        // 初始化服务实例
        instance = this
        // 注册缓存清理器
        NotificationRepository.registerCacheCleaner { keysToRemove ->
            if (keysToRemove.isEmpty()) {
                // 空集合表示清除全部缓存
                val beforeSize = processedNotifications.size
                processedNotifications.clear()
                Logger.i(TAG, "[NotifyListener] 清理全部processedNotifications缓存，清除前: $beforeSize 个条目")
            } else {
                // 清除指定的缓存项
                val beforeSize = processedNotifications.size
                processedNotifications.keys.removeAll(keysToRemove)
                val afterSize = processedNotifications.size
                Logger.i(TAG, "[NotifyListener] 清理processedNotifications缓存，清除前: $beforeSize，清除后: $afterSize，移除 ${keysToRemove.size} 个条目")
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
        } catch (_: Exception) {
        }

        // 初始化 MediaSession 监控服务
        mediaSessionMonitorService = MediaSessionMonitorService(this)
        mediaSessionMonitorService.initialize()

        // 监听设备状态变化，更新通知
        CoroutineScope(Dispatchers.Default).launch {
            try {
                connectionManager.devices.collect {
                    // 设备状态发生变化时更新通知
                    updateNotification()
                }
            } catch (e: Exception) {
                Logger.e(TAG, "设备状态监听协程异常退出，通知将不再自动更新", e)
            }
        }

        // 监听网络状态变化，更新通知
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateNotification()
                }

                override fun onLost(network: Network) {
                    updateNotification()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    updateNotification()
                }
            }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)

        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? {
        Logger.i(TAG, "[NotifyListener] onBind called, intent=$intent")
        return super.onBind(intent)
    }

    private var foregroundJob: Job? = null
    private val channelId = "notifyrelay_foreground"
    private val notifyId = 1001

    // 设备连接管理器
    private lateinit var connectionManager: DeviceConnectionManager
    private val deviceManager by lazy { DeviceConnectionManagerSingleton.getDeviceManager(applicationContext) }

    // 网络监听器
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 新增：已处理通知缓存，避免重复处理 (改进版：带时间戳的LRU缓存)
    private val processedNotifications = ConcurrentHashMap<String, Long>()

    // 通知发送专用作用域：串行发送
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()

    // MediaSession 监控服务实例
    private lateinit var mediaSessionMonitorService: MediaSessionMonitorService

    // Wake Lock：锁屏期间保持 CPU 不休眠，确保心跳线程正常运行
    private var wakeLock: PowerManager.WakeLock? = null

    // 使用通用工具将 Drawable 转换为 Bitmap（参照项目中其他模块的实现）

    /**
     * 处理媒体播放通知
     */
    private fun processMediaNotification(sbn: StatusBarNotification) {
        val sbnKey = getNotificationKey(sbn)
        // 更新全局持有的最新媒体通知，方便外部通过工具类触发操作
        try {
            latestMediaSbn = sbn
        } catch (_: Exception) {
        }

        // 初始化变量
        var finalTitle: String
        var finalText: String
        var finalCoverUrl: String? = null

        // 使用 MediaSession 机制获取数据
        val mediaSessionData = getMediaSessionData(sbn.packageName)
        if (mediaSessionData != null) {
            Logger.i(TAG, "Using MediaSession data for ${sbn.packageName}")
            // 使用 MediaSession 数据
            finalTitle = mediaSessionData.title
            finalText = mediaSessionData.artist

            // 从 MediaSession 获取封面
            if (mediaSessionData.artBitmap != null) {
                try {
                    val stream = ByteArrayOutputStream()
                    mediaSessionData.artBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val bytes = stream.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    finalCoverUrl = "data:image/jpeg;base64,$base64"
                } catch (e: Exception) {
                    Logger.e(TAG, "获取 MediaSession 封面失败", e)
                }
            }
        } else {
            // 没有 MediaSession 数据，跳过处理
            Logger.d(TAG, "No MediaSession data for ${sbn.packageName}, skipping media notification")
            return
        }

        // 检查胶囊歌词开关状态
        val capsuleLyricsEnabled = getStorageBoolean("capsule_lyrics_enabled", false)

        // 如果胶囊歌词开关开启，直接在本机内生成浮窗和通知
        if (capsuleLyricsEnabled) {
            try {
                Logger.i(TAG, "胶囊歌词开关开启，在本机内生成浮窗和通知: title='$finalTitle', text='$finalText'")

                val picMap = mutableMapOf<String, String>()
                if (!finalCoverUrl.isNullOrBlank()) {
                    picMap["miui.focus.pic_cover"] = finalCoverUrl
                    picMap["miui.focus.pic_app_icon"] = finalCoverUrl
                }

                val appName = getAppName(sbn.packageName)
                MediaCapsulePresenter.show(
                    context = applicationContext,
                    sourceId = sbnKey,
                    title = finalTitle,
                    text = finalText,
                    appName = appName,
                    picMap = picMap,
                )
            } catch (e: Exception) {
                Logger.e(TAG, "在本机内生成浮窗和通知失败", e)
            }
        }

        if (!getStorageBoolean("send_media_notifications_enabled", true)) return

        try {
            val appName = getAppName(sbn.packageName)
            MessageSender.sendMediaPlayNotification(
                applicationContext,
                sbn.packageName,
                appName,
                finalTitle,
                finalText,
                finalCoverUrl,
                sbn.postTime,
                deviceManager,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "发送媒体播放消息失败", e)
        }
    }

    private fun cleanupExpiredCacheEntries(currentTime: Long) {
        if (processedNotifications.size <= CACHE_CLEANUP_THRESHOLD) return

        val expiredKeys =
            processedNotifications
                .filter { (_, timestamp) ->
                    currentTime - timestamp > CACHE_ENTRY_TTL
                }.keys

        if (expiredKeys.isNotEmpty()) {
            processedNotifications.keys.removeAll(expiredKeys)
            Logger.i(TAG, "[NotifyListener] 清理过期缓存条目: ${expiredKeys.size} 个")
        }

        // 如果仍然超过最大大小，进行LRU清理
        if (processedNotifications.size > MAX_CACHE_SIZE) {
            val entriesToRemove = processedNotifications.size - MAX_CACHE_SIZE
            val sortedByTime = processedNotifications.entries.sortedBy { it.value }
            val keysToRemove = sortedByTime.take(entriesToRemove).map { it.key }
            processedNotifications.keys.removeAll(keysToRemove)
            Logger.i(TAG, "[NotifyListener] LRU清理缓存条目: ${keysToRemove.size} 个")
        }
    }

    private fun processNotification(
        sbn: StatusBarNotification,
        checkProcessed: Boolean = false,
    ) {
        // 读取超级岛设置开关，决定是否按超级岛专用逻辑处理
        val superIslandEnabled = getStorageBoolean("superisland_enabled", true)

        // 检查是否为媒体播放通知
        val isMediaNotification = sbn.notification.category == Notification.CATEGORY_TRANSPORT
        if (isMediaNotification) {
            // 媒体播放消息，单独处理
            processMediaNotification(sbn)
            return
        }

        // 在本机本地过滤前，尝试读取超级岛信息并单独转发
        // 当开关开启且检测到超级岛数据时，只发送超级岛分支，不再走普通通知转发
        val superIslandHandledAndStop: Boolean =
            if (superIslandEnabled) {
                try {
                    if (sbn.packageName == applicationContext.packageName) {
                        false
                    } else {
                        val superData = SuperIslandManager.extractSuperIslandData(sbn, applicationContext)
                        if (superData != null) {
                            Logger.i(TAG, "超级岛: 检测到超级岛数据，准备转发，pkg=${superData.sourcePackage}, title=${superData.title}")
                            superData.sourcePackage?.let { LocalSuperIslandTracker.markActive(it) }
                            try {
                                val deviceManager = this.deviceManager
                                // 不再使用包名前缀标记；通过通道头 DATA_SUPERISLAND 区分超级岛
                                val superPkg = superData.sourcePackage ?: "unknown"
                                // 严格以通知 sbn.key 作为会话键：一条系统通知只对应一座"岛"，内容变化不影响会话
                                val featureId = getNotificationKey(sbn, "")
                                // 图片处理（本地 URI 读取/Base64）可能在 IO 线程耗时，异步发送避免阻塞监听线程；
                                // sendSuperIslandData 内部已捕获全部异常
                                sendScope.launch {
                                    sendMutex.withLock {
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
                                            featureIdOverride = featureId,
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.w(TAG, "超级岛: 转发超级岛数据失败: ${e.message}")
                            }
                            true
                        } else {
                            false
                        }
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

        if (!BackendLocalFilter.shouldForwardBlocking(sbn, applicationContext, checkProcessed)) {
            if (Logger.enableFilteredNotificationLog) {
                logSbnDetail("法鸡-黑影 被过滤", sbn)
            }
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
                    Logger.i(TAG, "[NotifyListener] 本地已存在该通知，未转发到远程设备: sbnKey=${sbn.key}, pkg=${sbn.packageName}")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "[NotifyListener] addNotification error", e)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Logger.i(TAG, "[NotifyListener] onNotificationPosted called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        val isMedia = sbn.notification.category == Notification.CATEGORY_TRANSPORT
        processNotification(sbn)
    }

    private fun forwardNotificationToRemoteDevices(sbn: StatusBarNotification) {
        Logger.i(TAG, "[NotifyListener] forwardNotificationToRemoteDevices called, sbnKey=${sbn.key}, pkg=${sbn.packageName}")
        try {
            val appName = getAppName(sbn.packageName)

            // 使用整合的消息发送工具
            MessageSender.sendNotificationMessage(
                applicationContext,
                sbn.packageName,
                appName,
                getNotificationTitle(sbn),
                getNotificationText(sbn),
                sbn.postTime,
                deviceManager,
            )
        } catch (e: Exception) {
            Logger.e(TAG, "自动转发通知到远程设备失败", e)
        }
    }

    override fun onListenerConnected() {
        Logger.i(TAG, "[NotifyListener] onListenerConnected called")
        super.onListenerConnected()
        // 检查监听服务是否启用
        val enabledListeners =
            Settings.Secure.getString(
                applicationContext.contentResolver,
                "enabled_notification_listeners",
            )
        val isEnabled = enabledListeners?.contains(applicationContext.packageName) == true
        Logger.i(TAG, "[NotifyListener] Listener enabled: $isEnabled, enabledListeners=$enabledListeners")
        if (!isEnabled) {
            Logger.w(TAG, "[NotifyListener] NotificationListenerService 未被系统启用，无法获取通知！")
        }
        // 启动 MediaSession 监控服务
        mediaSessionMonitorService.startMonitoring()
        // 启动时同步所有活跃通知到历史，后台处理
        val actives = activeNotifications
        if (actives != null) {
            Logger.i(TAG, "[NotifyListener] onListenerConnected: activeNotifications.size=${actives.size}")
            CoroutineScope(Dispatchers.Default).launch {
                for (sbn in actives) {
                    processNotification(sbn, true)
                }
            }
        } else {
            Logger.w(TAG, "[NotifyListener] activeNotifications is null")
        }
        // 启动前台服务，保证后台存活
        startForegroundService()
        // 定时拉取活跃通知，保证后台实时性
        foregroundJob?.cancel()
        foregroundJob =
            CoroutineScope(Dispatchers.Default).launch {
                while (true) {
                    delay(30000)
                    val actives = activeNotifications
                    if (actives != null) {
                        for (sbn in actives) {
                            if (sbn.packageName == applicationContext.packageName) continue
                            processNotification(sbn, true)
                        }
                        // 定期清理过期的缓存，避免内存泄漏
                        cleanupExpiredCacheEntries(System.currentTimeMillis())
                        if (processedNotifications.size > CACHE_CLEANUP_THRESHOLD) {
                            Logger.d(TAG, "[NotifyListener] 缓存大小: ${processedNotifications.size}")
                        }
                    } else {
                        Logger.w(TAG, "[NotifyListener] 定时拉取 activeNotifications is null")
                    }
                }
            }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 释放 WakeLock（避免权限掉落时持有唤醒锁导致耗电）
        releaseWakeLock()
        // 停止 MediaSession 监控服务
        mediaSessionMonitorService.stopMonitoring()

        // 兜底：通知监听权限被系统收回时，若应用仍处于前台，回弹引导页要求重新授权，
        // 避免胶囊歌词/媒体浮窗/转发等功能在权限掉落后静默失效且无入口恢复。
        if (PermissionHelper.isAppInForeground(this) &&
            !PermissionHelper.checkNotificationListenerServiceCanStart(this)
        ) {
            Logger.w(TAG, "[NotifyListener] 权限掉落且应用在前台，回弹引导页重新授权")
            try {
                val intent = Intent(this, GuideActivity::class.java)
                intent.putExtra("reauth", true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "[NotifyListener] onDestroy called")
        // 释放 Wake Lock
        releaseWakeLock()
        // 清空服务实例引用
        instance = null
        super.onDestroy()
        foregroundJob?.cancel()
        sendScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 停止 MediaSession 监控服务
        mediaSessionMonitorService.stopMonitoring()
        mediaSessionMonitorService.destroy()
        // 停止设备连接
        try {
            if (this::connectionManager.isInitialized) {
                try {
                    val discoveryField = connectionManager.javaClass.getDeclaredField("discoveryManager")
                    discoveryField.isAccessible = true
                    val discovery = discoveryField.get(connectionManager)
                    val stopMethod = discovery.javaClass.getDeclaredMethod("stopDiscovery")
                    stopMethod.isAccessible = true
                    stopMethod.invoke(discovery)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        // 注销网络监听器
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {
        }
    }

    private fun startForegroundService() {
        val channel =
            NotificationChannel(
                channelId,
                "通知转发后台服务",
                NotificationManager.IMPORTANCE_HIGH,
            )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val notification = buildNotification()
        startForeground(notifyId, notification)
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notifyrelay:core").apply {
                    acquire()
                }
                Logger.i(TAG, "Wake Lock 已获取")
            } catch (e: SecurityException) {
                Logger.w(TAG, "获取 Wake Lock 失败（缺少权限）", e)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Logger.i(TAG, "Wake Lock 已释放")
            }
        }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val builder =
            NotificationCompat
                .Builder(this, channelId)
                .setContentTitle("通知监听/转发中")
                .setContentText(getNotificationText())
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

        // 为通知主体添加点击事件，实现剪贴板同步功能
        try {
            val syncIntent =
                Intent(this, ClipboardSyncReceiver::class.java).apply {
                    action = ClipboardSyncReceiver.ACTION_MANUAL_SYNC
                }
            val syncPendingIntent =
                PendingIntent.getBroadcast(
                    this,
                    0,
                    syncIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.setContentIntent(syncPendingIntent)
        } catch (e: Exception) {
            Logger.w(TAG, "添加剪贴板点击事件失败", e)
        }

        return builder.build()
    }

    private fun getNotificationText(): String {
        // 使用 DeviceConnectionManager 提供的线程安全方法获取在线且已认证的设备数量
        val onlineDevices =
            try {
                connectionManager.getAuthenticatedOnlineCount()
            } catch (_: Exception) {
                0
            }
        val fcitx5Paired =
            try {
                ClipboardSyncManager.isFcitx5Paired(this)
            } catch (_: Exception) {
                false
            }
        // Logger.d(TAG, "getNotificationText: authenticatedOnlineCount=$onlineDevices")
        Logger.d(TAG, "getNotificationText: authenticatedOnlineCount=$onlineDevices")

        // 优先显示设备连接数，如果有设备连接
        if (onlineDevices > 0) {
            return if (!fcitx5Paired) {
                "当前${onlineDevices}台设备已连接，点击以同步剪贴板"
            } else {
                "当前${onlineDevices}台设备已连接"
            }
        }

        // 没有设备连接时，显示网络状态
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isEthernet = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val isWifiDirect = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_WIFI_P2P) == true

        // 如果不是WiFi、以太网或WLAN直连，则认为是移动数据等非局域网
        val baseText =
            if (!isWifi && !isEthernet && !isWifiDirect) {
                "非局域网连接"
            } else {
                "无设备在线"
            }

        // Fcitx5 未启用时，添加点击提示
        return if (!fcitx5Paired) {
            "$baseText，点击通知同步剪贴板"
        } else {
            baseText
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildNotification()
            manager.notify(notifyId, notification)
            Logger.d(TAG, "updateNotification: ${getNotificationText()}")
        } catch (e: Exception) {
            Logger.e(TAG, "更新通知失败", e)
        }
    }

    // 保留通知历史，不做移除处理

    private fun getAppName(packageName: String): String =
        try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }

    private fun getNotificationTitle(sbn: StatusBarNotification): String? = NotificationRepository.getStringCompat(sbn.notification.extras, "android.title")

    private fun getNotificationText(sbn: StatusBarNotification): String? = NotificationRepository.getStringCompat(sbn.notification.extras, "android.text")

    internal fun getNotificationKey(
        sbn: StatusBarNotification,
        separator: String = "|",
    ): String = sbn.key ?: (sbn.id.toString() + separator + sbn.packageName)

    private fun getStorageBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean =
        try {
            StorageManager.getBoolean(applicationContext, key, defaultValue)
        } catch (_: Exception) {
            defaultValue
        }

    private fun logSbnDetail(
        prefix: String,
        sbn: StatusBarNotification,
    ) {
        val title = getNotificationTitle(sbn) ?: ""
        val text = getNotificationText(sbn) ?: ""
        Logger.d(TAG, "$prefix sbnKey=${sbn.key}, pkg=${sbn.packageName}, id=${sbn.id}, title=$title, text=$text")
    }
}
