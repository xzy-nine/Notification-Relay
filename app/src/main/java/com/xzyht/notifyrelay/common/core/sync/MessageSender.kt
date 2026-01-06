package com.xzyht.notifyrelay.common.core.sync

import android.R
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.util.Base64
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 消息发送类
 * 整合聊天测试和普通通知转发的消息发送功能
 * 支持队列和限流，避免大量并发发送导致的通知丢失
 */
object MessageSender {

    private const val TAG = "MessageSender"
    private const val MAX_CONCURRENT_SENDS = 5 // 最大并发发送数
    private const val MAX_RETRY_ATTEMPTS = 3 // 最大重试次数
    private const val RETRY_DELAY_MS = 1000L // 重试延迟

    // 发送队列
    private val sendChannel = Channel<SendTask>(Channel.Factory.UNLIMITED)
    // 超级岛单独发送队列（不去重、持续发送）
    private val superIslandSendChannel = Channel<SuperIslandTask>(Channel.Factory.UNLIMITED)
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_SENDS)
    private val activeSends = AtomicInteger(0)
    // 超级岛发送并发控制（独立于普通通知）
    private val MAX_CONCURRENT_SUPERISLAND_SENDS = 3
    private val superSendSemaphore = Semaphore(MAX_CONCURRENT_SUPERISLAND_SENDS)
    private val activeSuperSends = AtomicInteger(0)
    // 去重集合：防止同一设备、同一数据在未完成前被重复入队
    private val pendingKeys = ConcurrentHashMap.newKeySet<String>()
    // 已发送记录（带 TTL），防止短时间内重复发送已成功发送的通知
    private const val SENT_KEY_TTL_MS = 10_000L // 10秒内视为已发送，避免重复
    private val sentKeys = ConcurrentHashMap<String, Long>()

    // 超级岛：为实现“首次全量，后续差异”，需要跟踪每个设备下每个feature的上次完整状态
    private val siLastStatePerDevice = mutableMapOf<String, MutableMap<String, SuperIslandProtocol.State>>()
    
    // 媒体播放：为实现“首次全量，后续差异”，需要跟踪每个设备下每个媒体源的上次完整状态
    private val mediaLastStatePerDevice = mutableMapOf<String, MutableMap<String, MediaPlayState>>()
    
    // 媒体播放状态数据类
    data class MediaPlayState(
        val title: String,
        val text: String,
        val packageName: String,
        val coverUrl: String? = null,
        val sentTime: Long = System.currentTimeMillis() // 添加发送时间戳
    )
    
    // 媒体播放差异数据类
    data class MediaPlayDiff(
        val title: String? = null,
        val text: String? = null,
        val coverUrl: String? = null
    ) {
        fun isEmpty(): Boolean = title == null && text == null && coverUrl == null
        
        fun toJson(): JSONObject = JSONObject().apply {
            if (title != null) put("title", title)
            if (text != null) put("text", text)
            if (coverUrl != null) put("coverUrl", coverUrl)
        }
    }
    /**
     * 计算媒体播放状态差异
     */
    private fun diffMediaPlay(old: MediaPlayState?, new: MediaPlayState): MediaPlayDiff {
        if (old == null) return MediaPlayDiff(
            title = new.title,
            text = new.text,
            coverUrl = new.coverUrl
        )
        var t: String? = null
        var c: String? = null
        var cover: String? = null
        if (old.title != new.title) t = new.title
        if (old.text != new.text) c = new.text
        if (old.coverUrl != new.coverUrl) cover = new.coverUrl
        return MediaPlayDiff(t, c, cover)
    }
    /**
     * 构建媒体播放全量包
     * 确保包含完整的当前状态信息
     */
    private fun buildMediaPlayFullPayload(
        packageName: String,
        appName: String?,
        time: Long,
        isLocked: Boolean,
        state: MediaPlayState
    ): JSONObject {
        return JSONObject().apply {
            put("packageName", packageName)
            put("appName", appName ?: packageName)
            // 全量包始终包含完整的title和text
            put("title", state.title)
            put("text", state.text)
            // 如果有封面URL，添加到payload中
            if (state.coverUrl != null) {
                put("coverUrl", state.coverUrl)
            }
            put("time", System.currentTimeMillis()) // 使用当前时间戳
            put("isLocked", isLocked)
            put("type", "MEDIA_PLAY")
            put("mediaType", "FULL") // 全量包
        }
    }
    
    /**
     * 构建媒体播放差异包
     */
    private fun buildMediaPlayDeltaPayload(
        packageName: String,
        appName: String?,
        time: Long,
        isLocked: Boolean,
        diff: MediaPlayDiff
    ): JSONObject {
        return JSONObject().apply {
            put("packageName", packageName)
            put("appName", appName ?: packageName)
            if (diff.title != null) put("title", diff.title)
            if (diff.text != null) put("text", diff.text)
            if (diff.coverUrl != null) put("coverUrl", diff.coverUrl)
            put("time", time)
            put("isLocked", isLocked)
            put("type", "MEDIA_PLAY")
            put("mediaType", "DELTA") // 差异包
        }
    }
    // 超级岛：ACK 跟踪与强制全量发送控制
    private const val SI_ACK_TIMEOUT_MS = 4_000L
    private data class PendingAck(val hash: String, val ts: Long)
    private val siPendingAcks = mutableMapOf<String, MutableMap<String, PendingAck>>() // deviceUuid -> featureId -> pending
    private val siForceFullNext = ConcurrentHashMap.newKeySet<String>() // key: deviceUuid|featureId

    init {
        // 启动队列处理协程
        CoroutineScope(Dispatchers.IO).launch {
            processSendQueue()
        }
        // 启动超级岛队列处理协程（独立）
        CoroutineScope(Dispatchers.IO).launch {
            processSuperIslandSendQueue()
        }
        // 定期清理已发送记录，避免内存无限增长
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val toRemove = sentKeys.entries.filter { now - it.value > SENT_KEY_TTL_MS }.map { it.key }
                    toRemove.forEach { sentKeys.remove(it) }
                } catch (_: Exception) {}
                delay(10_000L)
            }
        }
        // 超级岛：ACK 超时扫描，超时则标记下次强制全量
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val snapshot = synchronized(siPendingAcks) { siPendingAcks.mapValues { it.value.toMap() }.toMap() }
                    snapshot.forEach { (deviceUuid, featureMap) ->
                        featureMap.forEach { (featureId, pending) ->
                            if (now - pending.ts > SI_ACK_TIMEOUT_MS) {
                                val key = "$deviceUuid|$featureId"
                                siForceFullNext.add(key)
                                synchronized(siPendingAcks) { siPendingAcks[deviceUuid]?.remove(featureId) }
                                Logger.w("超级岛", "ACK超时：标记下次全量 device=$deviceUuid, feature=$featureId")
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(2_000L)
            }
        }
    }

    private data class SendTask(
        val device: DeviceInfo,
        val data: String,
        val deviceManager: DeviceConnectionManager,
        val retryCount: Int = 0,
        val dedupKey: String
    )

    // 超级岛发送任务（不使用去重键）
    private data class SuperIslandTask(
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
                    // 任务结束，移除去重键
                    pendingKeys.remove(task.dedupKey)
                    sendSemaphore.release()
                    activeSends.decrementAndGet()
                }
            }
        }
    }

    /**
     * 处理超级岛发送队列（独立于普通通知队列，不走去重逻辑）
     */
    private suspend fun processSuperIslandSendQueue() {
        for (task in superIslandSendChannel) {
            superSendSemaphore.acquire()
            activeSuperSends.incrementAndGet()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 改为即时发送一次，不进行重试（实时性优先）
                    sendSuperIslandDataOnce(task)
                } finally {
                    superSendSemaphore.release()
                    activeSuperSends.decrementAndGet()
                }
            }
        }
    }

    /**
     * 带重试的通知数据发送
     */
    private suspend fun sendNotificationDataWithRetry(task: SendTask) {
        var success = false

        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val auth = task.deviceManager.authenticatedDevices[task.device.uuid]
                if (auth == null || !auth.isAccepted) {
                    //Logger.d(TAG, "设备未认证，跳过发送: ${task.device.displayName}")
                    return
                }

                // 根据负载类型选择报文头（媒体播放使用 DATA_MEDIAPLAY，其它使用 DATA_NOTIFICATION）
                val header = try {
                    val obj = org.json.JSONObject(task.data)
                    if (obj.optString("type", "").equals("MEDIA_PLAY", true)) "DATA_MEDIAPLAY" else "DATA_NOTIFICATION"
                } catch (_: Exception) { "DATA_NOTIFICATION" }
                ProtocolSender.sendEncrypted(task.deviceManager, task.device, header, task.data, 10000L)
                success = true
                try { sentKeys[task.dedupKey] = System.currentTimeMillis() } catch (_: Exception) {}
                //Logger.d(TAG, "通知发送成功到设备: ${task.device.displayName}, data: ${task.data}")

                if (success) return

            } catch (e: Exception) {
                Logger.w(TAG, "发送失败 (尝试 ${attempt + 1}/${MAX_RETRY_ATTEMPTS}): ${task.device.displayName}, 错误: ${e.message}")

                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // 递增延迟
                }
            }
        }

        if (!success) {
            Logger.e(TAG, "发送最终失败，放弃重试: ${task.device.displayName}")
        }
    }

    /**
     * 超级岛数据发送（带重试），不会更新去重表或使用去重键，保证尽可能持续发送
     */
    private suspend fun sendSuperIslandDataWithRetry(task: SuperIslandTask) {
        var success = false

        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val auth = task.deviceManager.authenticatedDevices[task.device.uuid]
                if (auth == null || !auth.isAccepted) {
                    //Logger.d("超级岛", "超级岛: 设备未认证，跳过发送: ${task.device.displayName}")
                    return
                }

                ProtocolSender.sendEncrypted(task.deviceManager, task.device, "DATA_SUPERISLAND", task.data, 10000L)
                success = true
                //Logger.d("超级岛", "超级岛: 发送成功到设备: ${task.device.displayName}")

                if (success) return

            } catch (e: Exception) {
                Logger.w("超级岛", "超级岛: 发送失败 (尝试 ${attempt + 1}/${MAX_RETRY_ATTEMPTS}): ${task.device.displayName}, 错误: ${e.message}")

                if (attempt < MAX_RETRY_ATTEMPTS - 1) {
                    delay(RETRY_DELAY_MS * (attempt + 1)) // 递增延迟
                }
            }
        }

        if (!success) {
            Logger.e("超级岛", "超级岛: 发送最终失败，放弃重试: ${task.device.displayName}")
        }
    }

    /**
     * 超级岛即时发送（不重试）。实时性优先：尝试一次发送，遇到错误记录日志后返回。
     */
    private suspend fun sendSuperIslandDataOnce(task: SuperIslandTask) {
        try {
            val auth = task.deviceManager.authenticatedDevices[task.device.uuid]
            if (auth == null || !auth.isAccepted) {
                //Logger.d("超级岛", "超级岛: 设备未认证，跳过发送: ${task.device.displayName}")
                return
            }

            ProtocolSender.sendEncrypted(task.deviceManager, task.device, "DATA_SUPERISLAND", task.data, 10000L)
            //Logger.d("超级岛", "超级岛: 发送成功到设备: ${task.device.displayName}")
        } catch (e: Exception) {
            Logger.w("超级岛", "超级岛: 实时发送失败: ${task.device.displayName}, 错误: ${e.message}")
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
                Logger.w(TAG, "没有可用的设备或消息为空")
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

            // 将发送任务加入队列（带去重）
            allDevices.forEach { device ->
                val dedupKey = buildDedupKey(device.uuid, json)
                // 检查最近是否已发送过相同消息，避免短时间内重复
                val lastSent = sentKeys[dedupKey]
                if (lastSent != null && System.currentTimeMillis() - lastSent <= SENT_KEY_TTL_MS) {
                    //Logger.d(TAG, "跳过已发送的重复聊天消息(短期内): ${device.displayName}")
                    return@forEach
                }
                if (!pendingKeys.add(dedupKey)) {
                    //Logger.d(TAG, "跳过重复聊天消息入队: ${device.displayName}")
                    return@forEach
                }
                val task = SendTask(device, json, deviceManager, dedupKey = dedupKey)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendChannel.send(task)
                        //Logger.d(TAG, "聊天消息已加入发送队列: ${device.displayName}, 消息: $message")
                    } catch (e: Exception) {
                        // 入队失败时及时移除去重键
                        pendingKeys.remove(dedupKey)
                        Logger.e(TAG, "加入发送队列失败: ${device.displayName}", e)
                    }
                }
            }

            // 记录到聊天历史
            ChatMemory.append(context, "发送: $message")

            Logger.i(TAG, "聊天消息已加入队列，共发送到 ${allDevices.size} 个设备，当前活跃发送: ${activeSends.get()}")
        } catch (e: Exception) {
            Logger.e(TAG, "发送聊天消息失败", e)
        }
    }

    /**
     * 发送媒体播放通知
     * 使用专门的协议前缀标记媒体通知，支持状态变化跟踪
     * 保持差异发送，仅在封面发生变化时发送包含封面的包，否则仅发送文本部分
     */
    fun sendMediaPlayNotification(
        context: Context,
        packageName: String, // 应为实际应用包名，通过 payload 中的 type=MEDIA_PLAY 区分媒体消息
        appName: String?,
        title: String?,
        text: String?,
        coverUrl: String?,
        time: Long,
        deviceManager: DeviceConnectionManager
    ) {
        try {
            // 获取所有已认证设备
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)

            if (authenticatedDevices.isEmpty()) {
                Logger.w(TAG, "没有已认证的设备")
                return
            }

            // 获取锁屏状态
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked
            
            // 创建当前媒体播放状态
            val currentState = MediaPlayState(
                title = title ?: "",
                text = text ?: "",
                packageName = packageName, // 传入实际包名（不含前缀）
                coverUrl = coverUrl
            )
            
            // 全局只会存在一个媒体会话，使用固定的媒体源ID
            val mediaSourceId = "global_media_session"

            // 将发送任务加入队列（带去重）
            authenticatedDevices.forEach { deviceInfo ->
                // 读取该设备下该媒体源的上次状态
                val deviceMap = synchronized(mediaLastStatePerDevice) {
                    mediaLastStatePerDevice.getOrPut(deviceInfo.uuid) { mutableMapOf() }
                }
                val lastState = synchronized(mediaLastStatePerDevice) { deviceMap[mediaSourceId] }
                
                // 计算差异
                val diff = diffMediaPlay(lastState, currentState)
                
                // 判断是否需要发送全量包：首次发送、封面变化、或超过15秒
                val now = System.currentTimeMillis()
                val needFullPayload = lastState == null || 
                                      diff.coverUrl != null ||
                                      (lastState != null && now - lastState.sentTime > 15 * 1000)
                
                // 构建发送数据
                val payloadObj = if (needFullPayload) {
                    // 首次发送、封面变化或超时，发送包含当前完整状态的全量包
                    buildMediaPlayFullPayload(
                        packageName,
                        appName,
                        time,
                        isLocked,
                        currentState
                    )
                } else {
                    // 仅文本变化，发送差异包（仅文本部分）
                    buildMediaPlayDeltaPayload(
                        packageName,
                        appName,
                        time,
                        isLocked,
                        diff.copy(coverUrl = null) // 确保差异包不包含封面
                    )
                }
                
                val json = payloadObj.toString()
                
                // 立即更新本地lastState（用于后续差异计算），包含当前时间戳
                val updatedState = currentState.copy(sentTime = System.currentTimeMillis())
                synchronized(mediaLastStatePerDevice) { deviceMap[mediaSourceId] = updatedState }
                
                val dedupKey = buildDedupKey(deviceInfo.uuid, json)
                // 检查是否正在等待发送或最近已发送过
                val lastSent = sentKeys[dedupKey]
                if (lastSent != null && System.currentTimeMillis() - lastSent <= SENT_KEY_TTL_MS) {
                    //Logger.d(TAG, "跳过已发送的重复媒体播放通知(短期内): ${deviceInfo.displayName}")
                    return@forEach
                }
                if (!pendingKeys.add(dedupKey)) {
                    //Logger.d(TAG, "跳过重复媒体播放通知入队: ${deviceInfo.displayName}")
                    return@forEach
                }
                val task = SendTask(deviceInfo, json, deviceManager, dedupKey = dedupKey)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendChannel.send(task)
                        //Logger.d(TAG, "媒体播放通知已加入发送队列: ${deviceInfo.displayName}, type=${if (lastState == null || diff.coverUrl != null) "FULL_WITH_COVER" else "DELTA_TEXT_ONLY"}")
                    } catch (e: Exception) {
                        // 入队失败时及时移除去重键
                        pendingKeys.remove(dedupKey)
                        Logger.e(TAG, "加入发送队列失败: ${deviceInfo.displayName}", e)
                    }
                }
            }

            //Logger.i(TAG, "媒体播放通知已加入队列，共 ${authenticatedDevices.size} 个设备，当前活跃发送: ${activeSends.get()}")
        } catch (e: Exception) {
            Logger.e(TAG, "发送媒体播放通知失败", e)
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
                Logger.w(TAG, "没有已认证的设备")
                return
            }

            // 获取锁屏状态
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
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

            // 将发送任务加入队列（带去重）
            authenticatedDevices.forEach { deviceInfo ->
                val dedupKey = buildDedupKey(deviceInfo.uuid, json)
                // 检查是否正在等待发送或最近已发送过
                val lastSent = sentKeys[dedupKey]
                if (lastSent != null && System.currentTimeMillis() - lastSent <= SENT_KEY_TTL_MS) {
                    //Logger.d(TAG, "跳过已发送的重复通知(短期内): ${deviceInfo.displayName}")
                    return@forEach
                }
                if (!pendingKeys.add(dedupKey)) {
                    //Logger.d(TAG, "跳过重复通知入队: ${deviceInfo.displayName}")
                    return@forEach
                }
                val task = SendTask(deviceInfo, json, deviceManager, dedupKey = dedupKey)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        sendChannel.send(task)
                        //Logger.d(TAG, "通知已加入发送队列: ${deviceInfo.displayName}")
                    } catch (e: Exception) {
                        // 入队失败时及时移除去重键
                        pendingKeys.remove(dedupKey)
                        Logger.e(TAG, "加入发送队列失败: ${deviceInfo.displayName}", e)
                    }
                }
            }

            Logger.i(TAG, "通知已加入队列，共 ${authenticatedDevices.size} 个设备，当前活跃发送: ${activeSends.get()}")
        } catch (e: Exception) {
            Logger.e(TAG, "发送通知消息失败", e)
        }
    }

    /**
     * 发送超级岛专用数据（包含 param_v2 原始 JSON 与图片 map）
     */
    fun sendSuperIslandData(
        context: Context,
        superPkg: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
        paramV2Raw: String?,
        picMap: Map<String, String>?,
        deviceManager: DeviceConnectionManager,
        featureIdOverride: String? = null
    ) {
        try {
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)
            if (authenticatedDevices.isEmpty()) {
                Logger.w(TAG, "没有已认证的设备")
                return
            }

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked

            // 处理图片：若 picMap 中是本地 URI/file 路径则读取并编码为 base64 data URI，http(s) 地址或其他字符串保持不变
            val processedPics = mutableMapOf<String, String>()
            if (picMap != null) {
                // 在 IO 线程同步读取后再继续（sendSuperIslandData 本身是同步接口）
                runBlocking(Dispatchers.IO) {
                    picMap.forEach { (k, v) ->
                        try {
                            val lower = v.lowercase()
                            if (lower.startsWith("content://") || lower.startsWith("file://") || v.startsWith(
                                    "/"
                                )
                            ) {
                                try {
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
                                } catch (e: Exception) {
                                    // 读取失败则保留原值
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

            // 计算特征键（支持外部传入首包固定ID，避免后续波动）
            val featureId = featureIdOverride ?: SuperIslandProtocol.computeFeatureId(
                superPkg, paramV2Raw, title, text
            )

            val finalPics: Map<String, String> = if (processedPics.isNotEmpty()) processedPics.toMap() else (picMap?.toMap() ?: emptyMap())
            val newState = SuperIslandProtocol.State(
                title = title,
                text = text,
                paramV2Raw = paramV2Raw,
                pics = finalPics
            )

            // 将超级岛发送任务加入独立队列（不去重，实时性优先）
            authenticatedDevices.forEach { deviceInfo ->
                // 读取该设备下该feature的上次状态
                val deviceMap = synchronized(siLastStatePerDevice) {
                    siLastStatePerDevice.getOrPut(deviceInfo.uuid) { mutableMapOf() }
                }
                val old = synchronized(siLastStatePerDevice) { deviceMap[featureId] }

                val forceFull = siForceFullNext.contains("${deviceInfo.uuid}|$featureId")

                val payloadObj = if (old == null || forceFull) {
                    // 首包全量
                    SuperIslandProtocol.buildFullPayload(
                        superPkg, appName, time, isLocked, featureId, newState
                    )
                } else {
                    // 差异包：即便无变化也发送空变更包，以刷新接收端的待撤回悬浮窗
                    val d = SuperIslandProtocol.diff(old, newState)
                    SuperIslandProtocol.buildDeltaPayload(
                        superPkg, appName, time, isLocked, featureId, d
                    )
                }

                // 立即更新本地lastState（简单模式：不等待ACK再更新，用于后续差异计算）
                synchronized(siLastStatePerDevice) { deviceMap[featureId] = newState }
                // 记录待ACK哈希
                try {
                    val h = payloadObj.optString("hash", "")
                    if (h.isNotEmpty()) {
                        val map = synchronized(siPendingAcks) { siPendingAcks.getOrPut(deviceInfo.uuid) { mutableMapOf() } }
                        synchronized(siPendingAcks) { map[featureId] = PendingAck(h, System.currentTimeMillis()) }
                    }
                } catch (_: Exception) {}
                val task = SuperIslandTask(deviceInfo, payloadObj.toString(), deviceManager)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        superIslandSendChannel.send(task)
                        //Logger.d("超级岛", "超级岛: 数据已加入超级岛发送队列：${deviceInfo.displayName}")
                    } catch (e: Exception) {
                        Logger.e("超级岛", "超级岛: 加入超级岛发送队列失败：${deviceInfo.displayName}", e)
                    }
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
        featureIdOverride: String? = null
    ) {
        try {
            val authenticatedDevices = getAuthenticatedDevices(deviceManager)
            if (authenticatedDevices.isEmpty()) return
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked
            val featureId = featureIdOverride ?: SuperIslandProtocol.computeFeatureId(
                superPkg, paramV2Raw, title, text
            )
            val payload = SuperIslandProtocol.buildEndPayload(
                superPkg, appName, time, isLocked, featureId
            ).toString()
            authenticatedDevices.forEach { deviceInfo ->
                // 清理该设备的lastState
                synchronized(siLastStatePerDevice) {
                    siLastStatePerDevice[deviceInfo.uuid]?.remove(featureId)
                }
                val task = SuperIslandTask(deviceInfo, payload, deviceManager)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        superIslandSendChannel.send(task)
                        //Logger.d("超级岛", "超级岛: 终止数据已加入发送队列：${deviceInfo.displayName}")
                    } catch (e: Exception) {
                        Logger.e("超级岛", "超级岛: 终止数据入队失败：${deviceInfo.displayName}", e)
                    }
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
    fun sendHighPriorityNotification(context: Context, title: String?, text: String?) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "notifyrelay_temp"

            // 创建通知渠道（如果不存在）
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, "跳转通知", NotificationManager.IMPORTANCE_HIGH).apply {
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
            val builder = Notification.Builder(context, channelId).apply {
                setContentTitle(title ?: "(无标题)")
                setContentText(text ?: "(无内容)")
                setSmallIcon(R.drawable.ic_dialog_info)
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

            //Logger.d(TAG, "高优先级悬浮通知已发送: $title")
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
    fun hasAvailableDevices(deviceManager: DeviceConnectionManager): Boolean {
        return deviceManager.devices.value.isNotEmpty()
    }

    // 接收端ACK回调：当收到对方SI_ACK时调用，确认hash送达，清理待ACK并解除强制全量
    fun onSuperIslandAck(deviceUuid: String, featureId: String?, hash: String?) {
        try {
            if (featureId.isNullOrEmpty() || hash.isNullOrEmpty()) return
            val pending = synchronized(siPendingAcks) { siPendingAcks[deviceUuid]?.get(featureId) }
            if (pending != null && pending.hash == hash) {
                synchronized(siPendingAcks) { siPendingAcks[deviceUuid]?.remove(featureId) }
                val key = "$deviceUuid|$featureId"
                siForceFullNext.remove(key)
                //Logger.d("超级岛", "ACK匹配成功：device=$deviceUuid, feature=$featureId")
            } else {
                val key = "$deviceUuid|$featureId"
                siForceFullNext.add(key)
                Logger.w("超级岛", "ACK哈希不匹配或无待确认：标记下次全量 device=$deviceUuid, feature=$featureId, ackHash=$hash")
            }
        } catch (_: Exception) {}
    }

    /**
     * 检查消息是否有效
     * @param message 消息内容
     * @return 消息是否有效
     */
    fun isValidMessage(message: String?): Boolean {
        return !message.isNullOrBlank()
    }

    // 根据设备UUID与数据内容构建去重键（SHA-256）
    private fun buildDedupKey(deviceUuid: String, data: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((deviceUuid + "|" + data).toByteArray())
        return digest.joinToString(separator = "") { byte ->
            val v = (byte.toInt() and 0xFF)
            val s = v.toString(16)
            if (s.length == 1) "0$s" else s
        }
    }
}