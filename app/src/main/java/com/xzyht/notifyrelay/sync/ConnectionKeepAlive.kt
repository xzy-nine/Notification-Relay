package com.xzyht.notifyrelay.sync

import android.content.Context
import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import com.xzyht.notifyrelay.feature.device.model.DeviceInfo
import com.xzyht.notifyrelay.nativecore.NativeCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import notifyrelay.base.util.Logger
import notifyrelay.core.util.BatteryUtils

/**
 * 连接保活与重连策略封装：
 *
 * - 对「已经成功握手并建立信任」的设备：
 *   - 心跳由 Rust 统一心跳调度器自动管理（known_devices + 配对状态），
 *     本类不再维护每设备心跳任务；
 *
 * - 同时，这里还承接「首次连接」时的握手重试逻辑：
 *   - performDeviceConnectionWithRetry 统一处理握手重试 + 认证成功后的状态更新，
 *   - 让 DeviceConnectionManager.connectToDevice 变成一个较薄的入口。
 *
 * 注意：本类只通过 DeviceConnectionManager 暴露的 internal 视图读写状态，
 * 不直接操作其 private 字段，保持边界清晰。
 */
class ConnectionKeepAlive(
    private val context: Context,
    private val deviceManager: DeviceConnectionManager,
    private val scope: CoroutineScope,
) {
    /**
     * 封装设备连接握手与重试逻辑：
     * - 重试与超时由 Rust `nrc_connect_device` 内部完成（3次/5s超时/1s间隔）
     * - 认证成功后更新 AuthInfo/deviceInfoCache（心跳由 Rust 调度器接管）
     * - 失败原因通过 (Boolean, String?) 形式返回
     */
    suspend fun performDeviceConnectionWithRetry(device: DeviceInfo): Pair<Boolean, String?> {
        val ctx = NativeCore.getContext()
        if (ctx == null) return Pair(false, "未初始化")
        // 无效 IP 直接快速失败，避免 Rust 侧按重试与超时消耗更长时间后才失败
        if (device.ip.isBlank() || device.ip == "0.0.0.0") return Pair(false, "设备 IP 无效")
        val batteryLevel = BatteryUtils.getBatteryLevel(context)
        val isCharging = BatteryUtils.isCharging(context)
        val battery = if (isCharging) batteryLevel else -batteryLevel

        val result =
            withContext(Dispatchers.IO) {
                NativeCore.connectDevice(ctx, device.uuid, device.ip, battery, "android")
            }
        if (result == 0) {
            try {
                scope.launch { deviceManager.refreshDevices() }
            } catch (_: Exception) {
            }
            return Pair(true, null)
        }
        // nrc_connect_device 约定：0=收到 ACCEPT(成功)，-1=被拒绝或重试耗尽；无其他返回码
        Logger.e("死神-NotifyRelay", "connectToDevice 连接失败: ${device.displayName}, result=$result")
        return Pair(false, "连接失败")
    }
}
