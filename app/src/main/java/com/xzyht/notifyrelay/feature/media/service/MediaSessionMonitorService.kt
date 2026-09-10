package com.xzyht.notifyrelay.feature.media.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.xzyht.notifyrelay.feature.notification.service.NotifyRelayNotificationListenerService
import notifyrelay.base.util.Logger
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap

class MediaSessionMonitorService(
    private val service: NotificationListenerService,
) {
    companion object {
        private const val TAG = "MediaSessionMonitorService"
        var instance: MediaSessionMonitorService? = null

        // 歌词字段判定所需的最小连续观测次数
        private const val LYRIC_FIELD_CONFIRM_THRESHOLD = 2

        // 歌词字段观测状态：按包名保存，且需跨服务实例保留
        // （监听服务会被系统频繁解绑重建，实例字段会导致学习状态丢失）
        private val lyricFieldProbes = ConcurrentHashMap<String, LyricFieldProbe>()
    }

    // 歌词所在字段
    private enum class LyricField { TITLE, ARTIST }

    // 按包名的歌词字段观测状态
    private class LyricFieldProbe {
        var lastTitle: String? = null
        var lastArtist: String? = null
        var candidate: LyricField? = null
        var streak: Int = 0
        var confirmed: LyricField? = null
    }

    // 服务连接状态
    private var isConnected = false

    private var mediaSessionManager: MediaSessionManager? = null
    private var componentName: ComponentName? = null

    // 存储当前活跃的媒体控制器
    private val activeControllers = mutableListOf<MediaController>()

    // 存储控制器的回调，用于后续注销
    private val controllerCallbacks = mutableMapOf<MediaController, MediaController.Callback>()

    // 去重：跟踪最后一个元数据哈希值，避免重复处理
    private var lastMetadataHash: Int = 0
    private var lastComputedIsPlaying: Boolean? = null

    // 防抖令牌
    private val updateToken = Any()

    // 去重：跟踪最后一个控制器签名
    private var lastControllerSignatures: String = ""

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            handler.removeCallbacksAndMessages(updateToken)
            val r = Runnable { updateControllers(controllers) }
            handler.postAtTime(r, updateToken, SystemClock.uptimeMillis())
        }

    private val handler = Handler(Looper.getMainLooper())

    // 健康检查机制
    private val healthCheckRunnable =
        object : Runnable {
            override fun run() {
                // 验证连接是否仍然有效
                if (isConnected && mediaSessionManager != null && componentName != null) {
                    try {
                        // 测试访问 - 如果权限被撤销，这将抛出异常
                        mediaSessionManager?.getActiveSessions(componentName)
                        Logger.i(TAG, "Health Check: OK")
                    } catch (e: SecurityException) {
                        Logger.w(TAG, "Health Check: FAILED - Permission lost")
                        isConnected = false
                        // 权限丢失，等待系统重新绑定
                    }
                }
                // 安排下一次检查
                handler.postDelayed(this, 30000)
            }
        }

    // 启动重试机制
    private val startupRetryRunnable =
        object : Runnable {
            override fun run() {
                if (!isConnected) return

                try {
                    val controllers = mediaSessionManager?.getActiveSessions(componentName)
                    Logger.i(TAG, "Successfully retrieved ${controllers?.size ?: 0} active sessions")
                    updateControllers(controllers)
                } catch (e: SecurityException) {
                    Logger.w(TAG, "Security Error on initial check: ${e.message}")
                    // 200ms 后重试一次，以防权限仍在授予中
                    handler.postDelayed(retryRunnable, 200)
                }
            }
        }

    // 200ms 重试机制
    private val retryRunnable =
        object : Runnable {
            override fun run() {
                if (!isConnected) return

                try {
                    val controllers = mediaSessionManager?.getActiveSessions(componentName)
                    Logger.i(TAG, "Retry successful: ${controllers?.size ?: 0} sessions")
                    updateControllers(controllers)
                } catch (e2: SecurityException) {
                    Logger.e(TAG, "Retry failed: ${e2.message} - Permission may need manual grant")
                }
            }
        }

    // 初始化方法
    fun initialize() {
        instance = this
        Logger.i(TAG, "initialize called")
    }

    // 开始监控
    fun startMonitoring() {
        isConnected = true
        Logger.i(TAG, "startMonitoring - Service binding initiated")

        mediaSessionManager = service.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        componentName = ComponentName(service, service.javaClass)

        mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)

        // 启动健康检查监控
        handler.postDelayed(healthCheckRunnable, 30000)

        // 添加延迟重试机制，以确保权限在重新绑定后完全生效
        handler.postDelayed(startupRetryRunnable, 100)
    }

    // 停止监控
    fun stopMonitoring() {
        isConnected = false
        Logger.i(TAG, "stopMonitoring - Service unbound")

        // 停止健康检查
        handler.removeCallbacks(healthCheckRunnable)
        // 停止启动重试和重试机制
        handler.removeCallbacks(startupRetryRunnable)
        handler.removeCallbacks(retryRunnable)

        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)

        // 清理所有回调
        synchronized(activeControllers) {
            controllerCallbacks.forEach { (controller, callback) ->
                try {
                    controller.unregisterCallback(callback)
                } catch (e: Exception) {
                    // 忽略
                }
            }
            controllerCallbacks.clear()
            activeControllers.clear()
        }
    }

    // 销毁方法
    fun destroy() {
        if (instance === this) instance = null
        Logger.i(TAG, "destroy called")
    }

    private fun updateControllers(controllers: List<MediaController>?) {
        // 确保服务仍在运行
        if (!isConnected) return

        // 去重检查
        val currentSignatures = controllers?.joinToString("|") { "${it.packageName}@${it.hashCode()}" } ?: "null"
        if (currentSignatures == lastControllerSignatures) {
            Logger.v(TAG, "Duplicate session update ignored.")
            return
        }
        lastControllerSignatures = currentSignatures
        Logger.d(TAG, "Processing new session update: $currentSignatures")

        // 健壮更新：清除并替换
        synchronized(activeControllers) {
            // 1. 注销所有旧的
            controllerCallbacks.forEach { (controller, callback) ->
                try {
                    controller.unregisterCallback(callback)
                } catch (e: Exception) {
                    // 忽略
                }
            }
            controllerCallbacks.clear()
            activeControllers.clear()

            // 2. 注册所有新的（如果有效）
            if (controllers != null) {
                controllers.forEach { controller ->
                    try {
                        val callback =
                            object : MediaController.Callback() {
                                override fun onPlaybackStateChanged(state: PlaybackState?) {
                                    // 优先级可能已更改
                                    val primary = getPrimaryController()
                                    if (primary != null && primary.packageName == controller.packageName) {
                                        // 多数应用（尤其车载/NAS/第三方播放器）不回调 onMetadataChanged，
                                        // 歌词更新只能在此兜底：手动比对元数据哈希，变化即按新元数据处理
                                        val meta = primary.metadata
                                        if (meta != null) {
                                            val artHash = (meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART))?.hashCode() ?: 0
                                            val currentHash =
                                                Objects.hash(
                                                    meta.getString(MediaMetadata.METADATA_KEY_TITLE),
                                                    meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
                                                    primary.packageName,
                                                    meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
                                                    artHash,
                                                )
                                            if (currentHash != lastMetadataHash) {
                                                updateMetadataIfPrimary(primary)
                                            }
                                        }
                                    }
                                }

                                override fun onMetadataChanged(metadata: MediaMetadata?) {
                                    updateMetadataIfPrimary(controller)
                                }

                                override fun onSessionDestroyed() {
                                    handler.post {
                                        recheckSessions() // 强制完全刷新
                                    }
                                }
                            }

                        controller.registerCallback(callback)
                        controllerCallbacks[controller] = callback
                        activeControllers.add(controller)
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to hook controller: ${controller.packageName}", e)
                    }
                }
            }
        }

        // 初始检查
        // 强制从主控制器更新
        val primary = getPrimaryController()
        if (primary != null) {
            updateMetadataIfPrimary(primary)
        }
    }

    fun recheckSessions() {
        if (mediaSessionManager != null && componentName != null) {
            try {
                // 强制更新：重置元数据哈希值，以便下次更新立即传播
                lastMetadataHash = 0
                lastComputedIsPlaying = null
                lastControllerSignatures = "" // 重置签名去重
                updateControllers(mediaSessionManager?.getActiveSessions(componentName))
            } catch (e: SecurityException) {
                Logger.w(TAG, "Error refreshing sessions: ${e.message}")
            }
        }
    }

    fun getPrimaryController(): MediaController? {
        synchronized(activeControllers) {
            // 优先级 1：正在播放/缓冲/跳过的控制器
            val playingController =
                activeControllers.firstOrNull {
                    val st = it.playbackState?.state
                    st == PlaybackState.STATE_PLAYING ||
                        st == PlaybackState.STATE_BUFFERING ||
                        st == PlaybackState.STATE_CONNECTING ||
                        st == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                        st == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
                        st == PlaybackState.STATE_FAST_FORWARDING ||
                        st == PlaybackState.STATE_REWINDING
                }
            if (playingController != null) {
                return playingController
            }

            // 优先级 2：最近活跃的控制器
            return activeControllers.firstOrNull()
        }
    }

    private fun updateMetadataIfPrimary(controller: MediaController) {
        val metadata = controller.metadata ?: return
        val pkg = controller.packageName

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val primary = getPrimaryController() ?: return

        // 只有当这是主控制器时才处理
        if (controller !== primary) return

        val artBitmap =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val artHash = artBitmap?.hashCode() ?: 0

        val metadataHash = Objects.hash(rawTitle, rawArtist, pkg, duration, artHash)

        if (metadataHash == lastMetadataHash) {
            return
        }
        lastMetadataHash = metadataHash

        // 歌词字段映射：歌词位于 title（多数应用）还是 artist（如 fnos 音乐）
        val (mappedTitle, mappedArtist) = resolveLyricField(pkg, rawTitle ?: "", rawArtist ?: "")

        // 通知 NotifyRelayNotificationListenerService 有新的媒体会话数据
        NotifyRelayNotificationListenerService.onMediaSessionUpdated(
            pkg,
            mappedTitle,
            mappedArtist,
            duration,
            artBitmap,
        )
    }

    /**
     * 判定歌词所在字段并完成字段映射（仅作用于获取层，下游仍按 title=歌词位 约定处理）。
     *
     * 判定依据：歌词会随播放进度持续变化，歌名/歌手相对稳定。
     * - 仅 title 变化 → 歌词在 title；仅 artist 变化 → 歌词在 artist
     * - 两字段同时变化（切歌）或内容未变化（同一状态被本链路反复处理）：不参与判定，也不打断已有计数
     * - 结论按包名常驻内存（跨服务重建、跨切歌保留），仅在该应用行为确实反转时才切换
     * - 同向连续 [LYRIC_FIELD_CONFIRM_THRESHOLD] 次后确认；未确认前默认 TITLE（兼容既有行为）
     *
     * @return Pair(歌词位文本, 歌手位文本)
     */
    private fun resolveLyricField(
        pkg: String,
        title: String,
        artist: String,
    ): Pair<String, String> {
        val probe = lyricFieldProbes.computeIfAbsent(pkg) { LyricFieldProbe() }
        return synchronized(probe) {
            val prevTitle = probe.lastTitle
            val prevArtist = probe.lastArtist
            probe.lastTitle = title
            probe.lastArtist = artist

            // 首次观测无基线，不作判定（保持默认 title）
            if (prevTitle != null || prevArtist != null) {
                val titleChanged = prevTitle != title
                val artistChanged = prevArtist != artist
                // 只有单一字段变化才是有效观测（歌词滚动）；
                // 切歌（两字段同变）与重复帧（均未变）都不参与判定，也不打断已有计数
                if (titleChanged != artistChanged) {
                    val current = if (titleChanged) LyricField.TITLE else LyricField.ARTIST
                    if (current == probe.candidate) {
                        probe.streak += 1
                    } else {
                        probe.candidate = current
                        probe.streak = 1
                    }
                    if (probe.streak >= LYRIC_FIELD_CONFIRM_THRESHOLD && probe.confirmed != current) {
                        probe.confirmed = current
                        Logger.i(TAG, "歌词字段切换: $pkg -> $current (title=$title, artist=$artist)")
                    }
                }
            }

            if (probe.confirmed == LyricField.ARTIST) {
                // artist 为歌词位时互换，保证下游拿到的 title 始终是歌词
                artist to title
            } else {
                title to artist
            }
        }
    }
}
