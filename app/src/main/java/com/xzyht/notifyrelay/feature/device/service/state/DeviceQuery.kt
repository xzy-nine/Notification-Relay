package com.xzyht.notifyrelay.feature.device.service.state

import com.xzyht.notifyrelay.feature.device.model.AuthInfo
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import notifyrelay.base.util.Logger

/**
 * 设备查询门面（只读）。
 *
 * 汇集 UI 与业务侧需要的设备视图：
 * - [authenticated]：已配对设备表。**内存表只保留平台侧决策（`isAccepted`）与密钥元数据**，
 *   展示元数据（名称 / IP / 端口 / 设备类型）一律从 core 快照派生，不再镜像；
 * - [rejected]：被用户拒绝的设备；
 * - [onlineAuthenticated] / [onlineAuthenticatedCount]：在线且已配对；
 * - [find] / [resolve]：按 uuid 反查设备信息。
 */
class DeviceQuery(
    private val directory: DeviceDirectory,
    private val store: DeviceSnapshotStore,
    private val authenticatedDeviceTable: () -> Map<String, AuthInfo>,
    private val rejectedDeviceIds: () -> Set<String>,
) {
    companion object {
        private const val TAG = "死神-NotifyRelay"
    }

    /** 按 uuid 反查设备信息。 */
    fun find(uuid: String): DeviceInfo? = directory.find(uuid)

    /** 按 uuid 反查设备信息，缺失 IP 时使用提供的回退 IP。 */
    fun resolve(
        uuid: String,
        fallbackIp: String?,
        fallbackPort: Int,
    ): DeviceInfo? = directory.resolve(uuid, fallbackIp, fallbackPort)

    /** 已配对设备表（展示元数据来自 core 快照）。 */
    fun authenticated(): Map<String, AuthInfo> =
        authenticatedDeviceTable().mapValues { (uuid, auth) ->
            val snap = store.snapshot(uuid)
            auth.copy(
                // 回退顺序：有效快照名 → AuthInfo.displayName → DeviceNameCache/UUID
                // （避免 DeviceSnapshotStore.displayName 直接返回 UUID 阻断 AuthInfo.displayName 回退）
                displayName =
                    snap?.name?.takeIf { it.isNotBlank() }
                        ?: auth.displayName?.takeIf { it.isNotBlank() }
                        ?: store.displayName(uuid),
                deviceType = store.deviceType(uuid) ?: auth.deviceType,
                lastIp = snap?.ip?.takeIf { it.isNotEmpty() && it != "0.0.0.0" } ?: auth.lastIp,
                lastPort = snap?.port ?: auth.lastPort,
            )
        }

    /** 被用户拒绝的设备集合。 */
    fun rejected(): Set<String> = rejectedDeviceIds()

    /** 在线且已配对的设备数量。 */
    fun onlineAuthenticatedCount(): Int = directory.onlineAuthenticatedCount()

    /** 在线且已配对的设备列表。 */
    fun onlineAuthenticated(): List<DeviceInfo> =
        directory.onlineAuthenticated().also {
            Logger.d(TAG, "[onlineAuthenticated] 返回结果: ${it.size} 个设备")
        }
}
