package com.xzyht.notifyrelay.core.sync

import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 统一加密发送器
 *
 * 封装加密、认证检查、TCP发送与报文头拼装：
 * 最终报文格式：`<HEADER>:<localUuid>:<localPublicKey>:<encryptedPayload>\n`
 */
object ProtocolSender {

    private const val TAG = "ProtocolSender"
    private const val DEFAULT_TIMEOUT = 10000L

    /**
     * 发送一条加密负载到指定设备。
     * @param header 例如：DATA_JSON / DATA_ICON_REQUEST / DATA_ICON_RESPONSE / DATA_APP_LIST_REQUEST 等
     * @param plaintext 明文 JSON 字符串
     */
    fun sendEncrypted(
        deviceManager: DeviceConnectionManager,
        target: DeviceInfo,
        header: String,
        plaintext: String,
        timeoutMs: Long = DEFAULT_TIMEOUT
    ) {
        try {
            val auth = deviceManager.authenticatedDevices[target.uuid]
            if (auth == null || !auth.isAccepted) {
                if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证或未接受：${target.displayName}")
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withTimeout(timeoutMs) {
                        val socket = java.net.Socket()
                        try {
                            socket.connect(java.net.InetSocketAddress(target.ip, target.port), 5000)
                            val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                            val encrypted = deviceManager.encryptData(plaintext, auth.sharedSecret)
                            val payload = "$header:${deviceManager.uuid}:${deviceManager.localPublicKey}:${encrypted}"
                            writer.write(payload + "\n")
                            writer.flush()
                            if (BuildConfig.DEBUG) Log.d(TAG, "已发送 $header -> ${target.displayName}")
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "发送失败 $header -> ${target.displayName}", e)
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送异常: $header", e)
        }
    }
}
