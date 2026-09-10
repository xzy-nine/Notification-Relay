package com.xzyht.notifyrelay.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.util.Base64
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import notifyrelay.base.util.Logger
import notifyrelay.base.util.PermissionHelper
import org.json.JSONObject

/**
 * 消息发送类
 * 整合聊天测试和普通通知转发的消息发送功能
 * 发送队列、限流、重试与去重均由 Rust core 发送队列统一处理
 */
object MessageSender {
    private const val TAG = "MessageSender"

    /**
     * 组装「全量」媒体状态（与 Rust 合并引擎对齐，供主动推送与查询回调共用）。
     */
    fun buildMediaFullContent(
        context: Context,
        packageName: String,
        appName: String?,
        title: String?,
        text: String?,
        coverUrl: String?,
        time: Long,
        isPlaying: Boolean = true,
    ): String {
        val isLocked = PermissionHelper.isDeviceLocked(context)
        return JSONObject()
            .apply {
                put("packageName", packageName)
                put("appName", appName ?: packageName)
                put("title", title ?: "")
                put("text", text ?: "")
                put("coverUrl", coverUrl ?: "")
                put("time", time)
                put("isLocked", isLocked)
                put("isPlaying", isPlaying)
            }.toString()
    }

    /**
     * 发送聊天测试消息
     * @param context 上下文
     * @param message 消息内容
     * @param deviceManager 设备管理器
     */
    fun sendChatMessage(
        context: Context,
        message: String,
        deviceManager: DeviceConnectionManager,
    ) {
        try {
            // 获取所有已认证设备
            val allDevices =
                deviceManager.devices.value.values
                    .map { it.first }
            val sentAny = allDevices.isNotEmpty() && message.isNotBlank()

            if (!sentAny) {
                Logger.w(TAG, "没有可用的设备或消息为空")
                return
            }

            // 构建标准 JSON 格式的消息
            val pkgName: String = context.packageName
            val raw =
                JSONObject()
                    .apply {
                        put("packageName", pkgName)
                        put("appName", "NotifyRelay")
                        put("title", "聊天测试")
                        put("text", message)
                        put("time", System.currentTimeMillis())
                    }.toString()
            allDevices.forEach { device ->
                enqueueNotification(device, raw, deviceManager, "聊天")
            }

            // 记录到聊天历史
            ChatMemory.append(context, "发送: $message")

            Logger.i(TAG, "聊天消息已加入队列，共发送到 ${allDevices.size} 个设备")
        } catch (e: Exception) {
            Logger.e(TAG, "发送聊天消息失败", e)
        }
    }

    /**
     * 发送媒体播放通知
     * 使用专门的协议前缀标记媒体通知，支持状态变化跟踪
     * 差异计算（FULL/DELTA）、合并、ACK 与心跳均由 Rust 合并引擎负责。
     */

