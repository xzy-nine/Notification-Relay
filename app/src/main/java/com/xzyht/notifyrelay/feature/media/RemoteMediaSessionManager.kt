package com.xzyht.notifyrelay.feature.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.superisland.store.SuperIslandRemoteStore
import com.xzyht.notifyrelay.feature.notification.superisland.media.MediaCapsulePresenter
import com.xzyht.notifyrelay.feature.notification.superisland.replica.FloatingReplicaManager
import com.xzyht.notifyrelay.nativecore.NativeCore
import com.xzyht.notifyrelay.sync.ProtocolSender
import github.xzynine.superislandui.diff.DiffSystem
import github.xzynine.superislandui.model.components.MediaSessionData
import io.github.miuzarte.scrcpyforandroid.services.AudioForwardingService
import notifyrelay.base.util.Logger
import notifyrelay.data.StorageManager
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class MediaMessageReceiveMode {
    On,
    Off,
    AudioOnly,
}

object RemoteMediaSessionManager {
    private const val KEY_ENABLED = "remote_media_island_enabled"
    private const val DEFAULT_ENABLED = true
    private const val KEY_RECEIVE_MODE = "remote_media_message_receive_mode"
    private const val MODE_ON = 0
    private const val MODE_OFF = 1
    private const val MODE_AUDIO_ONLY = 2

    // 会话和设备信息，需要线程安全访问
    @Volatile
    private var currentSession: MediaSessionData? = null

    @Volatile
    private var currentDevice: DeviceInfo? = null

    private var isEnabled: Boolean = true

    // 应用上下文，用于定期检查任务
    private var applicationContext: Context? = null

    // 固定sourceKey前缀，以设备为单位
    private const val SOURCE_KEY_PREFIX = "media_island"

    // 媒体会话特征ID缓存，用于sourceId计算
    private val mediaFeatureIdCache = ConcurrentHashMap<String, String>()

    // 媒体会话最后更新时间缓存
    private val mediaLastUpdateTime = ConcurrentHashMap<String, Long>()

    // 媒体会话数据缓存，用于定时复传
    private val mediaSessionCache = ConcurrentHashMap<String, MediaSessionCacheData>()

    // 超时时间（毫秒），与发送端超时发送时间匹配并略长（16秒）
    private const val MEDIA_SESSION_TIMEOUT_MS = 16 * 1000L

    // 定时复传间隔（毫秒），设置为6秒，确保在12秒自动关闭前更新两次
    private const val MEDIA_SESSION_RESEND_INTERVAL_MS = 6 * 1000L

    // 定时检查超时会话的间隔（毫秒）
    private const val CLEANUP_INTERVAL_MS = 3 * 1000L

    // 用于处理延迟任务的Handler（所有操作都在主线程串行执行）
    private val handler = Handler(Looper.getMainLooper())

    // 定期检查超时会话的任务
    @Volatile
    private var cleanupRunnable: Runnable? = null

    // 清理循环是否运行中（有活跃会话时才运行，无会话即停止，避免常驻空转）
    // 访问保护：所有读写都通过 handler 串行执行
    @Volatile
    private var cleanupLoopRunning = false

