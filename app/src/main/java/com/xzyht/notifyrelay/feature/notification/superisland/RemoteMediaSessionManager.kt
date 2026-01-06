package com.xzyht.notifyrelay.feature.notification.superisland

import android.content.Context
import com.xzyht.notifyrelay.common.core.sync.ProtocolSender
import com.xzyht.notifyrelay.common.core.util.Logger
import com.xzyht.notifyrelay.common.data.StorageManager
import com.xzyht.notifyrelay.common.data.StorageManager.getBoolean
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.service.DeviceInfo
import com.xzyht.notifyrelay.feature.notification.superisland.core.SuperIslandProtocol
import com.xzyht.notifyrelay.feature.notification.superisland.floating.BigIsland.model.MediaSessionData
import org.json.JSONObject

object RemoteMediaSessionManager {
    private const val KEY_ENABLED = "remote_media_island_enabled"
    private const val DEFAULT_ENABLED = true

    private var currentSession: MediaSessionData? = null
    private var currentDevice: DeviceInfo? = null

    private var isEnabled: Boolean = true

    // 固定sourceKey前缀，以设备为单位
    private const val SOURCE_KEY_PREFIX = "media_island"

    // 媒体会话特征ID缓存，用于sourceId计算
    private val mediaFeatureIdCache = mutableMapOf<String, String>()
    
    // 媒体会话最后更新时间缓存
    private val mediaLastUpdateTime = mutableMapOf<String, Long>()
    
    // 媒体会话数据缓存，用于定时复传
    private val mediaSessionCache = mutableMapOf<String, MediaSessionCacheData>()
    
    // 超时时间（毫秒），与发送端超时发送时间匹配（15秒）
    private const val MEDIA_SESSION_TIMEOUT_MS = 15 * 1000L
    
    // 定时复传间隔（毫秒），设置为6秒，确保在12秒自动关闭前更新两次
    private const val MEDIA_SESSION_RESEND_INTERVAL_MS = 6 * 1000L
    
    // 用于处理延迟任务的Handler
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    // 媒体会话缓存数据类
    private data class MediaSessionCacheData(
        val context: android.content.Context,
        val session: MediaSessionData,
        val device: DeviceInfo,
        val resendRunnable: Runnable
    )

