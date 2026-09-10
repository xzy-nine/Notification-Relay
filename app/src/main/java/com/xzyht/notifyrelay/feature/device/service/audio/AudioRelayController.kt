package com.xzyht.notifyrelay.feature.device.service.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.xzyht.notifyrelay.feature.audio.AudioRelayPlayer
import com.xzyht.notifyrelay.feature.audio.service.AudioRelayForegroundService
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.media.service.MediaProjectionForegroundService
import com.xzyht.notifyrelay.nativecore.NativeCore
import io.github.miuzarte.scrcpyforandroid.services.AudioForwardingService
import notifyrelay.base.util.ToastUtils
import notifyrelay.data.StorageManager
import notifyrelay.data.config.ScrcpyDefaults

/**
 * 音频中继（投送/接收）的完整生命周期控制。
 *
 * 与设备连接解耦：本类只依赖「按 uuid 反查设备」与「发送一条 DATA_MEDIA_CONTROL 报文」两个能力，
 * 由装配方以函数注入，避免反向依赖设备连接管理器。
 *
 * 两种工作模式（`audio_relay_mode`）：
 * - `1` 中继模式：本机屏幕捕获 → Rust 音频流通道 → 对端播放（`AudioRelayPlayer.start("send")`）；
 * - `0` scrcpy 模式：请求对端通过 scrcpy 转发音频（`AudioForwardingService`）。
 *
 * 时序（中继模式，发起方视角）：
 * ```mermaid
 * sequenceDiagram
 *     participant UI as MusicControlPage
 *     participant Ctrl as AudioRelayController
 *     participant Host as MainActivity
 *     participant Core as NativeCore.mediaProjection
 *     participant Player as AudioRelayPlayer
 *     participant Peer as 远端设备
 *
 *     UI->>Ctrl: requestSendTo(ip, name, uuid)
 *     Ctrl->>Ctrl: pendingSend = PendingSend(...)
 *     Ctrl->>Host: onRequestMediaProjection()
 *     Host->>Core: 写入授权得到的 MediaProjection
 *     Host->>Ctrl: startPendingSend()
 *     Ctrl->>Player: start("send", remoteUuid)
 *     Ctrl->>Player: startSendCapture(projection)
 *     Ctrl->>Ctrl: showRelayNotification() + 注册停止广播
 *     Peer->>Ctrl: audioStop
 *     Ctrl->>Peer: DATA_MEDIA_CONTROL ack（可选）
 *     Ctrl->>Player: stop()
 * ```
 */
