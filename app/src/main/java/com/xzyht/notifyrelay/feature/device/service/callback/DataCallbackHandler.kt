package com.xzyht.notifyrelay.feature.device.service.callback

import android.os.Build
import android.os.Environment
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.xzyht.notifyrelay.feature.appslist.launch.AppLaunchManager
import com.xzyht.notifyrelay.feature.appslist.sync.AppListSyncManager
import com.xzyht.notifyrelay.feature.appslist.sync.IconSyncManager
import com.xzyht.notifyrelay.feature.clipboard.ClipboardProcessor
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.media.MediaControlUtil
import com.xzyht.notifyrelay.feature.media.RemoteMediaSessionManager
import com.xzyht.notifyrelay.nativecore.NotifyRelayCore
import com.xzyht.notifyrelay.sync.FtpServerManager
import com.xzyht.notifyrelay.sync.FtpServerManager.StartResult
import com.xzyht.notifyrelay.sync.notification.NotificationProcessor
import com.xzyht.notifyrelay.sync.notification.StatusProcessor
import com.xzyht.notifyrelay.sync.notification.SuperIslandProcessor
import com.xzyht.notifyrelay.ui.activity.GuideActivity
import kotlinx.coroutines.launch
import notifyrelay.base.util.IntentUtils
import notifyrelay.base.util.Logger
import org.json.JSONObject

/**
 * `nrc_set_on_data_cb` 回调处理器（统一数据通道分发）。
 *
 * 支持的通道：`NOTIFICATION` / `MEDIAPLAY` / `ICON_REQUEST` / `ICON_RESPONSE` /
 * `APP_LIST_REQUEST` / `APP_LIST_RESPONSE` / `MEDIA_CONTROL` / `FTP` / `CLIPBOARD` /
 * `STATUS` / `APP_LAUNCH` / `SUPERISLAND`。
 *
 * 约束：
 * - 未认证设备的数据包一律丢弃（`DATA_UNKNOWN` 除外）；
 * - 回调运行在 JNA 附加线程，耗时/阻塞操作必须投递到 [DeviceCallbackHost.callbackScope]；
 * - 回调内禁止同步调用 `nrc_get_device_list`（core 重入崩溃）。
 */
