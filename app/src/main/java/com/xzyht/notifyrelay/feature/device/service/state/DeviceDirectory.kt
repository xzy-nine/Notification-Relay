package com.xzyht.notifyrelay.feature.device.service.state

import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceNameCache
import notifyrelay.base.util.Logger

/**
 * 设备信息的**同步查询**入口，全部数据来自 [DeviceSnapshotStore] 的只读投影。
 *
 * 之所以需要它：`nrc_get_device_list` 是异步快照，而业务侧（通知/图标/应用列表等）
 * 需要在任意线程**同步**按 uuid 反查设备信息。本类只读取投影，不写任何状态。
 */
class DeviceDirectory(
    private val store: DeviceSnapshotStore,
    private val localUuidProvider: () -> String,
    private val localDisplayNameProvider: () -> String,
    private val localIpProvider: () -> String,
    private val defaultPort: Int,
) {
    companion object {
        private const val TAG = "死神-NotifyRelay"
        private val INVALID_IPS = setOf("", "0.0.0.0")
    }

    /** 按 uuid 反查设备信息（优先有有效 IP 的记录，其次兜底；未知设备返回 null）。 */
    fun find(uuid: String): DeviceInfo? {
        store.snapshot(uuid)
            ?.takeIf { it.ip !in INVALID_IPS }
            ?.let { return it.toDeviceInfo(DeviceNameCache.getDisplayNameByUuid(uuid)) }
        store.currentInfo(uuid)
            ?.takeIf { it.ip !in INVALID_IPS }
            ?.let { return it }
        // 已配对但当前无有效 IP：仍返回快照（IP 可能为空，调用方自行判断可连性）
        store.snapshot(uuid)?.let { return it.toDeviceInfo(DeviceNameCache.getDisplayNameByUuid(uuid)) }
        if (uuid == localUuidProvider()) return localInfo()
        return null
    }

    /** 按 uuid 反查设备信息，缺失 IP 时使用提供的回退 IP。 */
    fun resolve(
        uuid: String,
        fallbackIp: String?,
        fallbackPort: Int = defaultPort,
    ): DeviceInfo? {
        val cached = find(uuid)
        if (cached != null && cached.ip !in INVALID_IPS) return cached
        val snap = store.snapshot(uuid)
        val name = snap?.name?.takeIf { it.isNotBlank() } ?: DeviceNameCache.getDisplayNameByUuid(uuid)
        val port = cached?.port ?: snap?.port ?: fallbackPort
        return fallbackIp?.let { DeviceInfo(uuid, name, it, port) }
    }

    /** 本机设备信息。 */
    fun localInfo(): DeviceInfo = DeviceInfo(localUuidProvider(), localDisplayNameProvider(), localIpProvider(), defaultPort)

    /** 在线且已配对的设备数量。 */
    fun onlineAuthenticatedCount(): Int =
        try {
            store.devices.value.count { (uuid, pair) -> pair.second && store.isPaired(uuid) }
        } catch (_: Exception) {
            0
        }

    /** 在线且已配对的设备列表。 */
    fun onlineAuthenticated(): List<DeviceInfo> =
        try {
            store.devices.value
                .filter { (uuid, pair) -> pair.second && store.isPaired(uuid) }
                .map { it.value.first }
                .also { Logger.d(TAG, "[onlineAuthenticated] 返回结果: ${it.size} 个设备") }
        } catch (e: Exception) {
            Logger.e(TAG, "[onlineAuthenticated] 出错: ${e.message}", e)
            emptyList()
        }
}
