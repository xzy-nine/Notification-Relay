package com.xzyht.notifyrelay.core.util

import android.content.Context
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.device.ui.DeviceForwardFragment
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 消息发送工具类
 * 整合聊天测试和普通通知转发的消息发送功能
 * 支持队列和限流，避免大量并发发送导致的通知丢失
 */
object MessageSender {

    private const val TAG = "MessageSender"
    private const val MAX_CONCURRENT_SENDS = 5 // 最大并发发送数
    private const val MAX_RETRY_ATTEMPTS = 3 // 最大重试次数
    private const val RETRY_DELAY_MS = 1000L // 重试延迟

    // 发送队列
    private val sendChannel = Channel<SendTask>(Channel.UNLIMITED)
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_SENDS)
    private val activeSends = AtomicInteger(0)

    init {
        // 启动队列处理协程
        CoroutineScope(Dispatchers.IO).launch {
            processSendQueue()
        }
    }

    private data class SendTask(
        val device: DeviceInfo,
        val data: String,
        val deviceManager: DeviceConnectionManager,
        val retryCount: Int = 0
    )

    /**
     * 处理发送队列
     */
    private suspend fun processSendQueue() {
        for (task in sendChannel) {
            sendSemaphore.acquire()
            activeSends.incrementAndGet()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    sendNotificationDataWithRetry(task)
                } finally {
                    sendSemaphore.release()
                    activeSends.decrementAndGet()
                }
            }
        }
    }

    /**
     * 带重试的通知数据发送
     */
    private suspend fun sendNotificationDataWithRetry(task: SendTask) {
        var currentTask = task
        var success = false

        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val auth = task.deviceManager.authenticatedDevices[task.device.uuid]
                if (auth == null || !auth.isAccepted) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "设备未认证，跳过发送: ${task.device.displayName}")
                    return
                }

                withTimeout(10000L) { // 10秒超时
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(task.device.ip, task.device.port), 5000)
                        val writer = java.io.OutputStreamWriter(socket.getOutputStream())
                        val encryptedData = task.deviceManager.encryptData(task.data, auth.sharedSecret)
                        val payload = "DATA_JSON:${task.deviceManager.uuid}:${task.deviceManager.localPublicKey}:${auth.sharedSecret}:${encryptedData}"
                        writer.write(payload + "\n")
                        writer.flush()
                        success = true
                        if (BuildConfig.DEBUG) Log.d(TAG, "通知发送成功到设备: ${task.device.displayName}")
                    } finally {
                        socket.close()
                    }
                }

                if (success) return

            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w(TAG, "发送失败 (尝试 ${attempt + 1}/${MAX_RETRY_ATTEMPTS}): ${task.device.displayName}, 错误: ${e.message}")

                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // 递增延迟
                }
            }
        }

        if (!success) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送最终失败，放弃重试: ${task.device.displayName}")
        }
    }

    /**
     * 发送聊天测试消息
     * @param context 上下文
     * @param message 消息内容
     * @param deviceManager 设备管理器
     */
    fun sendChatMessage(context: Context, message: String, deviceManager: DeviceConnectionManager) {
        try {
            // 获取所有已认证设备
            val allDevices = deviceManager.devices.value.values.map { it.first }
            val sentAny = allDevices.isNotEmpty() && message.isNotBlank()

            if (!sentAny) {
                if (BuildConfig.DEBUG) Log.w(TAG, "没有可用的设备或消息为空")
                return
            }

            // 构建标准 JSON 格式的消息
            val pkgName: String = context.packageName
            val json = JSONObject().apply {
                put("packageName", pkgName)
                put("appName", "NotifyRelay")
                put("title", "聊天测试")
                put("text", message)
                put("time", System.currentTimeMillis())
            }.toString()

            // 将发送任务加入队列
            allDevices.forEach { device ->
                val task = SendTask(device, json, deviceManager)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendChannel.send(task)
                        if (BuildConfig.DEBUG) Log.d(TAG, "聊天消息已加入发送队列: ${device.displayName}, 消息: $message")
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "加入发送队列失败: ${device.displayName}", e)
                    }
                }
            }

            // 记录到聊天历史
            ChatMemory.append(context, "发送: $message")

            if (BuildConfig.DEBUG) Log.i(TAG, "聊天消息已加入队列，共发送到 ${allDevices.size} 个设备，当前活跃发送: ${activeSends.get()}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送聊天消息失败", e)
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
        deviceManager: DeviceConnectionManager
    ) {
        try {
            // 获取所有已认证设备
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)

            if (authenticatedDevices.isEmpty()) {
                if (BuildConfig.DEBUG) Log.w(TAG, "没有已认证的设备")
                return
            }

            // 获取锁屏状态
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked

            // 构建标准 JSON 格式的通知数据
            val json = JSONObject().apply {
                put("packageName", packageName)
                put("appName", appName ?: packageName)
                put("title", title ?: "")
                put("text", text ?: "")
                put("time", time)
                put("isLocked", isLocked)
            }.toString()

            // 将发送任务加入队列
            authenticatedDevices.forEach { deviceInfo ->
                val task = SendTask(deviceInfo, json, deviceManager)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendChannel.send(task)
                        if (BuildConfig.DEBUG) Log.d(TAG, "通知已加入发送队列: ${deviceInfo.displayName}")
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "加入发送队列失败: ${deviceInfo.displayName}", e)
                    }
                }
            }

            if (BuildConfig.DEBUG) Log.i(TAG, "通知已加入队列，共 ${authenticatedDevices.size} 个设备，当前活跃发送: ${activeSends.get()}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送通知消息失败", e)
        }
    }

    /**
     * 发送高优先级悬浮通知（用于应用跳转指示）
     * @param context 上下文
     * @param title 通知标题
     * @param text 通知内容
     */
    fun sendHighPriorityNotification(context: Context, title: String?, text: String?) {
        try {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "notifyrelay_temp"

            // 创建通知渠道（如果不存在）
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = android.app.NotificationChannel(channelId, "跳转通知", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "应用内跳转指示通知"
                    enableLights(true)
                    lightColor = android.graphics.Color.BLUE
                    enableVibration(false)
                    setSound(null, null)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                    importance = android.app.NotificationManager.IMPORTANCE_HIGH
                    setBypassDnd(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 构建通知
            val builder = android.app.Notification.Builder(context, channelId).apply {
                setContentTitle(title ?: "(无标题)")
                setContentText(text ?: "(无内容)")
                setSmallIcon(android.R.drawable.ic_dialog_info)
                setCategory(android.app.Notification.CATEGORY_MESSAGE)
                setAutoCancel(true)
                setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                setOngoing(false)
            }

            // 发送通知，使用当前时间戳作为ID
            val notifyId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            notificationManager.notify(notifyId, builder.build())

            // 5秒后自动销毁通知
            android.os.Handler(context.mainLooper).postDelayed({
                notificationManager.cancel(notifyId)
            }, 5000)

            if (BuildConfig.DEBUG) Log.d(TAG, "高优先级悬浮通知已发送: $title")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "发送高优先级通知失败", e)
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

            authedMap?.forEach { (uuid, auth) ->
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
            if (BuildConfig.DEBUG) Log.e(TAG, "获取已认证设备列表失败", e)
            emptyList()
        }
    }

    /**
     * 检查是否有可用的设备
     * @param deviceManager 设备管理器
     * @return 是否有可用的设备
     */
    fun hasAvailableDevices(deviceManager: DeviceConnectionManager): Boolean {
        return deviceManager.devices.value.isNotEmpty()
    }

    /**
     * 检查消息是否有效
     * @param message 消息内容
     * @return 消息是否有效
     */
    fun isValidMessage(message: String?): Boolean {
        return !message.isNullOrBlank()
    }
}