class DataCallbackHandler(
    private val host: DeviceCallbackHost,
) {
    companion object {
        private const val TAG = "CoreCb"
    }

    fun build(): NotifyRelayCore.OnDataCb =
        object : NotifyRelayCore.OnDataCb {
            override fun invoke(
                uuidPtr: Pointer?,
                msgTypePtr: Pointer?,
                plaintextPtr: Pointer?,
                userData: Pointer?,
            ) {
                Native.detach(false) // JNA 附加线程回调返回时不 detach，避免嵌套调用 JNA 时 abort
                val uuid = NotifyRelayCore.ptrToString(uuidPtr) ?: return
                val msgType = NotifyRelayCore.ptrToString(msgTypePtr) ?: return
                val text = NotifyRelayCore.ptrToString(plaintextPtr) ?: return
                val authed =
                    synchronized(host.authenticatedDeviceTable) {
                        host.authenticatedDeviceTable[uuid]?.isAccepted == true
                    }
                Logger.d(TAG, "on_data: type=$msgType, authed=$authed, text_len=${text.length}")
                if (!authed && msgType != "DATA_UNKNOWN") return

                try {
                    when (msgType) {
                        "NOTIFICATION" ->
                            NotificationProcessor.process(
                                host.callbackContext,
                                host.deviceManager,
                                host.callbackScope,
                                NotificationProcessor.NotificationInput("DATA_NOTIFICATION", text, uuid),
                                host.notificationDataReceivedCallbacks,
                            )
                        "MEDIAPLAY" -> {
                            val json = JSONObject(text)
                            host.resolveDevice(uuid)?.let {
                                RemoteMediaSessionManager.onMediaMessageReceived(host.callbackContext, json, it)
                            }
                        }
                        "ICON_REQUEST" -> {
                            host.resolveDevice(uuid)?.let {
                                IconSyncManager.handleIconRequest(text, host.deviceManager, it, host.callbackContext)
                            }
                        }
                        "ICON_RESPONSE" -> IconSyncManager.handleIconResponse(text, host.callbackContext)
                        "APP_LIST_REQUEST" -> {
                            host.resolveDevice(uuid)?.let {
                                AppListSyncManager.handleAppListRequest(text, host.deviceManager, it, host.callbackContext)
                            }
                        }
                        "APP_LIST_RESPONSE" ->
                            AppListSyncManager.handleAppListResponse(text, host.callbackContext, uuid, host.deviceManager)
                        "MEDIA_CONTROL" -> handleMediaControl(uuid, text)
                        "FTP" -> handleFtp(uuid, text)
                        "CLIPBOARD" ->
                            ClipboardProcessor.process(host.callbackContext, ClipboardProcessor.ClipboardInput("DATA_CLIPBOARD", text, ""))
                        "STATUS" ->
                            StatusProcessor.process(
                                host.callbackContext,
                                host.deviceManager,
                                host.callbackScope,
                                StatusProcessor.StatusInput("DATA_STATUS", text, uuid),
                                host.notificationDataReceivedCallbacks,
                            )
                        "APP_LAUNCH" -> {
                            host.resolveDevice(uuid)?.let {
                                AppLaunchManager.handleAppLaunchRequest(text, host.deviceManager, it, host.callbackContext)
                            }
                        }
                        "SUPERISLAND" -> SuperIslandProcessor.process(host.callbackContext, host.deviceManager, text, uuid)
                        else -> Logger.d(TAG, "未知DATA通道: type=$msgType, uuid=$uuid, size=${text.length}")
                    }
                } catch (e: Exception) {
                    Logger.e(TAG, "on_data error: ${e.message}")
                }
            }
        }

    /** 媒体控制：播放控制直达本机媒体，音频中继动作交由 [DeviceCallbackHost.audioRelay]。 */
    private fun handleMediaControl(
        uuid: String,
        text: String,
    ) {
        val json = JSONObject(text)
        when (val action = json.getString("action")) {
            "playPause" -> runLocalMediaAction(uuid, action) { MediaControlUtil.playPause() }
            "next" -> runLocalMediaAction(uuid, action) { MediaControlUtil.next() }
            "previous" -> runLocalMediaAction(uuid, action) { MediaControlUtil.previous() }
            "audioRequest" -> host.callbackScope.launch { host.audioRelay.handleAudioRequest(uuid) }
            "audioResponse" ->
                host.audioRelay.handleAudioResponse(json.optString("result", "rejected") == "accepted")
            "audioStart" ->
                host.callbackScope.launch {
                    host.audioRelay.startReceive(
                        remoteUuid = uuid,
                        sampleRate = json.optInt("sampleRate", 48000),
                        channels = json.optInt("channels", 2),
                    )
                }
            "audioStop" ->
                host.callbackScope.launch {
                    host.audioRelay.stopFromRemote(
                        remoteUuid = uuid,
                        replyAck = json.optString("result", "").isEmpty(),
                    )
                }
        }
    }

    /** 执行本机媒体控制并回执结果（失败原因随回执一并返回远端）。 */
    private fun runLocalMediaAction(
        uuid: String,
        action: String,
        block: () -> Unit,
    ) {
        try {
            block()
            sendMediaControlResponse(uuid, action, "success", null)
        } catch (e: Exception) {
            sendMediaControlResponse(uuid, action, "error", e.message)
        }
    }

    /** 媒体控制响应：以 DATA_STATUS 承载，originalHeader 标明真实来源。 */
    private fun sendMediaControlResponse(
        remoteUuid: String,
        action: String,
        result: String,
        errorMessage: String?,
    ) {
        try {
            val raw =
                JSONObject()
                    .apply {
                        put("originalHeader", "DATA_MEDIA_CONTROL")
                        put("action", action)
                        put("result", result)
                        if (errorMessage != null) put("errorMessage", errorMessage)
                    }.toString()
            host.resolveDevice(remoteUuid)?.let { replyToDevice(it, "DATA_STATUS", raw) }
        } catch (e: Exception) {
            Logger.e(TAG, "sendMediaControlResponse", e)
        }
    }

    /** FTP：仅接受设备类型为 pc 的对端请求（启动/停止本机 FTP 服务）。 */
    private fun handleFtp(
        uuid: String,
        text: String,
    ) {
        val isPc =
            synchronized(host.authenticatedDeviceTable) {
                host.authenticatedDeviceTable[uuid]?.deviceType?.lowercase() == "pc"
            }
        if (!isPc) return
        host.callbackScope.launch {
            try {
                val json = JSONObject(text)
                when (json.optString("action", "")) {
                    "start" -> handleFtpStart(uuid, json)
                    "stop" -> {
                        FtpServerManager.stop()
                        val raw = JSONObject().apply { put("action", "stopped") }.toString()
                        host.resolveDevice(uuid)?.let { replyToDevice(it, "DATA_FTP", raw) }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "DATA_FTP", e)
            }
        }
    }

    private fun handleFtpStart(
        uuid: String,
        json: JSONObject,
    ) {
        val pcUser = json.optString("username", null)
        val pcPass = json.optString("password", null)
        val result = FtpServerManager.start(host.localDisplayName(), host.callbackContext, pcUser, pcPass)
        when (result.status) {
            StartResult.SUCCESS, StartResult.ALREADY_RUNNING -> {
                result.serverInfo?.let { info ->
                    val raw =
                        JSONObject()
                            .apply {
                                put("action", "started")
                                put("ipAddress", info.ipAddress)
                                put("port", info.port)
                            }.toString()
                    host.resolveDevice(uuid)?.let { replyToDevice(it, "DATA_FTP", raw) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        val intent = IntentUtils.createIntent(host.callbackContext, GuideActivity::class.java)
                        intent.putExtra("fromftp", true)
                        intent.putExtra("fromInternal", true)
                        IntentUtils.startActivity(host.callbackContext, intent, true)
                    }
                }
            }
            else -> {
                val err =
                    when (result.status) {
                        StartResult.PERMISSION_DENIED -> "PERMISSION_DENIED"
                        StartResult.PORT_IN_USE -> "PORT_IN_USE"
                        StartResult.CONFIG_ERROR -> "CONFIG_ERROR"
                        else -> "FAILED"
                    }
                val raw =
                    JSONObject()
                        .apply {
                            put("originalHeader", "DATA_FTP")
                            put("action", "start")
                            put("result", "error")
                            put("errorCode", err)
                        }.toString()
                host.resolveDevice(uuid)?.let { replyToDevice(it, "DATA_STATUS", raw) }
            }
        }
    }

    private fun replyToDevice(
        target: DeviceInfo,
        header: String,
        raw: String,
    ) {
        host.sendDataMessage(target, header, raw)
    }
}
