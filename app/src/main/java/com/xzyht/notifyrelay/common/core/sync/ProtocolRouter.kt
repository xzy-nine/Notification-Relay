package com.xzyht.notifyrelay.common.core.sync

import android.content.Context
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager

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
        if (parts.size < 4) return true // 形如 DATA:xxx 但结构不满足，直接忽略

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
                // DATA / DATA_JSON：远程通知主通道（含超级岛、去重与复刻等完整处理），入口统一交给 NotificationProcessor
                "DATA", "DATA_JSON" -> {
                    com.xzyht.notifyrelay.common.core.notification.NotificationProcessor.process(
                        context,
                        deviceManager,
                        deviceManager.coroutineScopeInternal,
                        com.xzyht.notifyrelay.common.core.notification.NotificationProcessor.NotificationInput(
                            rawData = decrypted,
                            sharedSecret = auth.sharedSecret,
                            remoteUuid = remoteUuid
                        ),
                        deviceManager.notificationDataReceivedCallbacksInternal
                    )
                    true
                }
                // DATA_ICON_REQUEST：对方向本机请求应用图标，本机查找后会通过 DATA_ICON_RESPONSE 回传
                "DATA_ICON_REQUEST" -> {
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    IconSyncManager.handleIconRequest(decrypted, deviceManager, source, context)
                    true
                }
                // DATA_ICON_RESPONSE：图标请求的响应，更新本机图标缓存供通知复刻使用
                "DATA_ICON_RESPONSE" -> {
                    IconSyncManager.handleIconResponse(decrypted, context)
                    true
                }
                // DATA_APP_LIST_REQUEST：对方请求本机应用列表，本机查询后通过 DATA_APP_LIST_RESPONSE 返回
                "DATA_APP_LIST_REQUEST" -> {
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    AppListSyncManager.handleAppListRequest(decrypted, deviceManager, source, context)
                    true
                }
                // DATA_APP_LIST_RESPONSE：应用列表请求的响应，用于更新本机缓存/状态
                "DATA_APP_LIST_RESPONSE" -> {
                    AppListSyncManager.handleAppListResponse(decrypted, context, remoteUuid)
                    true
                }
                else -> {
                    // 其他未识别的 DATA_* 报文：当前版本不支持，直接忽略（方便后向兼容）
                    //Logger.d(TAG, "未知DATA通道: $header")
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
