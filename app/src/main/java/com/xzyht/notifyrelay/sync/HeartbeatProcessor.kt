package com.xzyht.notifyrelay.sync

import com.xzyht.notifyrelay.feature.device.service.DeviceConnectionManager
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP 心跳（`HEARTBEAT_TCP`）处理。
 *
 * 运行时状态（名称 / 电量 / 在线 / IP / 设备类型）**全部由 Rust core 的 DeviceRegistry 维护**，
 * 平台端不再镜像；本类只负责「按最小间隔触发一次快照刷新」，
 * 避免高频心跳引发 JNA 调用风暴。
 */
object HeartbeatProcessor {
    private const val REFRESH_MIN_INTERVAL_MS = 500L

    private val lastRefreshAt = AtomicLong(0L)

    /** 心跳载荷（字段与 core `HEARTBEAT_TCP` 报文一致，仅用于日志/调试与自身过滤）。 */
    data class HeartbeatInfo(
        val uuid: String,
        val displayName: String,
        val port: Int,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val deviceType: String,
        val ip: String,
    )

    fun processHeartbeat(
        info: HeartbeatInfo,
        deviceManager: DeviceConnectionManager,
    ) {
        if (info.uuid == deviceManager.uuid) return

        val now = System.currentTimeMillis()
        val prev = lastRefreshAt.get()
        if (now - prev < REFRESH_MIN_INTERVAL_MS || !lastRefreshAt.compareAndSet(prev, now)) return
        deviceManager.triggerDeviceListRefresh()
    }
}
