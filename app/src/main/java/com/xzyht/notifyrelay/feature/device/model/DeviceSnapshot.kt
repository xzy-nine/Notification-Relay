package com.xzyht.notifyrelay.feature.device.model

import kotlin.math.abs

/**
 * Rust core `nrc_get_device_list` 快照条目（平台端的只读投影）。
 *
 * 字段与 FFI 返回一一对应，**平台端不再自行维护第二份真相**：
 * 在线/离线、`lastSeen`、配对状态、可见性过滤全部由 core 决定。
 *
 * 仅 [name] 与 [deviceType] 允许使用「上帧非空值」兜底，因为 core 的
 * `deviceType` 不落库、`name` 在重启首帧可能为空。
 */
data class DeviceSnapshot(
    val uuid: String,
    /** 设备名；core 重启首帧可能为空 */
    val name: String,
    val ip: String,
    val port: Int,
    /** 带符号电量：正=充电，负=放电；|v|>100 视为未知 */
    val battery: Int,
    /** 设备类型，如 android / pc；core 不落库，重启首帧可能为空 */
    val deviceType: String,
    val lastSeen: Long,
    val connected: Boolean,
    /** 是否已配对（core：device_keys 命中或 is_accepted） */
    val paired: Boolean,
    /** 是否在线（阈值由 core 内建：已认证 12s / 未认证 20s） */
    val online: Boolean,
) {
    companion object {
        const val UNKNOWN_DEVICE_TYPE = "unknown"

        /** 电量绝对值超过该值视为未知 */
        const val BATTERY_UNKNOWN_THRESHOLD = 100
    }

    /** 电量是否未知 */
    val batteryUnknown: Boolean get() = abs(battery) > BATTERY_UNKNOWN_THRESHOLD

    /** 充电状态：'1' 充电 / '0' 未充电 / '*' 未知 */
    val chargingStatus: Char
        get() = if (batteryUnknown) '*' else if (battery >= 0) '1' else '0'

    /** 电量百分比；未知时为 -1（与 [DeviceInfo.batteryLevel] 约定一致） */
    val batteryPercent: Int get() = if (batteryUnknown) -1 else abs(battery)

    /** 设备类型是否有效 */
    val hasKnownDeviceType: Boolean get() = deviceType.isNotBlank() && deviceType != UNKNOWN_DEVICE_TYPE

    /** 转换为展示模型；名称为空时使用 [fallbackName]。 */
    fun toDeviceInfo(fallbackName: String = ""): DeviceInfo =
        DeviceInfo(
            uuid = uuid,
            displayName = name.ifEmpty { fallbackName },
            ip = ip,
            port = port,
            batteryLevel = batteryPercent,
            chargingStatus = chargingStatus,
        )
}
