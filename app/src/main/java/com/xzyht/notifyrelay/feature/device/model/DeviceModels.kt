package com.xzyht.notifyrelay.feature.device.model

/**
 * 设备展示信息。
 *
 * 仅描述「平台侧用于展示与寻址」的最小集合：
 * 在线状态、配对状态、设备类型等一律以 Rust core 的 `nrc_get_device_list` 快照为准
 * （见 `DeviceSnapshot`），平台端不再自行维护第二份真相。
 */
data class DeviceInfo(
    val uuid: String,
    val displayName: String, // 前端显示名，优先蓝牙名，其次型号
    val ip: String,
    val port: Int,
    var batteryLevel: Int = -1, // 设备电量，默认-1表示未知
    // 充电状态：使用 '*' 表示未知（与 batteryLevel 使用 -1 表示未知一致），'1' 表示充电，'0' 表示未充电
    var chargingStatus: Char = '*', // 充电状态，默认'*'表示未知
)

/**
 * 已认证设备的平台侧记录。
 *
 * 密钥本身由 Rust 私有库持有，平台端只保留展示与寻址所需的元数据。
 */
data class AuthInfo(
    val publicKey: String,
    val sharedSecret: String,
    val isAccepted: Boolean,
    val displayName: String? = null,
    val lastIp: String? = null,
    val lastPort: Int? = null,
    val deviceType: String? = null,
    val battery: String? = null,
)

/** 待处理的配对请求（收到 PAIRING_INIT 后暂存，等待用户确认或取消）。 */
data class PendingPairing(
    val remoteUuid: String,
    val remotePubKey: String,
    val remoteIp: String,
)
