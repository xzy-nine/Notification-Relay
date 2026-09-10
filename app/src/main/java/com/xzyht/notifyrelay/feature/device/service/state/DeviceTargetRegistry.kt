package com.xzyht.notifyrelay.feature.device.service.state

import com.xzyht.notifyrelay.feature.device.model.DeviceSnapshot
import com.xzyht.notifyrelay.nativecore.NativeCore

/**
 * 平台端**唯一**向 Rust core 登记「设备连接目标」的出口。
 *
 * core 侧的重连状态机（`reconnect.rs`）与已知设备扫描器（`discovery/mod.rs`）都在内存中维护目标，
 * **不落盘**，因此每次进程启动后必须由平台读首帧快照重新登记 —— 这是平台端必须保留的职责
 * （见 [rehydrateFromCore]）。
 *
 * 除本类外，任何模块都不得直接调用 `nrc_reconnect_add_target` / `nrc_add_known_device`。
 */
class DeviceTargetRegistry(
    private val localUuidProvider: () -> String,
) {
    companion object {
        private val INVALID_IPS = setOf("", "0.0.0.0")
    }

    /**
     * 重启重灌：按首帧快照把所有已配对设备重新登记到重连状态机与已知设备扫描器。
     *
     * core 的 target 表是内存态，不重灌会导致重启后已配对设备不再自动重连。
     */
    fun rehydrateFromCore(paired: List<DeviceSnapshot>) {
        for (snap in paired) {
            registerReconnectTarget(snap.uuid, snap.ip)
            registerKnownDevice(snap.uuid, snap.ip)
        }
    }

    /** 登记到 Rust 重连状态机（先移除再添加，重置重试周期）。 */
    fun registerReconnectTarget(
        uuid: String,
        ip: String,
    ) {
        try {
            if (uuid == localUuidProvider()) return
            val ctx = NativeCore.getContext() ?: return
            if (ip in INVALID_IPS) return
            NativeCore.reconnectRemoveTarget(ctx, uuid)
            NativeCore.reconnectAddTarget(ctx, uuid, ip)
        } catch (_: Exception) {
        }
    }

    /** 登记到 Rust 已知设备扫描器（known_device_scanner，用于离线设备探测）。 */
    fun registerKnownDevice(
        uuid: String,
        ip: String,
    ) {
        try {
            if (uuid == localUuidProvider()) return
            val ctx = NativeCore.getContext() ?: return
            if (ip in INVALID_IPS) return
            NativeCore.removeKnownDevice(ctx, uuid)
            NativeCore.addKnownDevice(ctx, uuid, ip)
        } catch (_: Exception) {
        }
    }

    /** 从重连状态机移除目标。 */
    fun removeReconnectTarget(uuid: String) {
        try {
            val ctx = NativeCore.getContext() ?: return
            NativeCore.reconnectRemoveTarget(ctx, uuid)
        } catch (_: Exception) {
        }
    }

    /** 从已知设备扫描器移除（调度器随之停止该设备心跳）。 */
    fun removeKnownDevice(uuid: String) {
        try {
            val ctx = NativeCore.getContext() ?: return
            NativeCore.removeKnownDevice(ctx, uuid)
        } catch (_: Exception) {
        }
    }

    /** 断开 core 侧会话并移除全部目标登记（设备被删除时调用）。 */
    fun removeAllTargets(uuid: String) {
        try {
            val ctx = NativeCore.getContext() ?: return
            NativeCore.removeDeviceSession(ctx, uuid)
        } catch (_: Exception) {
        }
        removeReconnectTarget(uuid)
        removeKnownDevice(uuid)
    }
}
