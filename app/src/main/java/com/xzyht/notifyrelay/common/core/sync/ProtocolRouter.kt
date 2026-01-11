package com.xzyht.notifyrelay.common.core.sync

import android.content.Context
import android.os.Build
import android.os.Environment
import com.xzyht.notifyrelay.common.core.sync.SftpServer.StartResult.ALREADY_RUNNING
import com.xzyht.notifyrelay.common.core.sync.SftpServer.StartResult.PERMISSION_DENIED
import com.xzyht.notifyrelay.common.core.sync.SftpServer.StartResult.SUCCESS
import com.xzyht.notifyrelay.common.core.util.IntentUtils
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.GuideActivity
import com.xzyht.notifyrelay.feature.device.service.AuthInfo
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.superisland.RemoteMediaSessionManager
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 统一协议路由器
 *
 * 职责：
 * - 统一解析 TCP 文本首行中的 DATA_* 报文头
 * - 统一做认证检查与解密
 * - 将明文负载转发给对应的功能模块（通知/图标/应用列表等）
 *
 * 不处理：HANDSHAKE、HEARTBEAT、以及“手动发现”之类的特殊报文（仍由 DeviceConnectionManager 内部处理）。
 */
object ProtocolRouter {
    private const val TAG = "ProtocolRouter"
    private const val DEVICE_TYPE_PC = "pc"

    private fun isRemoteDevicePc(auth: AuthInfo?): Boolean {
        // 只允许明确为PC设备的情况
        // 设备类型应该从握手消息中正确获取并设置
        return auth?.deviceType?.lowercase() == DEVICE_TYPE_PC
    }

    /**
     * 处理一条 DATA* 加密通道的 TCP 首行。
     * @return true 表示已处理并应由上层关闭当前连接；false 表示非本路由器负责。
     */
    fun handleEncryptedDataLine(
        line: String,
        clientIp: String,
        deviceManager: DeviceConnectionManager,
        context: Context
    ): Boolean {
        // 仅处理以 DATA 开头的加密通道
        if (!line.startsWith("DATA")) return false

        // 统一解析：DATA_*:<remoteUuid>:<remotePubKey>:<encryptedPayload>
        val parts = line.split(":", limit = 4)
        if (parts.size < 4) return true 

        val header = parts[0]
        val remoteUuid = parts[1]
        parts[2]
        val payload = parts[3]

        val auth = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices[remoteUuid] }
        if (auth == null || !auth.isAccepted) {
            //Logger.d(TAG, "未认证或未接受的设备，丢弃: uuid=$remoteUuid, header=$header")
            return true
        }

        // 解密
        val decrypted = try { deviceManager.decryptData(payload, auth.sharedSecret) } catch (_: Exception) { null }
        if (decrypted == null) {
            //Logger.d(TAG, "解密失败: uuid=$remoteUuid, header=$header")
            return true
        }