    fun sendMediaPlayNotification(
        context: Context,
        packageName: String,
        appName: String?,
        title: String?,
        text: String?,
        coverUrl: String?,
        time: Long,
        deviceManager: DeviceConnectionManager,
    ) {
        try {
            // 推送「全量」媒体状态：差异计算（FULL/DELTA）、合并与 ACK 均由 Rust 合并引擎负责。
            val ctx = NativeCore.getContext() ?: return
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return

            val content =
                buildMediaFullContent(
                    context,
                    packageName,
                    appName,
                    title,
                    text,
                    coverUrl,
                    time,
                    isPlaying = true,
                )

            getAuthenticatedDevices(deviceManager).forEach { device ->
                try {
                    NativeCore.pushMediaState(ctx, queuePtr, device.uuid, content, false, false)
                } catch (e: Exception) {
                    Logger.w(TAG, "推送媒体状态失败: ${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "发送媒体播放通知失败", e)
        }
    }

    /**
     * 将通知 JSON 入队发送（经 Rust 发送队列：加密、限流、重试、去重由 Rust 统一处理）
     */
    private fun enqueueNotification(
        deviceInfo: DeviceInfo,
        json: String,
        deviceManager: DeviceConnectionManager,
        tag: String = "",
    ): Boolean {
        val ctx = NativeCore.getContext()
        if (ctx == null) {
            Logger.w(TAG, "Rust 未初始化，跳过入队: ${deviceInfo.displayName}")
            return false
        }
        val dedupKey = NativeCore.computeDedupKey(deviceInfo.uuid, json) ?: return false
        val result =
            ProtocolSender.sendEncrypted(
                deviceManager,
                deviceInfo,
                "DATA_NOTIFICATION",
                json,
                dedupKey = dedupKey,
            )
        if (result != ProtocolSender.EnqueueResult.SUCCESS) {
            Logger.w(TAG, "加入${tag}发送队列失败: ${deviceInfo.displayName}, result=$result")
            return false
        }
        return true
    }

    fun sendMediaPlayEndNotification(
        context: Context,
        packageName: String,
        appName: String?,
        time: Long,
        deviceManager: DeviceConnectionManager,
    ) {
        try {
            // 推送结束标记：Rust 合并引擎会回传 terminateValue="__END__" 全量，接收端据此移除媒体卡片。
            val ctx = NativeCore.getContext() ?: return
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return

            getAuthenticatedDevices(deviceManager).forEach { device ->
                try {
                    NativeCore.pushMediaState(ctx, queuePtr, device.uuid, "{}", true, false)
                } catch (e: Exception) {
                    Logger.w(TAG, "推送媒体结束失败: ${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "发送媒体播放结束通知失败", e)
        }
    }

    /**
     * 发送普通通知转发消息
     * @param context 上下文
     * @param packageName 应用包名
     * @param appName 应用名称
     * @param title 通知标题
     * @param text 通知内容
     * @param time 通知时间
     * @param deviceManager 设备管理器
     */
    fun sendNotificationMessage(
        context: Context,
        packageName: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
        deviceManager: DeviceConnectionManager,
    ) {
        try {
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)

            if (authenticatedDevices.isEmpty()) {
                Logger.w(TAG, "没有已认证的设备")
                return
            }

            // 获取锁屏状态
            val isLocked = PermissionHelper.isDeviceLocked(context)

            // 构建标准 JSON 格式的通知数据
            val raw =
                JSONObject()
                    .apply {
                        put("packageName", packageName)
                        put("appName", appName ?: packageName)
                        put("title", title ?: "")
                        put("text", text ?: "")
                        put("time", time)
                        put("isLocked", isLocked)
                    }.toString()
            authenticatedDevices.forEach { deviceInfo ->
                enqueueNotification(deviceInfo, raw, deviceManager, "通知")
            }

            Logger.i(TAG, "通知已加入队列，共 ${authenticatedDevices.size} 个设备")
        } catch (e: Exception) {
            Logger.e(TAG, "发送通知消息失败", e)
        }
    }

    /**
     * 组装「全量」超级岛状态（与 Rust 合并引擎对齐，供主动推送与查询回调共用）。
     * 图片处理：本地 URI/file 路径读取并编码为 base64 data URI，http(s) 地址或其他字符串保持不变。
     */
    suspend fun buildSuperIslandFullContent(
        context: Context,
        superPkg: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        featureIdOverride: String?,
    ): String {
        // 处理图片：若 picMap 中是本地 URI/file 路径则读取并编码为 base64 data URI，http(s) 地址或其他字符串保持不变
        val processedPics = mutableMapOf<String, String>()
        if (picMap != null) {
            withContext(Dispatchers.IO) {
                picMap.forEach { (k, v) ->
                    try {
                        val lower = v.lowercase()
                        if (lower.startsWith("content://") ||
                            lower.startsWith("file://") ||
                            v.startsWith(
                                "/",
                            )
                        ) {
                            try {
                                if (v.startsWith("/")) {
                                    val file = java.io.File(v)
                                    if (file.isFile) {
                                        val bytes = file.readBytes()
                                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                        processedPics[k] = "data:image/png;base64,$b64"
                                    } else {
                                        processedPics[k] = v
                                    }
                                } else {
                                    val uri = Uri.parse(v)
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        val bytes = input.readBytes()
                                        val mime =
                                            context.contentResolver.getType(uri) ?: "image/png"
                                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                        processedPics[k] = "data:$mime;base64,$b64"
                                    } ?: run {
                                        // 无法打开则回退到原始字符串
                                        processedPics[k] = v
                                    }
                                }
                            } catch (e: Exception) {
                                // 读取失败则保留原值
                                Logger.w(TAG, "读取本地图片失败: $v", e)
                                processedPics[k] = v
                            }
                        } else {
                            // 非本地资源（如 http:// 或 已经是 base64 字符串），保持原样
                            processedPics[k] = v
                        }
                    } catch (e: Exception) {
                        processedPics[k] = v
                    }
                }
            }
        }

        val finalPics: Map<String, String> = if (processedPics.isNotEmpty()) processedPics.toMap() else (picMap?.toMap() ?: emptyMap())
        val isLocked = PermissionHelper.isDeviceLocked(context)

        return JSONObject()
            .apply {
                put("packageName", superPkg)
                put("appName", appName ?: superPkg)
                put("title", title ?: "")
                put("text", text ?: "")
                put("param_v2_raw", paramV2Raw ?: "")
                put("time", time)
                put("isLocked", isLocked)
                put("featureIdOverride", featureIdOverride ?: "")
                put("pics", JSONObject(finalPics))
            }.toString()
    }

    /**
     * 发送超级岛专用数据（包含 param_v2 原始 JSON 与图片 map）
     */
    suspend fun sendSuperIslandData(
        context: Context,
        superPkg: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        deviceManager: DeviceConnectionManager,
        featureIdOverride: String? = null,
    ) {
        try {
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)
            if (authenticatedDevices.isEmpty()) {
                Logger.w(TAG, "没有已认证的设备")
                return
            }

            val ctx = NativeCore.getContext() ?: return
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return

            // 组装「全量」超级岛状态：差异计算（FULL/DELTA）、合并、ACK 与心跳均由 Rust 合并引擎负责。
            val content =
                buildSuperIslandFullContent(
                    context,
                    superPkg,
                    appName,
                    title,
                    text,
                    time,
                    paramV2Raw,
                    picMap,
                    featureIdOverride,
                )

            authenticatedDevices.forEach { device ->
                try {
                    NativeCore.pushSuperislandState(ctx, queuePtr, device.uuid, content, false, false)
                } catch (e: Exception) {
                    Logger.e("超级岛", "超级岛: 推送超级岛状态失败: ${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("超级岛", "超级岛: 发送超级岛数据失败", e)
        }
    }

    /**
     * 发送超级岛终止事件：当本地确认没有该超级岛通知时调用。
     */
    fun sendSuperIslandEnd(
        context: Context,
        superPkg: String,
        appName: String?,
        time: Long,
        paramV2Raw: String?,
        title: String?,
        text: String?,
        deviceManager: DeviceConnectionManager,
        featureIdOverride: String? = null,
    ) {
        try {
            // 推送结束标记：Rust 合并引擎会回传 terminateValue="__END__" 全量，接收端据此移除该超级岛卡片。
            val ctx = NativeCore.getContext() ?: return
            val queuePtr = NativeCore.senderQueuePtr
            if (queuePtr == 0L) return

            val content =
                JSONObject()
                    .apply {
                        put("packageName", superPkg)
                        put("appName", appName ?: superPkg)
                        put("title", title ?: "")
                        put("text", text ?: "")
                        put("param_v2_raw", paramV2Raw ?: "")
                        put("time", time)
                        put("featureIdOverride", featureIdOverride ?: "")
                    }.toString()

            getAuthenticatedDevices(deviceManager).forEach { device ->
                try {
                    NativeCore.pushSuperislandState(ctx, queuePtr, device.uuid, content, true, false)
                } catch (e: Exception) {
                    Logger.e("超级岛", "超级岛: 推送超级岛结束失败: ${device.displayName}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e("超级岛", "超级岛: 发送终止事件失败", e)
        }
    }

    /**
     * 发送高优先级悬浮通知（用于应用跳转指示）
     * @param context 上下文
     * @param title 通知标题
     * @param text 通知内容
     */
    fun sendHighPriorityNotification(
        context: Context,
        title: String?,
        text: String?,
    ) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "notifyrelay_temp"

            // 创建通知渠道（如果不存在）
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel =
                    NotificationChannel(channelId, "跳转通知", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "应用内跳转指示通知"
                        enableLights(true)
                        lightColor = Color.BLUE
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setShowBadge(false)
                        importance = NotificationManager.IMPORTANCE_HIGH
                        setBypassDnd(true)
                    }
                notificationManager.createNotificationChannel(channel)
            }

            // 构建通知
            val builder =
                Notification.Builder(context, channelId).apply {
                    setContentTitle(title ?: "(无标题)")
                    setContentText(text ?: "(无内容)")
                    setSmallIcon(android.R.drawable.ic_dialog_info)
                    setCategory(Notification.CATEGORY_MESSAGE)
                    setAutoCancel(true)
                    setVisibility(Notification.VISIBILITY_PUBLIC)
                    setOngoing(false)
                }

            // 发送通知，使用当前时间戳作为ID
            val notifyId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            notificationManager.notify(notifyId, builder.build())

            // 5秒后自动销毁通知
            Handler(context.mainLooper).postDelayed({
                notificationManager.cancel(notifyId)
            }, 5000)

            // Logger.d(TAG, "高优先级悬浮通知已发送: $title")
        } catch (e: Exception) {
            Logger.e(TAG, "发送高优先级通知失败", e)
        }
    }

    /**
     * 获取已认证的设备列表
     * @param deviceManager 设备管理器
     * @return 已认证设备的列表
     */
    private fun getAuthenticatedDevices(deviceManager: DeviceConnectionManager): List<DeviceInfo> {
        return try {
            val field = deviceManager::class.java.getDeclaredField("authenticatedDevices")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val authedMap = field.get(deviceManager) as? Map<String, *>

            val myUuidField = deviceManager::class.java.getDeclaredField("uuid")
            myUuidField.isAccessible = true
            val myUuid = myUuidField.get(deviceManager) as? String

            val authenticatedDevices = mutableListOf<DeviceInfo>()

            authedMap?.forEach { (uuid, _) ->
                val uuidStr = uuid as String
                if (uuidStr == myUuid) return@forEach

                val infoMethod = deviceManager::class.java.getDeclaredMethod("getDeviceInfo", String::class.java)
                infoMethod.isAccessible = true
                val deviceInfo = infoMethod.invoke(deviceManager, uuidStr) as? DeviceInfo

                if (deviceInfo != null) {
                    authenticatedDevices.add(deviceInfo)
                }
            }

            authenticatedDevices
        } catch (e: Exception) {
            Logger.e(TAG, "获取已认证设备列表失败", e)
            emptyList()
        }
    }

    /**
     * 检查是否有可用的设备
     * @param deviceManager 设备管理器
     * @return 是否有可用的设备
     */
    fun hasAvailableDevices(deviceManager: DeviceConnectionManager): Boolean = deviceManager.devices.value.isNotEmpty()

    /**
     * 检查消息是否有效
     * @param message 消息内容
     * @return 消息是否有效
     */
    fun isValidMessage(message: String?): Boolean = !message.isNullOrBlank()
}