    // 创建定期检查任务
    private fun createCleanupRunnable(): Runnable =
        Runnable {
            try {
                // 使用保存的应用上下文
                val context = applicationContext
                if (context != null) {
                    cleanupTimeoutSessionsOnHandler(context)
                } else {
                    Logger.w("RemoteMediaSessionManager", "应用上下文未初始化，跳过定期检查")
                }
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "定期检查超时会话失败", e)
            } finally {
                // 仍有活跃会话才继续调度，否则停止循环（已在 handler 线程，直接读写）
                if (cleanupLoopRunning) {
                    cleanupRunnable?.let { handler.postDelayed(it, CLEANUP_INTERVAL_MS) }
                }
            }
        }

    // 媒体会话缓存数据类
    private data class MediaSessionCacheData(
        val context: Context,
        val session: MediaSessionData,
        val device: DeviceInfo,
        val resendRunnable: Runnable,
    )

    fun init(context: Context) {
        // 保存应用上下文
        applicationContext = context.applicationContext

        val mode = getReceiveMode(context)
        isEnabled = mode != MediaMessageReceiveMode.Off
        Logger.i("RemoteMediaSessionManager", "远端媒体超级岛接收模式: $mode")
    }

    /**
     * 确保超时会话清理循环在运行（有活跃媒体会话时调用）
     * 通过 handler 串行执行，保护 cleanupLoopRunning 读写和 callback 调度
     */
    private fun ensureCleanupLoop() {
        handler.post {
            if (cleanupLoopRunning) {
                return@post
            }
            cleanupLoopRunning = true
            val runnable = cleanupRunnable ?: createCleanupRunnable().also { cleanupRunnable = it }
            handler.removeCallbacks(runnable)
            handler.postDelayed(runnable, CLEANUP_INTERVAL_MS)
        }
    }

    /**
     * 停止超时会话清理循环（无活跃会话时）
     * 通过 handler 串行执行，保护 cleanupLoopRunning 读写和 callback 调度
     */
    private fun stopCleanupLoop() {
        handler.post {
            cleanupLoopRunning = false
            cleanupRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    fun isEnabled(context: Context): Boolean = getReceiveMode(context) != MediaMessageReceiveMode.Off

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        setReceiveMode(context, if (enabled) MediaMessageReceiveMode.On else MediaMessageReceiveMode.Off)
    }

    fun getReceiveMode(context: Context): MediaMessageReceiveMode {
        val stored =
            try {
                StorageManager.getInt(context, KEY_RECEIVE_MODE, -1)
            } catch (_: Exception) {
                -1
            }

        if (stored == -1) {
            val enabled =
                try {
                    StorageManager.getBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)
                } catch (_: Exception) {
                    DEFAULT_ENABLED
                }
            return if (enabled) MediaMessageReceiveMode.On else MediaMessageReceiveMode.Off
        }

        return when (stored) {
            MODE_OFF -> MediaMessageReceiveMode.Off
            MODE_AUDIO_ONLY -> MediaMessageReceiveMode.AudioOnly
            else -> MediaMessageReceiveMode.On
        }
    }

    fun setReceiveMode(
        context: Context,
        mode: MediaMessageReceiveMode,
    ) {
        try {
            val value =
                when (mode) {
                    MediaMessageReceiveMode.On -> MODE_ON
                    MediaMessageReceiveMode.Off -> MODE_OFF
                    MediaMessageReceiveMode.AudioOnly -> MODE_AUDIO_ONLY
                }
            StorageManager.putInt(context, KEY_RECEIVE_MODE, value)
            StorageManager.putBoolean(context, KEY_ENABLED, mode != MediaMessageReceiveMode.Off)
            isEnabled = mode != MediaMessageReceiveMode.Off
            if (mode == MediaMessageReceiveMode.Off) {
                clearSession()
            }
            Logger.i("RemoteMediaSessionManager", "远端媒体超级岛接收模式已设置为: $mode")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "设置远端媒体超级岛接收模式失败", e)
        }
    }

    private fun shouldReceiveMediaMessage(context: Context): Boolean =
        when (getReceiveMode(context)) {
            MediaMessageReceiveMode.On -> true
            MediaMessageReceiveMode.Off -> false
            MediaMessageReceiveMode.AudioOnly -> AudioForwardingService.isAudioForwardingRunning()
        }

    fun onMediaMessageReceived(
        context: Context,
        json: JSONObject,
        device: DeviceInfo,
    ) {
        // 所有入口逻辑都串行在 handler 上执行，保护会话状态读写和清理循环调度
        handler.post {
            processMediaMessageOnHandler(context, json, device)
        }
    }

    private fun processMediaMessageOnHandler(
        context: Context,
        json: JSONObject,
        device: DeviceInfo,
    ) {
        if (!shouldReceiveMediaMessage(context)) {
            Logger.d("RemoteMediaSessionManager", "远端媒体消息未接收或未满足音频条件，伪造结束以关闭浮窗")
            closeSessionForDevice(device, "接收条件不满足")
            return
        }

        try {
            val mediaType = json.optString("mediaType", "")
            val terminateValue = json.optString("terminateValue", "")
            val isEndPackage = mediaType.equals("END", true) || terminateValue.equals("__END__", true)

            val sourceKey = SOURCE_KEY_PREFIX + "_" + device.uuid

            if (isEndPackage) {
                Logger.i("RemoteMediaSessionManager", "收到媒体会话结束包，关闭浮窗: ${device.displayName}")
                closeSessionForDevice(device, "收到结束包")
                return
            }

            val packageName = json.optString("packageName", "")
            val appName = json.optString("appName", "")
            val title = json.optString("title", "")
            val text = json.optString("text", "")
            val coverUrl = json.optString("coverUrl", "")
            val timestamp = json.optLong("time", System.currentTimeMillis())

            if (title.isBlank() && text.isBlank()) {
                Logger.w("RemoteMediaSessionManager", "收到空的媒体会话数据，继续处理以保持浮窗活跃")
            }

            val oldSession = mediaSessionCache[device.uuid]?.session
            val finalTitle = title.ifBlank { oldSession?.title ?: "" }
            val finalText = text.ifBlank { oldSession?.text ?: "" }
            val finalCoverUrl = coverUrl.ifBlank { oldSession?.coverUrl }

            currentSession =
                MediaSessionData(
                    packageName = packageName,
                    appName = appName,
                    title = finalTitle,
                    text = finalText,
                    coverUrl = finalCoverUrl,
                    deviceName = device.displayName,
                    timestamp = timestamp,
                )
            currentDevice = device

            val currentFeatureId =
                NativeCore.computeFeatureId(
                    packageName,
                    "",
                    title,
                    text,
                    "",
                ) ?: ""

            mediaFeatureIdCache[device.uuid] = currentFeatureId
            mediaLastUpdateTime[device.uuid] = System.currentTimeMillis()
            cleanupTimeoutSessionsOnHandler(context)
            setupResendTask(context, device.uuid, currentSession!!, device)
            // 有活跃会话，确保清理循环运行（已在 handler 线程，直接执行）
            ensureCleanupLoopOnHandler()

            val currentState = buildMediaState(finalTitle, finalText, finalCoverUrl)
            applyMediaSessionState(sourceKey, currentState, appName, context)

            Logger.i("RemoteMediaSessionManager", "更新远端媒体会话: $title - $text (来自 ${device.displayName})")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "处理远端媒体消息失败", e)
        }
    }

    /**
     * 确保清理循环运行（handler 线程内部调用，无需再 post）
     */
    private fun ensureCleanupLoopOnHandler() {
        if (cleanupLoopRunning) {
            return
        }
        cleanupLoopRunning = true
        val runnable = cleanupRunnable ?: createCleanupRunnable().also { cleanupRunnable = it }
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, CLEANUP_INTERVAL_MS)
    }

    fun clearSession() {
        // 清除所有设备的媒体会话浮窗
        mediaFeatureIdCache.keys.forEach { deviceUuid ->
            val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
            try {
                // 取消复传任务
                cancelResendTask(deviceUuid)
                // 从Store中移除
                SuperIslandRemoteStore.removeExact(sourceKey)
                // 关闭浮窗
                FloatingReplicaManager
                    .dismissBySource(sourceKey)
                Logger.i("RemoteMediaSessionManager", "已关闭设备媒体超级岛浮窗: $sourceKey")
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "关闭媒体超级岛浮窗失败: $sourceKey", e)
            }
        }
        mediaFeatureIdCache.clear()
        mediaLastUpdateTime.clear()
        mediaSessionCache.clear()
        currentSession = null
        currentDevice = null
        stopCleanupLoop()
        Logger.i("RemoteMediaSessionManager", "已清除所有远端媒体会话")
    }

    private fun closeSessionForDevice(
        device: DeviceInfo,
        reason: String,
    ) {
        val sourceKey = SOURCE_KEY_PREFIX + "_" + device.uuid
        try {
            cancelResendTask(device.uuid)
            SuperIslandRemoteStore.removeExact(sourceKey)
            FloatingReplicaManager
                .dismissBySource(sourceKey)
            mediaFeatureIdCache.remove(device.uuid)
            mediaLastUpdateTime.remove(device.uuid)
            mediaSessionCache.remove(device.uuid)
            if (currentDevice?.uuid == device.uuid) {
                currentSession = null
                currentDevice = null
            }
            Logger.i("RemoteMediaSessionManager", "已关闭设备媒体超级岛浮窗: ${device.displayName}, reason=$reason")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "关闭媒体超级岛浮窗失败: $sourceKey", e)
        }
    }

    fun getCurrentSession(): MediaSessionData? = currentSession

    fun getCurrentDevice(): DeviceInfo? = currentDevice

    fun sendMediaControl(
        context: Context,
        deviceManager: DeviceConnectionManager,
        action: String,
    ) {
        val device = currentDevice ?: return

        try {
            val raw =
                JSONObject()
                    .apply {
                        put("type", "MEDIA_CONTROL")
                        put("action", action)
                    }.toString()
            ProtocolSender.sendEncrypted(deviceManager, device, "DATA_MEDIA_CONTROL", raw)
            Logger.i("RemoteMediaSessionManager", "已发送媒体控制指令: $action 到 ${device.displayName}")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "发送媒体控制指令失败", e)
        }
    }

    fun onPlayPause(
        context: Context,
        deviceManager: DeviceConnectionManager,
    ) {
        sendMediaControl(context, deviceManager, "playPause")
    }

    fun onPrevious(
        context: Context,
        deviceManager: DeviceConnectionManager,
    ) {
        sendMediaControl(context, deviceManager, "previous")
    }

    fun onNext(
        context: Context,
        deviceManager: DeviceConnectionManager,
    ) {
        sendMediaControl(context, deviceManager, "next")
    }

    /**
     * 检查并清理超时的媒体会话（handler 线程内部调用）
     */
    private fun cleanupTimeoutSessionsOnHandler(context: Context) {
        val currentTime = System.currentTimeMillis()
        val timeoutDevices = mutableListOf<String>()

        // 找出超时的设备
        for ((deviceUuid, lastUpdateTime) in mediaLastUpdateTime) {
            if (currentTime - lastUpdateTime > MEDIA_SESSION_TIMEOUT_MS) {
                timeoutDevices.add(deviceUuid)
            }
        }

        // 清理超时会话
        for (deviceUuid in timeoutDevices) {
            val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
            try {
                // 取消复传任务
                cancelResendTask(deviceUuid)
                // 从Store中移除
                SuperIslandRemoteStore.removeExact(sourceKey)
                // 关闭浮窗
                FloatingReplicaManager
                    .dismissBySource(sourceKey)
                // 清除缓存
                mediaFeatureIdCache.remove(deviceUuid)
                mediaLastUpdateTime.remove(deviceUuid)
                mediaSessionCache.remove(deviceUuid)
                // 如果是当前会话，清除当前会话
                if (currentDevice?.uuid == deviceUuid) {
                    currentSession = null
                    currentDevice = null
                }
                Logger.i("RemoteMediaSessionManager", "已清理超时的媒体会话: $deviceUuid")
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "清理超时媒体会话失败: $deviceUuid", e)
            }
        }

        // 无任何活跃会话时停止清理循环，避免常驻空转（已在 handler 线程，直接执行）
        if (mediaLastUpdateTime.isEmpty()) {
            cleanupLoopRunning = false
            cleanupRunnable?.let { handler.removeCallbacks(it) }
        }
    }

    // 构建媒体全量状态（Rust 合并引擎已输出全量，本地无需 diff）
    private fun buildMediaState(
        title: String,
        text: String,
        coverUrl: String?,
    ): DiffSystem.State {
        val currentPics = mutableMapOf<String, String>()
        if (!coverUrl.isNullOrBlank()) currentPics["miui.focus.pic_cover"] = coverUrl
        return DiffSystem.State(
            title,
            text,
            MediaCapsulePresenter.buildParamV2(title, text),
            currentPics,
        )
    }

    // 直接以全量状态更新浮窗（Rust 合并引擎已输出全量，本地无需差异合并）
    private fun applyMediaSessionState(
        sourceKey: String,
        currentState: DiffSystem.State,
        appName: String?,
        context: Context,
    ) {
        // 以全量形式写入远端存储，保持 store 语义（结束包/清理时仍可移除）
        val payload =
            JSONObject().apply {
                put("title", currentState.title ?: "")
                put("text", currentState.text ?: "")
                if (!currentState.paramV2Raw.isNullOrBlank()) {
                    put("param_v2_raw", currentState.paramV2Raw)
                }
                if (currentState.pics.isNotEmpty()) {
                    put("pics", JSONObject(currentState.pics))
                }
            }
        SuperIslandRemoteStore.applyIncoming(sourceKey, payload)
        MediaCapsulePresenter.show(
            context = context,
            sourceId = sourceKey,
            title = currentState.title ?: "",
            text = currentState.text ?: "",
            appName = appName,
            picMap = currentState.pics,
        )
    }

    /**
     * 创建或更新定时复传任务
     */
    private fun setupResendTask(
        context: Context,
        deviceUuid: String,
        session: MediaSessionData,
        device: DeviceInfo,
    ) {
        cancelResendTask(deviceUuid)

        val resendRunnable =
            Runnable {
                try {
                    val originalLastUpdateTime = mediaLastUpdateTime[deviceUuid] ?: System.currentTimeMillis()
                    if (System.currentTimeMillis() - originalLastUpdateTime > (MEDIA_SESSION_TIMEOUT_MS - 1000)) {
                        Logger.i("RemoteMediaSessionManager", "媒体会话已接近超时，停止复传: $deviceUuid")
                        return@Runnable
                    }

                    val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
                    val currentState = buildMediaState(session.title, session.text, session.coverUrl)
                    applyMediaSessionState(sourceKey, currentState, session.appName, context)

                    setupResendTask(context, deviceUuid, session, device)
                } catch (e: Exception) {
                    Logger.e("RemoteMediaSessionManager", "定时复传媒体会话失败: $deviceUuid", e)
                }
            }

        handler.postDelayed(resendRunnable, MEDIA_SESSION_RESEND_INTERVAL_MS)
        mediaSessionCache[deviceUuid] =
            MediaSessionCacheData(
                context = context,
                session = session,
                device = device,
                resendRunnable = resendRunnable,
            )
    }

    /**
     * 取消定时复传任务
     */
    private fun cancelResendTask(deviceUuid: String) {
        val cacheData = mediaSessionCache.remove(deviceUuid)
        if (cacheData != null) {
            handler.removeCallbacks(cacheData.resendRunnable)
        }
    }
}
