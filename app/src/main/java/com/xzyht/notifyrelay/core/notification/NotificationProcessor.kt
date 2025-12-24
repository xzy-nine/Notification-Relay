package com.xzyht.notifyrelay.core.notification

import android.content.Context
import android.util.Log
import com.xzyht.notifyrelay.BuildConfig
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.notification.data.ChatMemory
import com.xzyht.notifyrelay.feature.device.model.NotificationRepository
import com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandHistory
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandHistoryEntry
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandProtocol
import com.xzyht.notifyrelay.feature.notification.superisland.SuperIslandRemoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 远程通知处理管线（单条通知级别，不负责网络收发）。
 *
 * 职责：
 * - 解密（如果提供 sharedSecret，则先解密再处理）
 * - 解析 JSON、处理超级岛协议（增量合并 + 悬浮窗 + 历史）
 * - 将结果写入 NotificationRepository
 * - 交给远程过滤器 remoteNotificationFilter，执行复刻/去重/延迟锁屏显示等逻辑
 * - 通知 UI 回调（原 notificationDataReceivedCallbacks）
 */
object NotificationProcessor {

    private const val TAG = "NotificationProcessor"
    
    // 超级岛通知去重缓存，用于处理锁屏时的重复通知
    // 键: 去重键（remoteUuid|mappedPkg|featureId）
    private val superIslandDeduplicationCache = mutableSetOf<String>()

    data class NotificationInput(
        val rawData: String,
        val sharedSecret: String?,
        val remoteUuid: String?,
    )

    /**
     * 处理一条来自远端的通知数据。
     * @param scope 用于内部启动延迟任务（锁屏延迟复刻等），通常传入 DeviceConnectionManager 的 coroutineScope
     */
    fun process(
        context: Context,
        manager: DeviceConnectionManager,
        scope: CoroutineScope,
        input: NotificationInput,
        notificationCallbacks: Collection<(String) -> Unit>
    ) {
        val (data, sharedSecret, remoteUuid) = input

        // 1. 解密
        val decrypted = if (sharedSecret != null) {
            try {
                manager.decryptDataInternal(data, sharedSecret)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "解密失败: ${e.message}")
                data
            }
        } else data

        if (BuildConfig.DEBUG) Log.d(TAG, "处理通知数据: $decrypted")

        // 2. JSON 级别处理：超级岛协议 + NotificationRepository 写入
        handleJsonLevel(context, manager, decrypted, sharedSecret, remoteUuid)

        // 3. 过滤与复刻
        handleFilterAndReplicate(context, manager, scope, decrypted, remoteUuid)

