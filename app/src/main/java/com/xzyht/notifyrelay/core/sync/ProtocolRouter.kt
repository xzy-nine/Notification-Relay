package com.xzyht.notifyrelay.core.sync

import android.content.Context
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo

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
        val remotePubKey = parts[2]
        val payload = parts[3]

        val auth = synchronized(deviceManager.authenticatedDevices) { deviceManager.authenticatedDevices[remoteUuid] }
        if (auth == null || !auth.isAccepted) {
            if (BuildConfig.DEBUG) Log.d(TAG, "未认证或未接受的设备，丢弃: uuid=$remoteUuid, header=$header")
            return true
        }

        // 解密
        val decrypted = try { deviceManager.decryptData(payload, auth.sharedSecret) } catch (_: Exception) { null }
        if (decrypted == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "解密失败: uuid=$remoteUuid, header=$header")
            return true
        }

        // 路由
        return try {
            when (header) {
                // 历史兼容：DATA 与 DATA_JSON 均视为通知 JSON
                "DATA", "DATA_JSON" -> {
                    deviceManager.handleNotificationData(decrypted, auth.sharedSecret, remoteUuid)
                    true
                }
                "DATA_ICON_REQUEST" -> {
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    IconSyncManager.handleIconRequest(decrypted, deviceManager, source, context)
                    true
                }
                "DATA_ICON_RESPONSE" -> {
                    IconSyncManager.handleIconResponse(decrypted, context)
                    true
                }
                "DATA_APP_LIST_REQUEST" -> {
                    val source = deviceManager.resolveDeviceInfo(remoteUuid, clientIp)
                    AppListSyncManager.handleAppListRequest(decrypted, deviceManager, source, context)
                    true
                }
                "DATA_APP_LIST_RESPONSE" -> {
                    AppListSyncManager.handleAppListResponse(decrypted, context)
                    true
                }
                else -> {
                    // 未知 DATA_* 报文头，忽略
                    if (BuildConfig.DEBUG) Log.d(TAG, "未知DATA通道: $header")
                    true
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "路由处理异常: header=$header, uuid=$remoteUuid", e)
            true
        }
    }

    // 解密逻辑已由 DeviceConnectionManager 直接提供，无需反射
}