        // 路由
        return try {
            when (header) {
                // 主通道：历史上的 DATA 默认为普通通知（DATA_NOTIFICATION）
                "DATA", "DATA_NOTIFICATION" -> {
                    Logger.d(TAG, "接收到 DATA_NOTIFICATION 消息: $decrypted")
                    val routedHeader = "DATA_NOTIFICATION"
                    com.xzyht.notifyrelay.common.core.notification.NotificationProcessor.process(
                        context,
                        deviceManager,
                        deviceManager.coroutineScopeInternal,
                        com.xzyht.notifyrelay.common.core.notification.NotificationProcessor.NotificationInput(
                            header = routedHeader,
                            rawData = decrypted,
                            sharedSecret = auth.sharedSecret,
                            remoteUuid = remoteUuid
                        ),
                        deviceManager.notificationDataReceivedCallbacksInternal
                    )
                    true
                }
                "DATA_SUPERISLAND" -> {
                    // 分流到 SuperIslandProcessor 专门处理超级岛通知
                    Logger.d(TAG, "接收到 DATA_NOTIFICATION 消息: $decrypted")
                    try {
                        val handled = com.xzyht.notifyrelay.common.core.notification.SuperIslandProcessor.process(
                            context,
                            deviceManager,
                            decrypted,
                            auth.sharedSecret,
                            remoteUuid
                        )
                        if (handled) return true
                    } catch (e: Exception) {
                        Logger.e(TAG, "SuperIsland 处理异常", e)
                    }
                    true
                }
                "DATA_MEDIAPLAY" -> {
                    // 处理远端媒体播放通知，触发超级岛显示
                    Logger.d(TAG, "接收到 DATA_MEDIAPLAY 消息: $decrypted")
                    try {
                        val json = JSONObject(decrypted)
                        val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                        Logger.i(TAG, "收到远端媒体播放DATA_MEDIAPLAY: ${json.optString("title", "")} - ${json.optString("text", "")} (来自 ${source?.displayName ?: "未知设备"})")
                        RemoteMediaSessionManager.onMediaMessageReceived(context, json, source!!)
                    } catch (e: Exception) {
                        Logger.e(TAG, "处理远端媒体播放通知DATA_MEDIAPLAY", e)
                    }
                    true
                }
                // DATA_ICON_REQUEST：对方向本机请求应用图标，本机查找后会通过 DATA_ICON_RESPONSE 回传
                "DATA_ICON_REQUEST" -> {
                    Logger.d(TAG, "接收到 DATA_ICON_REQUEST 消息: $decrypted")
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    IconSyncManager.handleIconRequest(decrypted, deviceManager, source, context)
                    true
                }
                // DATA_ICON_RESPONSE：图标请求的响应，更新本机图标缓存供通知复刻使用
                "DATA_ICON_RESPONSE" -> {
                    Logger.d(TAG, "接收到 DATA_ICON_RESPONSE 消息: $decrypted")
                    IconSyncManager.handleIconResponse(decrypted, context)
                    true
                }
                // DATA_APP_LIST_REQUEST：对方请求本机应用列表，本机查询后通过 DATA_APP_LIST_RESPONSE 返回
                "DATA_APP_LIST_REQUEST" -> {
                    Logger.d(TAG, "接收到 DATA_APP_LIST_REQUEST 消息: $decrypted")
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    AppListSyncManager.handleAppListRequest(decrypted, deviceManager, source, context)
                    true
                }
                // DATA_APP_LIST_RESPONSE：应用列表请求的响应，用于更新本机缓存/状态
                "DATA_APP_LIST_RESPONSE" -> {
                    Logger.d(TAG, "接收到 DATA_APP_LIST_RESPONSE 消息: $decrypted")
                    AppListSyncManager.handleAppListResponse(decrypted, context, remoteUuid)
                    true
                }
                // DATA_AUDIO_REQUEST：对方请求本机音频转发
                "DATA_MEDIA_CONTROL" -> {
                    Logger.d(TAG, "接收到 DATA_MEDIA_CONTROL 消息: $decrypted")
                    // 处理媒体控制命令，包括音频转发和媒体播放控制
                    try {
                        val json = org.json.JSONObject(decrypted)
                        val action = json.getString("action")
                        
                        // 执行相应的媒体控制操作，优先通过通知的 PendingIntent 触发
                        when (action) {
                            // 媒体播放控制
                            "playPause" -> {
                                try {
                                    val sbn = com.xzyht.notifyrelay.common.core.notification.servers.NotifyRelayNotificationListenerService.latestMediaSbn
                                    if (sbn != null) {
                                        com.xzyht.notifyrelay.common.core.util.MediaControlUtil.triggerPlayPauseFromNotification(sbn)
                                    } else {
                                        Logger.w(TAG, "playPause: 未找到媒体通知，无法触发本机媒体操作")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(TAG, "执行 playPause 失败", e)
                                }
                            }
                            "next" -> {
                                try {
                                    val sbn = com.xzyht.notifyrelay.common.core.notification.servers.NotifyRelayNotificationListenerService.latestMediaSbn
                                    if (sbn != null) {
                                        com.xzyht.notifyrelay.common.core.util.MediaControlUtil.triggerNextFromNotification(sbn)
                                    } else {
                                        Logger.w(TAG, "next: 未找到媒体通知，无法触发本机媒体操作")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(TAG, "执行 next 失败", e)
                                }
                            }
                            "previous" -> {
                                try {
                                    val sbn = com.xzyht.notifyrelay.common.core.notification.servers.NotifyRelayNotificationListenerService.latestMediaSbn
                                    if (sbn != null) {
                                        com.xzyht.notifyrelay.common.core.util.MediaControlUtil.triggerPreviousFromNotification(sbn)
                                    } else {
                                        Logger.w(TAG, "previous: 未找到媒体通知，无法触发本机媒体操作")
                                    }
                                } catch (e: Exception) {
                                    Logger.e(TAG, "执行 previous 失败", e)
                                }
                            }
                            // 音频转发控制（仅对 PC 设备响应）
                            "audioRequest" -> {
                                if (!isRemoteDevicePc(auth)) {
                                    Logger.w(TAG, "音频转发请求被忽略：非 PC 设备")
                                } else {
                                    val response = "{\"type\":\"MEDIA_CONTROL\",\"action\":\"audioResponse\",\"result\":\"accepted\"}"
                                    ProtocolSender.sendEncrypted(deviceManager, deviceManager.resolveDeviceInfo(remoteUuid, clientIp), "DATA_MEDIA_CONTROL", response)
                                }
                            }
                            "audioResponse" -> {
                                // 处理音频转发响应，这里可以添加相应的逻辑
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "处理媒体控制命令失败", e)
                    }
                    true
                }
                "DATA_SFTP" -> {
                    Logger.d(TAG, "接收到 DATA_SFTP 消息，clientIp: $clientIp, remoteUuid: $remoteUuid")
                    if (!isRemoteDevicePc(auth)) {
                        Logger.w(TAG, "SFTP 请求被忽略：非 PC 设备")
                        return true
                    }
                    Logger.d(TAG, "设备类型验证通过，开始处理 SFTP 命令")
                    // 使用设备管理器的协程作用域处理 suspend 函数调用
                    deviceManager.coroutineScopeInternal.launch {
                        try {
                            val json = JSONObject(decrypted)
                            val action = json.optString("action", "")
                            Logger.i(TAG, "SFTP 命令 action: $action")

                            when (action) {
                                "start" -> {
                                    Logger.i(TAG, "开始启动 SFTP 服务器")
                                    val sharedSecret = auth.sharedSecret
                                    val deviceName = deviceManager.getLocalDisplayName()
                                    Logger.d(TAG, "使用共享密钥派生 SFTP 凭据")
                                    val sftpStartResult = SftpServer.start(sharedSecret, deviceName, context)
                                    when (sftpStartResult.status) {
                                        SUCCESS, ALREADY_RUNNING -> {
                                            val sftpInfo = sftpStartResult.serverInfo
                                            if (sftpInfo != null) {
                                                Logger.i(TAG, "SFTP 服务器启动成功，IP: ${sftpInfo.ipAddress}, 端口: ${sftpInfo.port}")
                                                val responseJson = JSONObject().apply {
                                                    put("action", "started")
                                                    put("ipAddress", sftpInfo.ipAddress)
                                                    put("port", sftpInfo.port)
                                                    // 不再发送用户名和密码，PC端可以从sharedSecret独立计算
                                                }
                                                Logger.d(TAG, "发送 SFTP 服务器信息到 PC")
                                                ProtocolSender.sendEncrypted(
                                                    deviceManager,
                                                    deviceManager.resolveDeviceInfo(remoteUuid, clientIp),
                                                    "DATA_SFTP",
                                                    responseJson.toString()
                                                )
                                                Logger.i(TAG, "SFTP server started and info sent to PC (derived from sharedSecret)")
                                                
                                                // 检查是否需要跳转到引导页授权
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                    if (!Environment.isExternalStorageManager()) {
                                                        // 跳转到引导页，让用户手动授权
                                                        val intent = IntentUtils.createIntent(context, GuideActivity::class.java)
                                                        intent.putExtra("fromSftp", true)
                                                        intent.putExtra("fromInternal", true)
                                                        IntentUtils.startActivity(context, intent, true)
                                                    }
                                                }
                                            }
                                        }

                                        PERMISSION_DENIED -> TODO()
                                    }
                                }
                                "stop" -> {
                                    Logger.i(TAG, "停止 SFTP 服务器")
                                    SftpServer.stop()
                                    val responseJson = JSONObject().apply {
                                        put("action", "stopped")
                                    }
                                    ProtocolSender.sendEncrypted(
                                        deviceManager,
                                        deviceManager.resolveDeviceInfo(remoteUuid, clientIp),
                                        "DATA_SFTP",
                                        responseJson.toString()
                                    )
                                    Logger.i(TAG, "SFTP server stopped via command")
                                }
                                else -> {
                                    Logger.w(TAG, "未知的 SFTP action: $action")
                                }
                            }
                        } catch (e: Exception) {
                            Logger.e(TAG, "处理 SFTP 命令失败", e)
                        }
                    }
                    true
                }
                else -> {
                    // 其他未识别的 DATA_* 报文：当前版本不支持，直接忽略（方便后向兼容）
                    Logger.d(TAG, "未知DATA通道: $header")
                    true
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "路由处理异常: header=$header, uuid=$remoteUuid", e)
            true
        }
    }

    // 解密逻辑已由 DeviceConnectionManager 直接提供，无需反射
}