        // 4. 通知 UI 回调
        notificationCallbacks.forEach { callback ->
            try {
                if (BuildConfig.DEBUG) Log.d(TAG, "调用UI层回调: $callback")
                callback.invoke(decrypted)
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(TAG, "调用UI层回调失败: ${e.message}")
            }
        }
    }

    private fun handleJsonLevel(
        context: Context,
        manager: DeviceConnectionManager,
        decrypted: String,
        sharedSecret: String?,
        remoteUuid: String?
    ) {
        try {
            if (remoteUuid != null) {
                val json = org.json.JSONObject(decrypted)
                val pkg = json.optString("packageName")
                val appName = json.optString("appName")
                val title = json.optString("title")
                val text = json.optString("text")
                val time = json.optLong("time", System.currentTimeMillis())

                // 超级岛优先分支
                if (handleSuperIslandIfNeeded(context, manager, json, decrypted, pkg, appName, title, text, time, sharedSecret, remoteUuid)) {
                    return
                }

                val installedPkgs = com.xzyht.notifyrelay.core.repository.AppRepository.getInstalledPackageNamesSync(context)
                val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(pkg.orEmpty(), installedPkgs)

                try {
                    NotificationRepository.addRemoteNotification(mappedPkg, appName, title, text, time, remoteUuid, context)
                    NotificationRepository.scanDeviceList(context)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(TAG, "存储远程通知失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "handleJsonLevel异常: ${e.message}")
        }
    }

    /**
     * 如果是超级岛通知，则优先处理差异协议/旧协议，并在完成后返回 true。
     */
    private fun handleSuperIslandIfNeeded(
        context: Context,
        manager: DeviceConnectionManager,
        json: org.json.JSONObject,
        decrypted: String,
        pkg: String?,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
        sharedSecret: String?,
        remoteUuid: String
    ): Boolean {
        return try {
            val installedPkgs = com.xzyht.notifyrelay.core.repository.AppRepository.getInstalledPackageNamesSync(context)
            val mappedPkg = com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.mapToLocalPackage(pkg.orEmpty(), installedPkgs)

            val isSuper = (!mappedPkg.isNullOrEmpty() && mappedPkg.startsWith("superisland:")) || (pkg?.startsWith("superisland:") == true)
            if (!isSuper) return false

            val siType = try { json.optString("type", "") } catch (_: Exception) { "" }
            val hasFeature = try { json.has("featureKeyName") && json.has("featureKeyValue") } catch (_: Exception) { false }

            // SI_ACK 属于超岛协议的确认包，仅用于可靠性确认，不应进入通知/聊天管线
            if (siType == "SI_ACK") {
                if (BuildConfig.DEBUG) Log.i("超级岛", "收到超级岛ACK: remoteUuid=$remoteUuid, pkg=$pkg, mappedPkg=$mappedPkg, hash=${try { json.optString("hash", "") } catch (_: Exception) { "" }}")
                return true
            }

            if (siType.startsWith("SI_") || hasFeature) {
                // 获取发送设备的锁屏状态
                val isLocked = try { json.optBoolean("isLocked", false) } catch (_: Exception) { false }
                
                // 新协议：差异合并
                val featureId = try { json.optString("featureKeyValue", "") } catch (_: Exception) { "" }
                val sourceKey = listOfNotNull(remoteUuid, mappedPkg, featureId.takeIf { it.isNotBlank() }).joinToString("|")
                
                // 构建去重键
                val dedupKey = "${remoteUuid}|${mappedPkg}|${featureId}"
                
                if (siType == SuperIslandProtocol.TYPE_END) {
                    try { FloatingReplicaManager.dismissBySource(sourceKey) } catch (_: Exception) {}
                    // 移除去重缓存
                    superIslandDeduplicationCache.remove(dedupKey)
                    if (BuildConfig.DEBUG) Log.i("超级岛", "收到终止通知，移除去重缓存: $dedupKey")
                    return true
                }
                
                // 提前获取通知标题和内容，用于更详细的日志记录
                val mTitle = try { json.optString("title", title) } catch (_: Exception) { title }
                val mText = try { json.optString("text", text) } catch (_: Exception) { text }
                
                // 锁屏状态下的超级岛通知去重处理
                if (isLocked) {
                    // 检查是否已经处理过相同的超级岛通知
                    if (superIslandDeduplicationCache.contains(dedupKey)) {
                        if (BuildConfig.DEBUG) Log.i("超级岛", "锁屏重复通知去重: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                        return true // 跳过重复通知
                    } else {
                        // 立即添加到去重缓存，防止同一时间点的重复通知
                        superIslandDeduplicationCache.add(dedupKey)
                        if (BuildConfig.DEBUG) Log.i("超级岛", "首次处理超级岛通知，添加到去重缓存: $dedupKey, title=${mTitle ?: "无标题"}")
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.i("超级岛", "非锁屏状态，正常处理超级岛通知: sourceKey=$sourceKey, title=${mTitle ?: "无标题"}")
                }
                
                val merged = SuperIslandRemoteStore.applyIncoming(sourceKey, json)

                // 发送 ACK
                val recvHash = try { json.optString("hash", "") } catch (_: Exception) { "" }
                if (!recvHash.isNullOrEmpty()) {
                    try { manager.sendSuperIslandAckInternal(remoteUuid, sharedSecret, recvHash, featureId, mappedPkg) } catch (_: Exception) {}
                }

                if (merged != null) {
                    val finalTitle = merged.title ?: mTitle
                    val finalText = merged.text ?: mText
                    val mParam2 = merged.paramV2Raw
                    val mPics = merged.pics
                    
                    try {
                        FloatingReplicaManager.showFloating(context, sourceKey, finalTitle, finalText, mParam2, mPics, appName)
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.w("超级岛", "差异复刻悬浮窗失败: ${e.message}")
                    }
                    
                    val historyEntry = SuperIslandHistoryEntry(
                        id = System.currentTimeMillis(),
                        sourceDeviceUuid = remoteUuid,
                        originalPackage = pkg,
                        mappedPackage = mappedPkg,
                        appName = appName?.takeIf { it.isNotEmpty() },
                        title = finalTitle?.takeIf { it.isNotBlank() },
                        text = finalText?.takeIf { it.isNotBlank() },
                        paramV2Raw = mParam2?.takeIf { it.isNotBlank() },
                        picMap = mPics.toMap(),
                        rawPayload = decrypted,
                        featureId = featureId // 传递特征ID，用于去重
                    )
                    
                    try {
                        SuperIslandHistory.append(context, historyEntry)
                    } catch (_: Exception) {
                        SuperIslandHistory.append(
                            context,
                            SuperIslandHistoryEntry(
                                id = System.currentTimeMillis(),
                                sourceDeviceUuid = remoteUuid,
                                originalPackage = pkg,
                                mappedPackage = mappedPkg,
                                rawPayload = decrypted,
                                featureId = featureId // 传递特征ID，用于去重
                            )
                        )
                    }
                    
                    return true
                } else {
                    // 如果合并失败，移除去重缓存，允许后续重试
                    if (isLocked) {
                        superIslandDeduplicationCache.remove(dedupKey)
                        if (BuildConfig.DEBUG) Log.i("超级岛", "合并失败，移除去重缓存: $dedupKey")
                    }
                    return true
                }
            } else {
                return false
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun handleFilterAndReplicate(
        context: Context,
        manager: DeviceConnectionManager,
        scope: CoroutineScope,
        decrypted: String,
        remoteUuid: String?
    ) {
        if (BuildConfig.DEBUG) Log.d(TAG, "准备调用远程过滤器")

        val result = com.xzyht.notifyrelay.feature.device.repository.remoteNotificationFilter(decrypted, context)
        if (BuildConfig.DEBUG) Log.d(TAG, "remoteNotificationFilter result: $result")
        try {
            if (!result.mappedPkg.isNullOrEmpty() && result.mappedPkg.startsWith("superisland:")) {
                if (BuildConfig.DEBUG) Log.i("超级岛", "remoteNotificationFilter 判定为超级岛: mappedPkg=${result.mappedPkg}, needsDelay=${result.needsDelay}, title=${result.title}")
            }
        } catch (_: Exception) {}

        if (result.shouldShow) {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            val localIsLocked = keyguardManager.isKeyguardLocked
            if (BuildConfig.DEBUG) Log.d("智能去重", "锁屏分支检查: shouldShow=${result.shouldShow}, needsDelay=${result.needsDelay}, localIsLocked=${localIsLocked}, 标题:${result.title}")

            if (result.needsDelay && localIsLocked) {
                handleLockedScreenDelayed(context, scope, result)
                ChatMemory.append(context, "收到: ${result.rawData}")
            } else {
                // 在非协程环境中调用挂起函数，使用 scope 启动协程
                scope.launch {
                    com.xzyht.notifyrelay.feature.device.repository.replicateNotification(context, result, null, startMonitoring = true)
                }

                if (remoteUuid != null) {
                    try {
                        val sourceDevice = manager.getDeviceInfoInternal(remoteUuid)
                        if (sourceDevice != null) {
                            com.xzyht.notifyrelay.core.sync.IconSyncManager.checkAndSyncIcon(
                                context,
                                result.mappedPkg,
                                manager,
                                sourceDevice
                            )
                        }
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(TAG, "图标同步检查失败", e)
                    }
                }
            }
        } else {
            val isSuperIsland = try {
                !result.mappedPkg.isNullOrEmpty() && result.mappedPkg.startsWith("superisland:")
            } catch (_: Exception) { false }
            if (!isSuperIsland) {
                ChatMemory.append(context, "收到: ${result.rawData}")
            }
        }
    }

    private fun handleLockedScreenDelayed(
        context: Context,
        scope: CoroutineScope,
        result: com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.FilterResult
    ) {
        if (BuildConfig.DEBUG) Log.d("智能去重", "本机锁屏：延迟复刻，等待监控期后再检查重复再复刻 - 标题:${result.title}")
        try {
            if (com.xzyht.notifyrelay.feature.notification.backend.RemoteFilterConfig.enableDeduplication) {
                com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.addPlaceholder(result.title, result.text, result.mappedPkg, 15_000L)
            }
        } catch (_: Exception) {}

        scope.launch {
            try {
                val waitMs = 15_000L
                if (BuildConfig.DEBUG) Log.d("智能去重", "锁屏延迟复刻等待 ${waitMs}ms - 标题:${result.title}")
                delay(waitMs)

                val localList = com.xzyht.notifyrelay.feature.device.model.NotificationRepository.getNotificationsByDevice("本机")
                fun normalizeTitleLocal(t: String?): String {
                    if (t == null) return ""
                    val prefixPattern = Regex("^\\([^)]+\\)")
                    return t.replace(prefixPattern, "").trim()
                }

                val normalizedPendingTitle = normalizeTitleLocal(result.title)
                val pendingText = result.text
                val duplicateFound = localList.any { nr ->
                    try {
                        nr.device == "本机" && normalizeTitleLocal(nr.title) == normalizedPendingTitle && (nr.text ?: "") == (pendingText ?: "")
                    } catch (_: Exception) { false }
                }

                if (!duplicateFound) {
                    val placeholderStillExists = try {
                        com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.isPlaceholderPresent(result.title, result.text, result.mappedPkg)
                    } catch (e: Exception) { true }

                    if (!placeholderStillExists) {
                        if (BuildConfig.DEBUG) Log.d("智能去重", "锁屏延迟复刻：占位已被取消，跳过复刻 - 标题:${result.title}")
                    } else {
                        if (BuildConfig.DEBUG) Log.d("智能去重", "锁屏延迟复刻：超期无重复，进行复刻 - 标题:${result.title}")
                        try {
                            com.xzyht.notifyrelay.feature.device.repository.replicateNotification(context, result, null, startMonitoring = false)
                        } catch (e: Exception) {
                            if (BuildConfig.DEBUG) Log.e("智能去重", "锁屏延迟复刻执行复刻时发生错误", e)
                        } finally {
                            try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
                        }
                    }
                } else {
                    try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
                    if (BuildConfig.DEBUG) Log.d("智能去重", "锁屏延迟复刻：发现重复，跳过复刻 - 标题:${result.title}")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("智能去重", "锁屏延迟复刻异常", e)
                try { com.xzyht.notifyrelay.feature.notification.backend.BackendRemoteFilter.removePlaceholderMatching(result.title, result.text, result.mappedPkg) } catch (_: Exception) {}
            }
        }
    }
}
