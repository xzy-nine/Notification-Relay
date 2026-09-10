package com.xzyht.notifyrelay.feature.media.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
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

        // 已知歌词字段的包名：切歌后快速监听（100ms 粒度，持续 1.5s），
        // 首次歌词滚动一出现即切 LYRIC，不再走慢速阶梯
        private const val FAST_WATCH_INTERVAL_MS = 100L
        private const val FAST_WATCH_WINDOW_MS = 1_500L

        // 纯音乐/无歌词判定阈值（按包名历史自适应）
        private const val T_INSTRUMENTAL_FAST_MS = 2_000L
        private const val T_INSTRUMENTAL_NORMAL_MS = 4_000L
        private const val T_INSTRUMENTAL_SLOW_MS = 6_000L

        // 歌词字段未知时的保守阶梯
        private val COLD_PKG_RECHECK_DELAYS_MS =
            longArrayOf(1_000L, 2_000L, T_INSTRUMENTAL_NORMAL_MS, T_INSTRUMENTAL_SLOW_MS)

        // 歌词字段观测状态：按包名保存，且需跨服务实例保留
        // （监听服务会被系统频繁解绑重建，实例字段会导致学习状态丢失）
        private val lyricFieldProbes = ConcurrentHashMap<String, LyricFieldProbe>()
    }

    // 歌词所在字段
    private enum class LyricField { TITLE, ARTIST }

    // 当前歌曲的歌词状态（per-song，切歌即重置）
    private enum class LyricState {
        // 新歌：等待判断是否带歌词
        PENDING_LYRIC,

        // T_SOFT 已到仍无歌词滚动：倾向纯音乐，但暂不回退
        PENDING_INSTRUMENTAL,

        // T_HARD 已到仍无歌词滚动：纯音乐/无歌词，回退原始字段
        INSTRUMENTAL,

        // 本首歌已出现歌词滚动：粘滞歌词（前奏/间奏不再回退）
        LYRIC,
    }

    // 按包名的歌词字段观测状态
    private class LyricFieldProbe {
        var lastTitle: String? = null
        var lastArtist: String? = null
        var candidate: LyricField? = null
        var streak: Int = 0
        var confirmed: LyricField? = null
        // 当前歌曲标识（切歌即变化，用标题区分：歌词不影响该值）
        var songId: String? = null
        // 当前歌曲起始时间（用于软/硬超时判定）
        var songStartAt: Long = 0L
        var state: LyricState = LyricState.PENDING_LYRIC
        // 包名历史：用于自适应纯音乐判定阈值
        var lyricHits: Int = 0
        var instrHits: Int = 0
        // 最近一次播放位置（用于"位置推进但无歌词"的直接判定）
        var lastPosition: Long = -1L
        // 复核上报所需的原始信息
        var lastDuration: Long = 0L
        var lastArt: Bitmap? = null
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
        val position = controller.playbackState?.position ?: -1L
        val (mappedTitle, mappedArtist) =
            resolveLyricField(pkg, rawTitle ?: "", rawArtist ?: "", duration, artBitmap, position)

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
     * 字段判定（按包名常驻，跨服务重建与切歌保留）：
     * - 仅 title 变化 → 歌词在 title；仅 artist 变化 → 歌词在 artist
     * - 同向连续 [LYRIC_FIELD_CONFIRM_THRESHOLD] 次后确认；未确认前默认 TITLE（兼容既有行为）
     *
     * 歌曲状态（per-song，切歌即重置）：
     * - [LyricState.PENDING_LYRIC]：新歌，等待判断；主位暂显歌名/标题
     * - [LyricState.PENDING_INSTRUMENTAL]：超过 [T_SOFT_MS] 仍无歌词滚动，倾向纯音乐但不回退
     * - [LyricState.INSTRUMENTAL]：超过 [T_HARD_MS] 仍无歌词滚动，回退原始字段
     * - [LyricState.LYRIC]：本首歌已出现歌词滚动，粘滞（前奏/间奏不再回退）
     *
     * @return Pair(歌词位文本, 歌手位文本)
     */
    private fun resolveLyricField(
        pkg: String,
        title: String,
        artist: String,
        duration: Long,
        artBitmap: Bitmap?,
        position: Long,
    ): Pair<String, String> {
        val probe = lyricFieldProbes.computeIfAbsent(pkg) { LyricFieldProbe() }
        return synchronized(probe) {
            probe.lastDuration = duration
            probe.lastArt = artBitmap

            val prevTitle = probe.lastTitle
            val prevArtist = probe.lastArtist
            probe.lastTitle = title
            probe.lastArtist = artist
            val prevPosition = probe.lastPosition
            probe.lastPosition = position

            val now = System.currentTimeMillis()
            // 歌曲标识优先用时长：歌词位不参与（歌词在 title 的应用其 title 每句都变），
            // 时长未知的流媒体再退回标题/歌手
            val songId =
                when {
                    duration > 0L -> duration.toString()
                    title.isNotEmpty() -> title
                    else -> artist
                }

            if (prevTitle == null && prevArtist == null) {
                // 首次观测：建立本首歌基线
                probe.songId = songId
                probe.songStartAt = now
                probe.state = LyricState.PENDING_LYRIC
            } else {
                val titleChanged = prevTitle != title
                val artistChanged = prevArtist != artist

                if (songId != probe.songId) {
                    // 切歌：结算上一首（带歌词/纯音乐），状态按歌曲重置，不沿用上一首结论
                    if (probe.state == LyricState.LYRIC) probe.lyricHits += 1 else probe.instrHits += 1
                    probe.songId = songId
                    probe.songStartAt = now
                    probe.state = LyricState.PENDING_LYRIC
                    probe.candidate = null
                    probe.streak = 0
                    scheduleLyricProbeRechecks(probe)
                } else if (titleChanged != artistChanged) {
                    // 单一字段变化：歌词滚动
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
                    if (probe.state != LyricState.LYRIC) {
                        probe.state = LyricState.LYRIC
                        Logger.i(TAG, "歌词状态: $pkg -> LYRIC (title=$title, artist=$artist)")
                    }
                }

                // 软/硬超时推进（帧驱动 + 定时复核双保险）
                // LYRIC 状态粘滞：同一 songId 内即使长时间无歌词变化（前奏/间奏）也不回退
                if (probe.state == LyricState.PENDING_LYRIC || probe.state == LyricState.PENDING_INSTRUMENTAL) {
                    val threshold = instrumentalThreshold(probe)
                    // 有播放位置时优先按歌曲内进度判定（暂停不推进，暂停期间不会误判）
                    val playedEnough = position > prevPosition && position >= threshold
                    val elapsed = now - probe.songStartAt
                    if (elapsed >= threshold || playedEnough) {
                        probe.state = LyricState.INSTRUMENTAL
                        Logger.i(TAG, "歌词状态: $pkg -> INSTRUMENTAL (title=$title, artist=$artist)")
                    } else if (probe.state == LyricState.PENDING_LYRIC && elapsed >= threshold / 2) {
                        probe.state = LyricState.PENDING_INSTRUMENTAL
                    }
                }
            }

            if (probe.state == LyricState.LYRIC && probe.confirmed == LyricField.ARTIST) {
                // artist 为歌词位时互换，保证下游拿到的 title 始终是歌词
                artist to title
            } else {
                // 等待判定/纯音乐/歌词在 title：主位保留歌名或视频标题
                title to artist
            }
        }
    }

    /**
     * 纯音乐判定阈值：按包名历史自适应。
     * 常出现纯音乐的包名 2s 即可回退标题，出现过歌词的包名 4s，新包名保守 6s。
     */
    private fun instrumentalThreshold(probe: LyricFieldProbe): Long {
        val total = probe.lyricHits + probe.instrHits
        if (total == 0) return T_INSTRUMENTAL_SLOW_MS
        val instrRatio = probe.instrHits.toFloat() / total
        return when {
            instrRatio > 0.8f -> T_INSTRUMENTAL_FAST_MS
            probe.lyricHits > 0 -> T_INSTRUMENTAL_NORMAL_MS
            else -> T_INSTRUMENTAL_SLOW_MS
        }
    }

    /**
     * 新歌开始后的主动复核：
     * - 已知歌词字段（confirmed=ARTIST）的包名：100ms 粒度快速监听 1.5s，
     *   首次歌词滚动一出现即切 LYRIC，不再走慢速阶梯；窗口结束再按自适应阈值复核一次
     * - 字段未知的包名：走保守阶梯
     */
    private fun scheduleLyricProbeRechecks(probe: LyricFieldProbe) {
        handler.removeCallbacks(lyricProbeRecheckRunnable)
        val delays = mutableListOf<Long>()
        if (probe.confirmed == LyricField.ARTIST) {
            var d = FAST_WATCH_INTERVAL_MS
            while (d <= FAST_WATCH_WINDOW_MS) {
                delays.add(d)
                d += FAST_WATCH_INTERVAL_MS
            }
            delays.add(instrumentalThreshold(probe))
        } else {
            COLD_PKG_RECHECK_DELAYS_MS.forEach { delays.add(it) }
        }
        delays.forEach { delay -> handler.postDelayed(lyricProbeRecheckRunnable, delay) }
    }

    private val lyricProbeRecheckRunnable =
        Runnable {
            val primary = getPrimaryController() ?: return@Runnable
            val pkg = primary.packageName
            val probe = lyricFieldProbes[pkg] ?: return@Runnable
            val cachedTitle = probe.lastTitle ?: return@Runnable
            val cachedArtist = probe.lastArtist ?: ""
            val before = probe.state
            val position = primary.playbackState?.position ?: -1L
            val (mappedTitle, mappedArtist) =
                resolveLyricField(pkg, cachedTitle, cachedArtist, probe.lastDuration, probe.lastArt, position)
            // 仅在状态确实推进时重新上报，避免重复推送
            if (probe.state != before) {
                NotifyRelayNotificationListenerService.onMediaSessionUpdated(
                    pkg,
                    mappedTitle,
                    mappedArtist,
                    probe.lastDuration,
                    probe.lastArt,
                )
            }
        }
}
