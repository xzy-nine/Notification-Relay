package com.xzyht.notifyrelay.feature.device.service

import android.content.Context
import com.xzyht.notifyrelay.feature.device.service.audio.AudioRelayController
import com.xzyht.notifyrelay.feature.device.service.pairing.HandshakeWaiterRegistry
import com.xzyht.notifyrelay.feature.device.service.statequery.StateQueryResponder

/**
 * 设备域的装配入口（组合根）。
 *
 * 只负责持有 `DeviceConnectionManager` 并向外暴露由它装配出的各协作者，
 * 业务侧应直接依赖协作者类型（如 [AudioRelayController]），而不是通过管理器转发调用。
 */
object DeviceConnectionManagerSingleton {
    @Volatile
    private var sharedDeviceManager: DeviceConnectionManager? = null

    /**
     * 获取DeviceConnectionManager实例
     */
    fun getDeviceManager(context: Context): DeviceConnectionManager =
        sharedDeviceManager ?: synchronized(this) {
            sharedDeviceManager ?: DeviceConnectionManager(context.applicationContext).also { sharedDeviceManager = it }
        }

    /**
     * 音频中继控制器（由 [DeviceConnectionManager] 装配，与其共享生命周期）。
     */
    fun getAudioRelay(context: Context): AudioRelayController = getDeviceManager(context).audioRelay

    /**
     * 握手结果等待器登记表（UI 挂起等待远端配对/连接响应）。
     */
    fun getHandshakeWaiters(context: Context): HandshakeWaiterRegistry = getDeviceManager(context).handshakeWaiters

    /**
     * Rust 心跳「状态查询」响应器（超级岛/媒体会话）。
     */
    fun getStateQueryResponder(context: Context): StateQueryResponder = getDeviceManager(context).stateQueryResponder

    /**
     * 清除DeviceConnectionManager实例（仅在测试时使用）
     */
    fun clearDeviceManager() {
        sharedDeviceManager = null
    }
}
