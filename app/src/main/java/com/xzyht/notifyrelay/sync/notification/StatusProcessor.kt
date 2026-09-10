package com.xzyht.notifyrelay.sync.notification

import android.content.Context
import android.os.Handler
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.sync.ProtocolSender
import kotlinx.coroutines.CoroutineScope
import notifyrelay.base.util.Logger
import notifyrelay.base.util.ToastUtils
import org.json.JSONObject

/**
 * 状态消息处理器
 * 处理DATA_STATUS消息的接收后逻辑
 * 响应消息用作tos是默认行为
 */
object StatusProcessor {
    private const val TAG = "StatusProcessor"

    /**
     * 处理状态消息输入
     */
    data class StatusInput(
        val header: String,
        val rawData: String,
        val remoteUuid: String,
    )

    /**
     * 处理状态消息
     */
    fun process(
        context: Context,
        deviceManager: DeviceConnectionManager,
        coroutineScope: CoroutineScope,
        input: StatusInput,
        callbacks: Collection<(String) -> Unit>,
    ) {
        try {
            Logger.d(TAG, "接收到DATA_STATUS消息: ${input.rawData}")

            val json = JSONObject(input.rawData)

            // 提取关键信息
            val originalHeader = json.optString("originalHeader", "")
            val requestId = json.optString("requestId", "")
            val result = json.optString("result", "")
            val errorCode = json.optString("errorCode", "")
            val errorMessage = json.optString("errorMessage", "")
            val action = json.optString("action", "")

            // 处理不同类型的状态响应
            when (originalHeader) {
                "DATA_FTP" -> {
                    Logger.d(TAG, "FTP状态响应: action=${json.optString("action")}, result=$result")
                }
                "DATA_MEDIA_CONTROL" -> {
                    Logger.d(TAG, "媒体控制状态响应: action=${json.optString("action")}, result=$result")
                }
                "DATA_SUPERISLAND" -> {
                    // 处理超级岛相关状态响应
                    handleSuperIslandStatusResponse(json, deviceManager, input.remoteUuid)
                }
                else -> {
                    // 处理其他类型的状态响应
                    Logger.d(TAG, "处理其他类型的状态响应: $originalHeader")
                }
            }

            // 触发回调
            callbacks.forEach {
                it(input.rawData)
            }

            val isSuperIslandAck = action == "SI_ACK"
            if (!isSuperIslandAck) {
                // 无论是错误还是正确，都显示响应中的消息信息
                // 确保在主线程中显示Toast
                Handler(context.mainLooper).post {
                    if (errorMessage.isNotEmpty()) {
                        ToastUtils.showShortToast(context, errorMessage)
                    } else if (result == "success") {
                        // 如果是成功响应，直接显示"成功"，这样后续只需要添加排除，不需要增加成功响应
                        ToastUtils.showShortToast(context, "成功")
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理DATA_STATUS消息失败", e)
        }
    }

    /**
     * 处理超级岛状态响应
     */
    private fun handleSuperIslandStatusResponse(
        json: JSONObject,
        deviceManager: DeviceConnectionManager,
        remoteUuid: String,
    ) {
        val action = json.optString("action", "")
        val hash = json.optString("hash", "")
        val featureKeyValue = json.optString("featureKeyValue", "")

        Logger.d(TAG, "处理超级岛状态响应: action=$action")

        // 注：超级岛 ACK（SI_ACK）已由 Rust 合并引擎在接收端内部消费，平台无需再处理。

        // 根据需要处理其他类型的超级岛状态响应
    }

    /**
     * 发送状态响应
     */
    fun sendStatusResponse(
        deviceManager: DeviceConnectionManager,
        deviceInfo: DeviceInfo,
        originalHeader: String,
        result: String,
        errorCode: String = "",
        errorMessage: String = "",
        requestId: String = "",
    ) {
        try {
            val raw =
                JSONObject()
                    .apply {
                        put("originalHeader", originalHeader)
                        put("result", result)
                        if (errorCode.isNotEmpty()) {
                            put("errorCode", errorCode)
                        }
                        if (errorMessage.isNotEmpty()) {
                            put("errorMessage", errorMessage)
                        }
                        if (requestId.isNotEmpty()) {
                            put("requestId", requestId)
                        }
                    }.toString()
            ProtocolSender.sendEncrypted(
                deviceManager,
                deviceInfo,
                "DATA_STATUS",
                raw,
            )

            Logger.d(TAG, "发送DATA_STATUS响应: originalHeader=$originalHeader, result=$result")
        } catch (e: Exception) {
            Logger.e(TAG, "发送DATA_STATUS响应失败", e)
        }
    }
}
