package com.xzyht.notifyrelay.feature.device.model

import org.json.JSONObject

/**
 * uuid → displayName 的全局只读缓存，供 UI 与业务层按 uuid 反查显示名。
 *
 * 缓存为纯展示用途的弱一致副本：core 快照中的名称为空时用它兜底，
 * 不参与任何连接/认证判定。
 */
object DeviceNameCache {
    private const val CACHE_MAX_ENTRIES = 500

    // 工具：构造 json 格式的通知数据
    fun buildNotificationJson(
        packageName: String,
        appName: String?,
        title: String?,
        text: String?,
        time: Long,
    ): String {
        val json = JSONObject()
        json.put("packageName", packageName)
        json.put("appName", appName ?: packageName)
        json.put("title", title ?: "")
        json.put("text", text ?: "")
        json.put("time", time)
        return json.toString()
    }

    private val globalDeviceNameCache = mutableMapOf<String, String>()

    fun updateGlobalDeviceName(
        uuid: String,
        displayName: String,
    ) {
        synchronized(globalDeviceNameCache) {
            globalDeviceNameCache[uuid] = displayName
            if (globalDeviceNameCache.size > CACHE_MAX_ENTRIES) {
                globalDeviceNameCache.remove(globalDeviceNameCache.keys.first())
            }
        }
    }

    fun getDisplayNameByUuid(uuid: String?): String {
        if (uuid == null) return "未知设备"
        if (uuid == "本机") return "本机"
        synchronized(globalDeviceNameCache) {
            return globalDeviceNameCache[uuid] ?: uuid
        }
    }
}