    fun init(context: Context) {
        isEnabled = try {
            getBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)
        } catch (_: Exception) {
            DEFAULT_ENABLED
        }
        Logger.i("RemoteMediaSessionManager", "远端媒体超级岛功能已${if (isEnabled) "启用" else "禁用"}")
    }

    fun isEnabled(context: Context): Boolean {
        return try {
            getBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)
        } catch (_: Exception) {
            DEFAULT_ENABLED
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            StorageManager.putBoolean(context, KEY_ENABLED, enabled)
            isEnabled = enabled
            if (!enabled) {
                clearSession()
            }
            Logger.i("RemoteMediaSessionManager", "远端媒体超级岛功能已${if (enabled) "启用" else "禁用"}")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "设置远端媒体超级岛开关失败", e)
        }
    }

    fun onMediaMessageReceived(
        context: Context,
        json: JSONObject,
        device: DeviceInfo
    ) {
        if (!isEnabled(context)) {
            Logger.d("RemoteMediaSessionManager", "远端媒体超级岛功能未启用，忽略消息")
            return
        }

        try {
            val packageName = json.optString("packageName", "")
            val appName = json.optString("appName", "")
            val title = json.optString("title", "")
            val text = json.optString("text", "")
            val coverUrl = json.optString("coverUrl", "")
            val timestamp = json.optLong("time", System.currentTimeMillis())

            // 即使标题和文本为空，也继续处理，保持浮窗活跃
            if (title.isBlank() && text.isBlank()) {
                Logger.w("RemoteMediaSessionManager", "收到空的媒体会话数据，继续处理以保持浮窗活跃")
            }

            // 保留旧的会话数据
            val oldSession = mediaSessionCache[device.uuid]?.session
            val finalTitle = title.ifBlank { oldSession?.title ?: "" }
            val finalText = text.ifBlank { oldSession?.text ?: "" }
            val finalCoverUrl = coverUrl ?: oldSession?.coverUrl
            
            currentSession = MediaSessionData(
                packageName = packageName,
                appName = appName,
                title = finalTitle,
                text = finalText,
                coverUrl = finalCoverUrl,
                deviceName = device.displayName,
                timestamp = timestamp
            )
            currentDevice = device

            // 构建符合超级岛协议的payload，委托给SuperIslandRemoteStore处理差异
            val sourceKey = SOURCE_KEY_PREFIX + "_" + device.uuid

            // 获取上次状态，计算差异
            val lastFeatureId = mediaFeatureIdCache[device.uuid]
            val currentFeatureId = SuperIslandProtocol.computeFeatureId(
                packageName, null, title, text
            )
            val isFirst = lastFeatureId == null
            val isSameSession = lastFeatureId == currentFeatureId

            // 更新特征ID缓存和最后更新时间
            mediaFeatureIdCache[device.uuid] = currentFeatureId
            mediaLastUpdateTime[device.uuid] = System.currentTimeMillis()
            
            // 检查并清理超时会话
            cleanupTimeoutSessions(context)
            
            // 创建或更新定时复传任务
            setupResendTask(context, device.uuid, currentSession!!, device)

            // 获取上次状态，用于保留旧的图标
            val lastState = SuperIslandRemoteStore.getState(sourceKey)
            
            // 构建当前状态，保留旧的图标和文本
            val currentPics = mutableMapOf<String, String>()
            // 保留旧的图标
            lastState?.pics?.let { currentPics.putAll(it) }
            // 如果新消息中有图标，更新图标
            if (coverUrl != null) {
                currentPics["miui.focus.pic_cover"] = coverUrl
            }
            val currentState = SuperIslandProtocol.State(
                title = finalTitle,
                text = finalText,
                paramV2Raw = buildMediaParamV2(finalTitle, finalText).toString(),
                pics = currentPics
            )
            
            // 计算差异
            val diff = SuperIslandProtocol.diff(lastState, currentState)
            
            // 构建payload
            val payload = JSONObject().apply {
                put("packageName", packageName)
                put("appName", appName ?: packageName)
                put("time", timestamp)
            }
            
            // 如果是差异包，添加changes字段
            if (diff.isEmpty()) {
                // 没有变化，返回
                return
            } else if (lastState != null) {
                // 差异包
                payload.put("changes", diff.toJson())
            } else {
                // 全量包
                payload.put("title", title)
                payload.put("text", text)
                payload.put("param_v2_raw", buildMediaParamV2(title, text).toString())
                if (currentPics.isNotEmpty()) {
                    payload.put("pics", JSONObject(currentPics))
                }
            }
            
            val merged = SuperIslandRemoteStore.applyIncoming(sourceKey, payload)

            if (merged != null) {
                // 有内容需要展示，更新浮窗
                val pics = merged.pics
                val paramV2 = merged.paramV2Raw

                // 调用超级岛浮窗显示
                com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager.showFloating(
                    context, sourceKey, merged.title, merged.text, paramV2, pics, appName, false
                )

                Logger.i("RemoteMediaSessionManager", "更新远端媒体会话: $title - $text (来自 ${device.displayName}, isFirst=$isFirst)")
            } else {
                // 结束包，关闭浮窗
                com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager.dismissBySource(sourceKey)
                Logger.i("RemoteMediaSessionManager", "关闭远端媒体会话浮窗: $title (来自 ${device.displayName})")
            }
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "处理远端媒体消息失败", e)
        }
    }

    fun clearSession() {
        // 清除所有设备的媒体会话浮窗
        mediaFeatureIdCache.keys.forEach { deviceUuid ->
            val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
            try {
                // 取消复传任务
                cancelResendTask(deviceUuid)
                // 从Store中移除
                SuperIslandRemoteStore.removeExact(sourceKey)
                // 关闭浮窗
                com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager.dismissBySource(sourceKey)
                Logger.i("RemoteMediaSessionManager", "已关闭设备媒体超级岛浮窗: $sourceKey")
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "关闭媒体超级岛浮窗失败: $sourceKey", e)
            }
        }
        mediaFeatureIdCache.clear()
        mediaLastUpdateTime.clear()
        mediaSessionCache.clear()
        currentSession = null
        currentDevice = null
        Logger.i("RemoteMediaSessionManager", "已清除所有远端媒体会话")
    }

    fun getCurrentSession(): MediaSessionData? = currentSession

    fun getCurrentDevice(): DeviceInfo? = currentDevice

    fun sendMediaControl(
        context: Context,
        deviceManager: DeviceConnectionManager,
        action: String
    ) {
        val device = currentDevice ?: return

        try {
            val request = JSONObject().apply {
                put("type", "MEDIA_CONTROL")
                put("action", action)
            }
            ProtocolSender.sendEncrypted(deviceManager, device, "DATA_MEDIA_CONTROL", request.toString())
            Logger.i("RemoteMediaSessionManager", "已发送媒体控制指令: $action 到 ${device.displayName}")
        } catch (e: Exception) {
            Logger.e("RemoteMediaSessionManager", "发送媒体控制指令失败", e)
        }
    }

    fun onPlayPause(context: Context, deviceManager: DeviceConnectionManager) {
        sendMediaControl(context, deviceManager, "playPause")
    }

    fun onPrevious(context: Context, deviceManager: DeviceConnectionManager) {
        sendMediaControl(context, deviceManager, "previous")
    }

    fun onNext(context: Context, deviceManager: DeviceConnectionManager) {
        sendMediaControl(context, deviceManager, "next")
    }
    
    /**
     * 检查并清理超时的媒体会话
     */
    private fun cleanupTimeoutSessions(context: Context) {
        val currentTime = System.currentTimeMillis()
        val timeoutDevices = mutableListOf<String>()
        
        // 找出超时的设备
        for ((deviceUuid, lastUpdateTime) in mediaLastUpdateTime) {
            if (currentTime - lastUpdateTime > MEDIA_SESSION_TIMEOUT_MS) {
                timeoutDevices.add(deviceUuid)
            }
        }
        
        // 清理超时会话
        for (deviceUuid in timeoutDevices) {
            val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
            try {
                // 取消复传任务
                cancelResendTask(deviceUuid)
                // 从Store中移除
                SuperIslandRemoteStore.removeExact(sourceKey)
                // 关闭浮窗
                com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager.dismissBySource(sourceKey)
                // 清除缓存
                mediaFeatureIdCache.remove(deviceUuid)
                mediaLastUpdateTime.remove(deviceUuid)
                mediaSessionCache.remove(deviceUuid)
                // 如果是当前会话，清除当前会话
                if (currentDevice?.uuid == deviceUuid) {
                    currentSession = null
                    currentDevice = null
                }
                Logger.i("RemoteMediaSessionManager", "已清理超时的媒体会话: $deviceUuid")
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "清理超时媒体会话失败: $deviceUuid", e)
            }
        }
    }
    
    /**
     * 创建或更新定时复传任务
     */
    private fun setupResendTask(context: Context, deviceUuid: String, session: MediaSessionData, device: DeviceInfo) {
        // 取消已存在的复传任务
        cancelResendTask(deviceUuid)
        
        // 创建新的复传任务
        val resendRunnable = Runnable {
            try {
                // 更新最后更新时间
                mediaLastUpdateTime[deviceUuid] = System.currentTimeMillis()
                
                // 获取上次状态，用于保留旧的图标
                val sourceKey = SOURCE_KEY_PREFIX + "_" + deviceUuid
                val lastState = SuperIslandRemoteStore.getState(sourceKey)
                
                // 构建当前状态，保留旧的图标
                val currentPics = mutableMapOf<String, String>()
                // 保留旧的图标
                lastState?.pics?.let { currentPics.putAll(it) }
                // 如果会话中有图标，使用会话中的图标
                if (session.coverUrl != null) {
                    currentPics["miui.focus.pic_cover"] = session.coverUrl
                }
                val currentState = SuperIslandProtocol.State(
                    title = session.title,
                    text = session.text,
                    paramV2Raw = buildMediaParamV2(session.title, session.text).toString(),
                    pics = currentPics
                )
                
                // 计算差异
                val diff = SuperIslandProtocol.diff(lastState, currentState)
                
                // 构建payload
                val payload = JSONObject().apply {
                    put("packageName", session.packageName)
                    put("appName", session.appName ?: session.packageName)
                    put("time", System.currentTimeMillis())
                }
                
                // 如果有变化，发送差异包
                if (!diff.isEmpty()) {
                    payload.put("changes", diff.toJson())
                    val merged = SuperIslandRemoteStore.applyIncoming(sourceKey, payload)
                    
                    if (merged != null) {
                        // 调用超级岛浮窗显示
                        com.xzyht.notifyrelay.feature.notification.superisland.FloatingReplicaManager.showFloating(
                            context, sourceKey, merged.title, merged.text, merged.paramV2Raw, merged.pics, session.appName, false
                        )
                        Logger.d("RemoteMediaSessionManager", "已定时复传媒体会话: ${session.title} - ${session.text} (来自 ${device.displayName})")
                    }
                }
                
                // 重新安排下一次复传
                setupResendTask(context, deviceUuid, session, device)
            } catch (e: Exception) {
                Logger.e("RemoteMediaSessionManager", "定时复传媒体会话失败: $deviceUuid", e)
            }
        }
        
        // 安排第一次复传
        handler.postDelayed(resendRunnable, MEDIA_SESSION_RESEND_INTERVAL_MS)
        
        // 缓存复传任务
        mediaSessionCache[deviceUuid] = MediaSessionCacheData(
            context = context,
            session = session,
            device = device,
            resendRunnable = resendRunnable
        )
    }
    
    /**
     * 取消定时复传任务
     */
    private fun cancelResendTask(deviceUuid: String) {
        val cacheData = mediaSessionCache.remove(deviceUuid)
        if (cacheData != null) {
            handler.removeCallbacks(cacheData.resendRunnable)
        }
    }
    
    /**
     * 构建媒体会话的param_v2结构
     */
    private fun buildMediaParamV2(title: String, text: String): JSONObject {
        val paramV2 = JSONObject()
        // 添加business字段，标识为media类型
        paramV2.put("business", "media")
        val baseInfo = JSONObject()
        baseInfo.put("title", title)
        baseInfo.put("content", text)
        paramV2.put("baseInfo", baseInfo)

        // 布局信息
        val island = JSONObject()
        val bigIsland = JSONObject()
        bigIsland.put("type", "media")
        island.put("bigIslandArea", bigIsland)
        paramV2.put("param_island", island)
        
        return paramV2
    }


}