class AudioRelayController(
    private val context: Context,
    /** 按 uuid 反查设备信息（用于发送控制报文与展示名称）。 */
    private val resolveDeviceInfo: (uuid: String) -> DeviceInfo?,
    /** 发送一条 DATA_MEDIA_CONTROL 明文报文。 */
    private val sendMediaControl: (target: DeviceInfo, plaintext: String) -> Unit,
) {
    companion object {
        const val HEADER_MEDIA_CONTROL = "DATA_MEDIA_CONTROL"

        /** 中继模式开关取值 */
        const val RELAY_MODE = 1

        private const val ACTION_AUDIO_RESPONSE = "audioResponse"
    }

    /** 供 UI 直接操作播放器（启停收发）。 */
    val player by lazy { AudioRelayPlayer(context) }

    /**
     * 屏幕捕获授权请求回调。由 UI 层（MainActivity）注入，
     * 在需要发起投送会话时触发系统授权流程。
     */
    var onRequestMediaProjection: (() -> Unit)? = null

    private var currentRemoteUuid: String? = null
    private var stopReceiver: BroadcastReceiver? = null

    private data class PendingSend(
        val deviceIp: String,
        val deviceName: String,
        val remoteUuid: String,
    )

    private var pendingSend: PendingSend? = null

    // ==================== UI 入口 ====================

    /** 发起向指定设备的音频投送：先请求屏幕捕获授权，授权后由 [startPendingSend] 启动。 */
    fun requestSendTo(
        deviceIp: String,
        deviceName: String,
        remoteUuid: String,
    ) {
        pendingSend = PendingSend(deviceIp, deviceName, remoteUuid)
        onRequestMediaProjection?.invoke()
    }

    /** 屏幕捕获授权成功后调用，启动挂起的投送会话。 */
    fun startPendingSend() {
        val pending = pendingSend ?: return
        pendingSend = null
        val projection = NativeCore.mediaProjection ?: return
        currentRemoteUuid = pending.remoteUuid
        if (!player.start("send", remoteUuid = pending.remoteUuid)) {
            // 启动失败：清理本次会话状态与投影资源，不进入捕获/前台通知流程
            currentRemoteUuid = null
            cleanupMediaProjection()
            return
        }
        player.startSendCapture(projection)
        showRelayNotification(pending.deviceName, direction = "send")
    }

    /** 主动停止中继（UI 入口）：先通知远端，再本地清理。 */
    fun stop() {
        val uuid = currentRemoteUuid
        if (!uuid.isNullOrEmpty()) {
            resolveDeviceInfo(uuid)?.let {
                sendMediaControl(it, """{"type":"MEDIA_CONTROL","action":"audioStop"}""")
            }
        }
        player.stop()
        cancelRelayNotification()
        cleanupMediaProjection()
    }

    // ==================== 远端消息处理 ====================

    /**
     * 远端请求音频转发（`audioRequest`）：
     * - 中继模式：暂存目标并请求本机屏幕捕获授权，授权后开始投送；
     * - scrcpy 模式：直接通过 scrcpy 启动转发并回执结果。
     */
    fun handleAudioRequest(remoteUuid: String) {
        val device = resolveDeviceInfo(remoteUuid) ?: return
        val relayMode = StorageManager.getInt(context, "audio_relay_mode", 0)
        if (relayMode == RELAY_MODE) {
            pendingSend = PendingSend(device.ip, device.displayName, remoteUuid)
            onRequestMediaProjection?.invoke()
            return
        }
        val ok = AudioForwardingService.startAudioForwarding(context, device.ip, ScrcpyDefaults.ADB_PORT, device.displayName)
        val result = if (ok) "accepted" else "rejected"
        sendMediaControl(device, """{"type":"MEDIA_CONTROL","action":"$ACTION_AUDIO_RESPONSE","result":"$result"}""")
    }

    /** 远端对 `audioRequest` 的回执（`audioResponse`）。 */
    fun handleAudioResponse(accepted: Boolean) {
        if (accepted) return
        // 由 Rust 回调线程触发，Toast 必须在主线程 Looper 上执行
        Handler(Looper.getMainLooper()).post {
            ToastUtils.showShortToast(context, "音频转发请求被拒绝")
        }
    }

    /** 远端开始推送音频（`audioStart`）：启动接收侧播放并展示前台通知。 */
    fun startReceive(
        remoteUuid: String,
        sampleRate: Int,
        channels: Int,
    ) {
        val device = resolveDeviceInfo(remoteUuid)
        currentRemoteUuid = remoteUuid
        if (!player.start("recv", sampleRate, channels, remoteUuid = remoteUuid)) {
            // 启动失败：清理本次会话状态与投影资源，不进入前台通知流程
            currentRemoteUuid = null
            cleanupMediaProjection()
            return
        }
        showRelayNotification(device?.displayName ?: device?.ip ?: "", remoteUuid = remoteUuid)
    }

    /**
     * 远端停止推送音频（`audioStop`）：本地清理，并在对端未附带结果时回执 ok。
     */
    fun stopFromRemote(
        remoteUuid: String,
        replyAck: Boolean,
    ) {
        player.stop()
        cancelRelayNotification()
        cleanupMediaProjection()
        if (!replyAck) return
        resolveDeviceInfo(remoteUuid)?.let {
            sendMediaControl(it, """{"type":"MEDIA_CONTROL","action":"audioStop","result":"ok"}""")
        }
    }

    /** 停止并清空屏幕投影，避免下次授权时 stop 旧投影触发 onStop 回调干扰新会话。 */
    fun cleanupMediaProjection() {
        try {
            NativeCore.mediaProjection?.stop()
        } catch (_: Exception) {
        }
        NativeCore.mediaProjection = null
    }

    // ==================== 前台通知与停止广播 ====================

    private fun showRelayNotification(
        deviceName: String,
        remoteUuid: String = "",
        direction: String = "recv",
    ) {
        if (remoteUuid.isNotEmpty()) {
            currentRemoteUuid = remoteUuid
        }
        registerStopReceiver()
        AudioRelayForegroundService.start(context, deviceName, direction)
    }

    private fun registerStopReceiver() {
        if (stopReceiver != null) return
        stopReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    if (intent?.action == AudioRelayForegroundService.STOP_ACTION) {
                        stop()
                    }
                }
            }
        try {
            context.registerReceiver(
                stopReceiver,
                IntentFilter(AudioRelayForegroundService.STOP_ACTION),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (_: Exception) {
        }
    }

    private fun cancelRelayNotification() {
        try {
            stopReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        stopReceiver = null
        AudioRelayForegroundService.stop(context)
        MediaProjectionForegroundService.stop(context)
    }
}
